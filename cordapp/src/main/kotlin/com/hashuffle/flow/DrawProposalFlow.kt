package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.state.BitcoinDrawState
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap


@CordaSerializable
data class DrawProposal(val currentBitcoinBlock: BitcoinDrawState.BitcoinBlock,
                        val participationFee: Long)


class DrawProposalFlow(val sessionsToPropose: Collection<FlowSession>,
                       val drawProposal: DrawProposal) : FlowLogic<Collection<Pair<Party, Boolean>>>() {
    companion object {
        object PROPOSAL : ProgressTracker.Step("Sending proposals to counterparties.")
        object RETRIEVED_RESPONSES : ProgressTracker.Step("Retrieved responses by counterparties.")

        @JvmStatic
        fun tracker() = ProgressTracker(PROPOSAL, RETRIEVED_RESPONSES)
    }

    override val progressTracker: ProgressTracker = DrawProposalFlow.tracker()

    @Suspendable
    override fun call(): Collection<Pair<Party, Boolean>> {
        progressTracker.currentStep = PROPOSAL

        // For all the sessions, send the current bitcoin proposal
        val participations = sessionsToPropose.map { session ->

            // send the proposal
            session.send(drawProposal)

            // receive the response
            val response = session.receive<Boolean>().unwrap { s -> s }

            // create the pair with the corresponding party
            Pair(session.counterparty, response)
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