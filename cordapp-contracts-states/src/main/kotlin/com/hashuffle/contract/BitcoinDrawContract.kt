package com.hashuffle.contract

import com.hashuffle.state.BitcoinDrawState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.utilities.loggerFor
import org.bitcoinj.core.BitcoinSerializer
import org.bitcoinj.core.Block
import org.bitcoinj.core.Context
import org.bitcoinj.params.MainNetParams
import java.math.BigInteger
import java.nio.ByteBuffer

open class BitcoinDrawContract : Contract {
    companion object {
        @JvmStatic
        val DRAW_CONTRACT_ID = "com.hashuffle.contract.BitcoinDrawContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val inputDrawStates = tx.inputsOfType<BitcoinDrawState>()
        val outputDrawStates = tx.outputsOfType<BitcoinDrawState>()
        val command = tx.commands.requireSingleCommand<BitcoinDrawContract.Commands>()

        when (command.value) {

            // Setup the Draw
            is Commands.Setup -> {

                requireThat {
                    "Should provide exactly one bitcoin draw state as output" using (outputDrawStates.size == 1)

                    val drawState = outputDrawStates.first()

                    "Should be at least 1 participants" using (drawState.drawParticipants.isNotEmpty())

                    "Draw block should be higher than current" using (drawState.currentBlock.blockHeight < drawState.drawBlockHeight)
                }
            }

            is Commands.PerformDraw -> {
                val performDrawCommand = command.value as Commands.PerformDraw
                val drawState = inputDrawStates.first()

                val numOfBlocksToProvide = drawState.drawBlockHeight + drawState.numberOfBlocksForVerification -
                        drawState.currentBlock.blockHeight

                requireThat {
                    "Provided block sequence length other than state's" using (performDrawCommand.blockListForDraw.size == numOfBlocksToProvide)

                    val providedBitcoinBlocks = deserialiseBlockchainBlocks(performDrawCommand.blockListForDraw)

                    "Blockchain is invalid" using isBlockchainValid(drawState.currentBlock.hash, drawState.currentBlock.difficultyTarget, providedBitcoinBlocks)

                    // Find the block to use for the draw
                    val drawBlockIndex = drawState.drawBlockHeight - drawState.currentBlock.blockHeight - 1
                    val drawBlock = providedBitcoinBlocks[drawBlockIndex]
                    val beaconHash = sha256(drawBlock.hashAsString, drawState.numberOfHashRounds)

                    // Calculate the scores for the participants
                    var participantsWithScores = drawState
                            .drawParticipants.map { p -> Pair(p, toInt(SecureHash.sha256(p.ticketId.toString() + beaconHash))) }

                    //Sort based on the hash int results (asc)
                    participantsWithScores = participantsWithScores.sortedBy { p -> p.second }

                    val winner = participantsWithScores.last()

                    "Only the winner can spend the state" using command.signers.contains(winner.first.party.owningKey)
                }
            }
        }
    }

    private fun toInt(hash: SecureHash): BigInteger {
        return BigInteger(hash.toString(), 16).abs()
    }

    /**
     * Receives a string and hash it with the sha256 algorithm
     * by hashRounds.
     */
    private fun sha256(str: String, hashRounds: Int): String {
        var finalHash = str

        for (i in 0..hashRounds) {
            finalHash = SecureHash.sha256(finalHash).toString()
        }

        return finalHash
    }

    /**
     * Deserialises the blocks given in pure ByteArray form each block, into Bitcoin
     * Block objects. In that way further processing can be performed.
     */
    private fun deserialiseBlockchainBlocks(blockListForDraw: List<ByteArray>): List<Block> {
        val params = MainNetParams.get()
        Context.getOrCreate(params)

        val bitcoinSerializer = BitcoinSerializer(params, false)

        return blockListForDraw.map { bitcoinSerializer.deserialize(ByteBuffer.wrap(it)) as Block }
    }

    /*
     * Traverses the provided blocks and verifies the whole block chain having as
     * initial reference the provided starting block hash.
     */
    private fun isBlockchainValid(startingBlockHash: String, difficulty: Long, blockListForDraw: List<Block>): Boolean {
        val logger = loggerFor<BitcoinDrawContract>()

        // initialise BitcoinJ
        var previousBlockHash: String = startingBlockHash

        blockListForDraw.forEach { block ->

            // verify that the block is valid
            if (!isValid(block)) {
                logger.error("Block with hash id " + block.hashAsString + " is not valid")
                return false
            }

            logger.info("Validated block with id " + block.hashAsString)

            if (block.difficultyTarget != difficulty) {
                logger.error("Difficulty is not the expected one: " + block.difficultyTarget + " " + difficulty)
                return false
            }

            // verify that it points correctly to the previous hash
            if (previousBlockHash != block.prevBlockHash.toString()) {
                logger.error("Block with hash id " + block.hashAsString + " doesn't point to previous block with hash " + previousBlockHash)
                return false
            }

            logger.info("Block with hash id " + block.hashAsString + " points to previous block with hash " + previousBlockHash)

            previousBlockHash = block.hashAsString
        }

        return true
    }

    /** Returns true if the hash of the block is OK (lower than difficulty target). */
    private fun isValid(block: Block): Boolean {
        val target: BigInteger = block.getDifficultyTargetAsInteger()
        val h: BigInteger = block.getHash().toBigInteger()

        return h.compareTo(target) <= 0
    }


    interface Commands : CommandData {
        class Setup : Commands
        class PerformDraw(val blockListForDraw: List<ByteArray>) : Commands
    }
}