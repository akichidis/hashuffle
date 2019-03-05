package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.contract.BitcoinDrawContract
import com.hashuffle.state.BitcoinDrawState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.util.stream.IntStream

/**
 * This flow setups the draw by creating the necessary
 * state which all the participants want to participate.
 */
object SetupBitcoinDrawFlow {

    @StartableByRPC
    @InitiatingFlow
    class Setup(val currentBitcoinBlock: BitcoinDrawState.BitcoinBlock,
                val drawBlockHeight: Int,
                val blocksForVerification: Int,
                val participationFee: Long,
                val otherParticipants: List<Party>) : FlowLogic<Pair<UniqueIdentifier, SignedTransaction>>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object SEND_DRAW_PROPOSAL : ProgressTracker.Step("Sending draw proposal")
            object CREATE_DRAW : ProgressTracker.Step("Create the draw.")
            object SIGN_DRAW : ProgressTracker.Step("Sign the draw.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    SEND_DRAW_PROPOSAL,
                    CREATE_DRAW,
                    SIGN_DRAW,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): Pair<UniqueIdentifier, SignedTransaction> {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val me = serviceHub.myInfo.legalIdentities.single()

            // Stage 1. Send draw proposals
            progressTracker.currentStep = SEND_DRAW_PROPOSAL

            // First step should be to send the proposal to
            // the other nodes.

            // Get the responses and include on the draw only the nodes
            // which want to participate. Also, include the token state to participate (it is dictated
            // by the proposal state)
            val otherPartyFlows = otherParticipants.map { party -> initiateFlow(party) }.toSet()

            // send the proposal and gather the positive participations
            val partiesToParticipate = sendDrawProposals(otherPartyFlows)

            requireThat {
                "There should be at least one positive reply" using (partiesToParticipate.isNotEmpty())
            }

            // Stage 2. Create the draw state
            progressTracker.currentStep = CREATE_DRAW

            // create the current block
            val participants = mutableListOf(BitcoinDrawState.Participant(me, 0))

            IntStream.range(0, partiesToParticipate.size).boxed()
                    .forEach { i -> participants.add(BitcoinDrawState.Participant(partiesToParticipate[i], i + 1)) }

            // create the draw state
            val bitcoinDrawState = BitcoinDrawState(currentBitcoinBlock, drawBlockHeight, blocksForVerification, participants)

            // the list of signers
            val signers = participants.map { p -> p.party.owningKey }

            val txCommand = Command(BitcoinDrawContract.Commands.Setup(), signers)
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(bitcoinDrawState, BitcoinDrawContract.DRAW_CONTRACT_ID)
                    .addCommand(txCommand)

            // Stage 2. Sign the transaction.
            progressTracker.currentStep = SIGN_DRAW

            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 3. Send it to other parties to sign
            progressTracker.currentStep = GATHERING_SIGS

            val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, otherPartyFlows, GATHERING_SIGS.childProgressTracker()))

            // Stage 4.
            progressTracker.currentStep = FINALISING_TRANSACTION

            // Notarise and record the transaction in both parties' vaults.
            return Pair(bitcoinDrawState.linearId, subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker())))
        }

        @Suspendable
        private fun sendDrawProposals(otherPartyFlows: Set<FlowSession>): List<Party> {
            // Send the draw proposals and retrieve their responses
            val drawProposal = DrawProposal(currentBitcoinBlock, participationFee)

            val drawProposalResponses = subFlow(DrawProposalFlow(otherPartyFlows, drawProposal))

            // gather only the positive responses
            val positiveResponsesParties = drawProposalResponses.
                    filter { pair -> pair.second }
                    .map { pair ->
                        logger.info("Accepted positive reply: ${pair.first.name}")
                        pair.first }

            return positiveResponsesParties
        }
    }

    @InitiatedBy(SetupBitcoinDrawFlow.Setup::class)
    class Acceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")

        companion object {
            fun tracker() = ProgressTracker(
                    SIGNING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = SIGNING_TRANSACTION

            subFlow(DrawProposalReply(otherPartyFlow))

            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val outputDrawState = stx.tx.outputsOfType<BitcoinDrawState>().single()
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}