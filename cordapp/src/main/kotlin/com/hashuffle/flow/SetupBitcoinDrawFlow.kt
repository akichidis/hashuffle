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
import java.io.File
import java.util.*

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
                val otherParticipant: Party) : FlowLogic<Pair<UniqueIdentifier, SignedTransaction>>() {
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

            val otherNode = serviceHub.networkMapCache.getNodesByLegalName(otherParticipant.name)

            // Stage 1. Create the draw state
            progressTracker.currentStep = CREATE_DRAW

            // create the current block
            val participants = mutableListOf(BitcoinDrawState.Participant(me, 0),
                    BitcoinDrawState.Participant(otherParticipant, 1))

            // create the draw state
            val bitcoinDrawState = BitcoinDrawState(currentBitcoinBlock, drawBlockHeight, blocksForVerification, participants)

            // the list of signers
            val signers = listOf(me.owningKey, otherParticipant.owningKey)

            val txCommand = Command(BitcoinDrawContract.Commands.Setup(), signers)
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(bitcoinDrawState, BitcoinDrawContract.DRAW_CONTRACT_ID)
                    .addCommand(txCommand)

            // Stage 2. Sign the transaction.
            progressTracker.currentStep = SIGN_DRAW

            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 3. Send it to other parties to sign
            progressTracker.currentStep = GATHERING_SIGS

            val otherPartyFlow = initiateFlow(otherParticipant)
            val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))

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

            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val outputDrawState = stx.tx.outputsOfType<BitcoinDrawState>().single()
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}