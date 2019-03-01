package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.contract.BitcoinDrawContract
import com.hashuffle.state.BitcoinDrawState
import net.corda.core.contracts.Command
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.io.File

/**
 * This flow setups the draw by creating the necessary
 * state which all the participants want to participate.
 */
object SetupBitcoinDrawFlow {

    @StartableByRPC
    @InitiatingFlow
    class Setup(val currentBlockHash: String,
                val currentBlockHeight: Int,
                val currentBlockDifficulty: Long,
                val drawBlockHeight: Int,
                val blocksForVerification: Int,
                val otherParticipant: Party) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object CREATE_DRAW : ProgressTracker.Step("Create the draw.")
            object SIGN_DRAW : ProgressTracker.Step("Sign the draw.")
            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    CREATE_DRAW,
                    SIGN_DRAW,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val me = serviceHub.myInfo.legalIdentities.single()

            // Stage 1. Create the draw state
            progressTracker.currentStep = CREATE_DRAW

            /*

            "00000000000000000009100c3b97060ecaec44d843285f115b0d784502bf4d90", 564946, 6071846049920),
                    564947,
                    1,

             */

            // create the current block
            val currentBlock = BitcoinDrawState.BitcoinBlock(currentBlockHash, currentBlockHeight, currentBlockDifficulty)

            val participants = listOf(BitcoinDrawState.Participant(me, 0),
                    BitcoinDrawState.Participant(otherParticipant, 1))

            val bitcoinDrawState = BitcoinDrawState(currentBlock, drawBlockHeight, blocksForVerification, participants)

            val txCommand = Command(BitcoinDrawContract.Commands.Setup(), me.owningKey)
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(bitcoinDrawState, BitcoinDrawContract.DRAW_CONTRACT_ID)
                    .addCommand(txCommand)

            // Stage 2. Sign the transaction.
            progressTracker.currentStep = SIGN_DRAW

            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 3.
            progressTracker.currentStep = FINALISING_TRANSACTION

            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(signedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }
}