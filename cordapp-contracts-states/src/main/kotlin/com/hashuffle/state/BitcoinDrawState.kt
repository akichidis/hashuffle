package com.hashuffle.state

import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable

data class BitcoinDrawState(// the current bitcoin block which is used as referrence
                            val currentBlock: BitcoinBlock,

                            // the height of the block which will be used - based on the current
                            // for instance, if the current block is the 1000, and the drawBlockHeight = 5, then
                            // the block which will be used for the draw it should be the 1005
                            val drawBlockHeight: Int,

                            // This number of blocks should be provided after the
                            // draw block, in order to verify that the draw block
                            // is not an orphan one.
                            val numberOfBlocksForVerification: Int,

                            val drawParticipants: List<Participant>): ContractState {

    override val participants: List<AbstractParty> get() = drawParticipants.map { p -> p.party }

    /* Every participant is associated with a public key & ticketId for the draw */
    @CordaSerializable
    data class Participant(val party: Party,
                           val ticketId: Int) {
    }

    @CordaSerializable
    data class BitcoinBlock(// the block's hash (solved) - should be something like
                            // 00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048
                            val hash: String,
                            // the block's height
                            var blockHeight: Int,
                            // the current block's difficulty. When a draw is performed, the difficulty of the
                            // provided blocks should match the current one (we ignore for now the cases where network's
                            // difficulty is adjusted in the meanwhile)
                            val currentBlockDifficulty: Long) {

    }
}