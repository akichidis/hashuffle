package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.contract.BitcoinDrawContract
import com.hashuffle.contract.HashuffleTokenContract
import com.hashuffle.schema.HashuffleTokenSchemaV1
import com.hashuffle.state.BitcoinDrawState
import com.hashuffle.state.HashuffleTokenState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
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
            val participantsWithTokens = sendDrawProposals(otherPartyFlows).toMutableList()

            requireThat {
                "There should be at least one positive reply" using (participantsWithTokens.isNotEmpty())
            }

            // Stage 2. Create the draw state
            progressTracker.currentStep = CREATE_DRAW

            // build the transaction
            val (txBuilder, bitcoinDrawState) = buildTransaction(me, notary, participantsWithTokens, currentBitcoinBlock, drawBlockHeight, blocksForVerification)

            // Stage 2. Sign the transaction.
            progressTracker.currentStep = SIGN_DRAW

            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 3. Send it to other parties to sign
            progressTracker.currentStep = GATHERING_SIGS

            // Get the sessions only of those participants for which
            // we have a hashuffle token - hence accepted to participate on the Draw
            val sessionsOfParticipants = otherPartyFlows
                    .filter { f -> participantsWithTokens.firstOrNull { p -> p.first == f.counterparty } != null  }
                    .toSet()

            val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, sessionsOfParticipants, GATHERING_SIGS.childProgressTracker()))

            // Stage 4.
            progressTracker.currentStep = FINALISING_TRANSACTION

            // Notarise and record the transaction in both parties' vaults.
            return Pair(bitcoinDrawState.linearId, subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker())))
        }

        @Suspendable
        private fun sendDrawProposals(otherPartyFlows: Set<FlowSession>): List<Pair<Party, List<StateAndRef<HashuffleTokenState>>>> {
            // Send the draw proposals and retrieve their responses
            val drawProposal = DrawProposal(currentBitcoinBlock, participationFee)

            // gather the "positive" responses
            val drawProposalResponses = subFlow(DrawProposalFlow(otherPartyFlows, drawProposal))

            return drawProposalResponses.filter { pair ->
                logger.info("Accepted reply from: ${pair.first.name}")
                logger.info("Received: ${pair.second.size} states")

                if (pair.second.isEmpty()) {
                    logger.info("Node ${pair.first.name} will not participate")
                    return@filter false
                }

                logger.info("Node ${pair.first.name} will participate")

                return@filter true
            }
        }

        @Suspendable
        private fun buildTransaction(me: Party,
                                     notary: Party,
                                     participantsWithTokens: MutableList<Pair<Party, List<StateAndRef<HashuffleTokenState>>>>,
                                     currentBitcoinBlock: BitcoinDrawState.BitcoinBlock,
                                     drawBlockHeight: Int,
                                     blocksForVerification: Int): Pair<TransactionBuilder, BitcoinDrawState> {
            // First find a Hashuffle Token for me
            val hashuffleToken = findAHashuffleToken(participationFee)

            requireThat {
                "Couldn't find a hashuffle token to participate. Can't organise draw!" using hashuffleToken.isNotEmpty()
            }

            // add my token as well - for convention reasons the organiser
            // will always be the first one of the list
            participantsWithTokens.add(0, Pair(me, hashuffleToken))

            val participants = ArrayList<BitcoinDrawState.Participant>()

            // create the list of participants
            IntStream.range(0, participantsWithTokens.size).boxed()
                    .forEach { i -> participants.add(BitcoinDrawState.Participant(participantsWithTokens[i].first, i)) }

            // create the draw state
            val bitcoinDrawState = BitcoinDrawState(currentBitcoinBlock, drawBlockHeight, blocksForVerification, participants)

            // the list of signers
            val signers = participants.map { p -> p.party.owningKey }

            val drawCommand = Command(BitcoinDrawContract.Commands.Setup(), signers)

            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(bitcoinDrawState, BitcoinDrawContract.DRAW_CONTRACT_ID)
                    .addCommand(drawCommand)

            // add the hashuffle input states
            // Note: For simplification we assume that every participant's tokens
            // list will contain only one state token
            participantsWithTokens.forEach { p -> txBuilder.addInputState(p.second.first()) }

            // Create the "prize" HashuffleToken and add it to the transaction
            val prizeToken = HashuffleTokenState(participants.size * participationFee, null)
            txBuilder.addOutputState(prizeToken, HashuffleTokenContract.CONTRACT_ID)

            // Add the command which "sums" the tokens
            val tokenCommand = Command(HashuffleTokenContract.Commands.Sum(), participantsWithTokens.map { p -> p.first.owningKey })

            txBuilder.addCommand(tokenCommand)

            return Pair(txBuilder, bitcoinDrawState)
        }

        /**
         * This method returns the hashuffle tokens to match the required participation fee. Although a list
         * is returned, we expect to return either 0 (if there is none available) or 1.
         */
        @Suspendable
        private fun findAHashuffleToken(participationFee: Long): List<StateAndRef<HashuffleTokenState>> {
            // create the query builder
            val feeCompare = builder { HashuffleTokenSchemaV1.HashuffleToken::value.equal(participationFee) }

            // we want only unconsumed states
            val criteria = QueryCriteria.VaultCustomQueryCriteria(feeCompare, status = Vault.StateStatus.UNCONSUMED)

            // find for a HashuffleToken on the vault which matches the criteria
            return serviceHub.vaultService
                    .queryBy(HashuffleTokenState::class.java, criteria).states
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