package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.contract.BitcoinDrawContract
import com.hashuffle.state.BitcoinDrawState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * Signs a game move based on a sign request and sends the signed move
 * to the opponent party.
 */
object PerformDrawFlow {

    @StartableByRPC
    @InitiatingFlow
    class Draw(val drawStateId: UniqueIdentifier, val blockListForDraw: List<ByteArray>) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object SEARCH_FOR_DRAW_STATE : ProgressTracker.Step("Searching for draw state.")
            object SIGN_DRAW : ProgressTracker.Step("Sign the draw.")
            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    SEARCH_FOR_DRAW_STATE,
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

            // Stage 1. Search on the vault for the state
            progressTracker.currentStep = SEARCH_FOR_DRAW_STATE

            val bitcoinDrawState = serviceHub.vaultService
                    .queryBy(BitcoinDrawState::class.java, QueryCriteria.LinearStateQueryCriteria(null, listOf(drawStateId)))
                    .states.first()

            val txCommand = Command(BitcoinDrawContract.Commands.PerformDraw(blockListForDraw), me.owningKey)
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(bitcoinDrawState)
                    .addCommand(txCommand)

            // Stage 2. Sign the transaction.
            progressTracker.currentStep = SIGN_DRAW

            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 3.
            progressTracker.currentStep = FINALISING_TRANSACTION

            // If code reaches that state and gets validated & signed from notary as well,
            // then it means that this node is the winning one.
            return subFlow(FinalityFlow(signedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }
}