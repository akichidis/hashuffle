package com.hashuffle.contract

import com.hashuffle.state.HashuffleTokenState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

open class HashuffleTokenContract : Contract {
    companion object {
        @JvmStatic
        val CONTRACT_ID = "com.hashuffle.contract.HashuffleTokenContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val inputStates = tx.inputsOfType<HashuffleTokenState>()
        val outputStates = tx.outputsOfType<HashuffleTokenState>()
        val command = tx.commands.requireSingleCommand<HashuffleTokenContract.Commands>()

        when (command.value) {

            is Commands.Issue -> {

            }

            is Commands.Spend -> {

            }

            is Commands.Sum -> {
                // cover the case where the sum of inputs should be equal to the exact output.
                // if not we reject the transaction.
                val totalInput = inputStates.map { s -> s.value }.reduce { acc, i -> i + acc }

                val outputToken = outputStates.single()

                requireThat {
                    "Total input sum should be equal to output" using (outputToken.value == totalInput)
                }
            }
        }
    }


    interface Commands : CommandData {
        class Issue : Commands
        class Sum: Commands
        class Spend : Commands
    }
}