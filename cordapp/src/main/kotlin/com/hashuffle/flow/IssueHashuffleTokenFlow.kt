package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.contract.HashuffleTokenContract
import com.hashuffle.state.HashuffleTokenState
import net.corda.core.contracts.Command
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object IssueHashuffleTokenFlow {

    @StartableByRPC
    @InitiatingFlow
    class Issue(val value: Long) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object CREATE_STATE : ProgressTracker.Step("Create token.")
            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    CREATE_STATE,
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

            // Stage 1. Create the token state
            progressTracker.currentStep = CREATE_STATE

            val token = HashuffleTokenState(value, listOf(me))

            val txCommand = Command(HashuffleTokenContract.Commands.Issue(), me.owningKey)
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(token, HashuffleTokenContract.CONTRACT_ID)
                    .addCommand(txCommand)

            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 2.
            progressTracker.currentStep = FINALISING_TRANSACTION

            // If code reaches that state and gets validated & signed from notary as well.
            return subFlow(FinalityFlow(signedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }
}