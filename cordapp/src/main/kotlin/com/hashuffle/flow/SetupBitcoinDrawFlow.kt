package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.contract.BitcoinDrawContract
import com.hashuffle.state.BitcoinDrawState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
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
            object CREATE_DRAW : ProgressTracker.Step("Create the draw.")
            object SIGN_DRAW : ProgressTracker.Step("Sign the draw.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
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

            // First step should be to send the proposal to
            // the other nodes.

            // Get the responses and include on the draw only the nodes
            // which want to participate. Also, include the token state to participate (it is dictated
            // by the proposal state)
            val otherPartyFlows = otherParticipants.map { party -> initiateFlow(party) }.toSet()

            // Send the draw proposals and retrieve their responses
            val drawProposal = DrawProposal(currentBitcoinBlock, participationFee)

            val drawProposalResponses = subFlow(DrawProposalFlow(otherPartyFlows, drawProposal))

            drawProposalResponses.forEach { s -> logger.info("Received: $s") }

            // Stage 1. Create the draw state
            progressTracker.currentStep = CREATE_DRAW

            // create the current block
            val participants = mutableListOf(BitcoinDrawState.Participant(me, 0))

            IntStream.range(0, otherParticipants.size).boxed()
                    .forEach { i -> participants.add(BitcoinDrawState.Participant(otherParticipants[i], i + 1)) }

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

@CordaSerializable
data class DrawProposal(val currentBitcoinBlock: BitcoinDrawState.BitcoinBlock,
                        val participationFee: Long)


class DrawProposalFlow(val sessionsToPropose: Collection<FlowSession>,
                       val drawProposal: DrawProposal) : FlowLogic<Collection<Boolean>>() {
    companion object {
        object PROPOSAL : ProgressTracker.Step("Sending proposals to counterparties.")
        object RETRIEVED_RESPONSES : ProgressTracker.Step("Retrieved responses by counterparties.")

        @JvmStatic
        fun tracker() = ProgressTracker(PROPOSAL, RETRIEVED_RESPONSES)
    }

    override val progressTracker: ProgressTracker = DrawProposalFlow.tracker()

    @Suspendable
    override fun call(): Collection<Boolean> {
        progressTracker.currentStep = PROPOSAL

        // For all the sessions, send the current bitcoin proposal
        val participations = sessionsToPropose.map { session ->

            session.send(drawProposal)
            session.receive<Boolean>().unwrap { s -> s }

        }

        progressTracker.currentStep = RETRIEVED_RESPONSES

        return participations
    }
}

class DrawProposalReply(val otherSideSession: FlowSession,
                        override val progressTracker: ProgressTracker = DrawProposalReply.tracker()) : FlowLogic<Boolean>() {
    companion object {
        object REPLY_TO_PROPOSAL : ProgressTracker.Step("Replying to proposal.")

        @JvmStatic
        fun tracker() = ProgressTracker(REPLY_TO_PROPOSAL)
    }

    @Suspendable
    override fun call(): Boolean {
        val me = serviceHub.myInfo.legalIdentities.single()

        // Wait to get the proposal
        val proposal = otherSideSession.receive<DrawProposal>().unwrap { p -> p }

        progressTracker.currentStep = REPLY_TO_PROPOSAL

        logger.info("Received proposal for draw with current hash: ${proposal.currentBitcoinBlock.hash}")
        logger.info("Received proposal for draw fee: ${proposal.participationFee}")

        otherSideSession.send(true)

        return true
    }
}