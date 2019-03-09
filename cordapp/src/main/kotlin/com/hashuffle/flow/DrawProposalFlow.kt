package com.hashuffle.flow

import co.paralleluniverse.fibers.Suspendable
import com.hashuffle.schema.HashuffleTokenSchemaV1
import com.hashuffle.state.BitcoinDrawState
import com.hashuffle.state.HashuffleTokenState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.ReceiveStateAndRefFlow
import net.corda.core.flows.SendStateAndRefFlow
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria.VaultCustomQueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap


@CordaSerializable
data class DrawProposal(val currentBitcoinBlock: BitcoinDrawState.BitcoinBlock,
                        val participationFee: Long)

/**
 * The DrawProposalFlow is responsible verifying which of the provided parties (in form of sessions)
 * are interested on a new draw. The draw organiser uses this flow to find the parties which are interested
 * and consequently include them on the draw.
 * In order for a node to reply on that, it should execute the logic of the DrawProposalReply flow.
 */
class DrawProposalFlow(val sessionsToPropose: Collection<FlowSession>,
                       val drawProposal: DrawProposal) : FlowLogic<Collection<Pair<Party, List<StateAndRef<HashuffleTokenState>>>>>() {
    companion object {
        object PROPOSAL : ProgressTracker.Step("Sending proposals to counterparties.")
        object RETRIEVED_RESPONSES : ProgressTracker.Step("Retrieved responses by counterparties.")

        @JvmStatic
        fun tracker() = ProgressTracker(PROPOSAL, RETRIEVED_RESPONSES)
    }

    override val progressTracker: ProgressTracker = DrawProposalFlow.tracker()

    @Suspendable
    override fun call(): Collection<Pair<Party, List<StateAndRef<HashuffleTokenState>>>> {
        progressTracker.currentStep = PROPOSAL

        val participations = ArrayList<Pair<Party, List<StateAndRef<HashuffleTokenState>>>>()

        // For all the sessions, send the current bitcoin proposal
        sessionsToPropose.forEach { session ->

            // send the proposal
            session.send(drawProposal)

            // here normally we anticipate either 0 states (if the node is not willing to participate) or more
            val responseStates = subFlow(ReceiveStateAndRefFlow<HashuffleTokenState>(session))

            // the node wants to participate, include
            // them on the participations - else ignore
            participations.add(Pair(session.counterparty, responseStates))
        }

        progressTracker.currentStep = RETRIEVED_RESPONSES

        return participations
    }
}

class DrawProposalReply(val otherSideSession: FlowSession,
                        override val progressTracker: ProgressTracker = DrawProposalReply.tracker()) : FlowLogic<Void?>() {
    companion object {
        object REPLY_TO_PROPOSAL : ProgressTracker.Step("Replying to proposal.")

        @JvmStatic
        fun tracker() = ProgressTracker(REPLY_TO_PROPOSAL)
    }

    @Suspendable
    override fun call(): Void? {
        // Wait to get the proposal
        val proposal = otherSideSession.receive<DrawProposal>().unwrap { p -> p }

        progressTracker.currentStep = REPLY_TO_PROPOSAL

        logger.info("Received proposal for draw with current hash: ${proposal.currentBitcoinBlock.hash}")
        logger.info("Received proposal for draw fee: ${proposal.participationFee}")

        // create the query builder
        val feeCompare = builder { HashuffleTokenSchemaV1.HashuffleToken::value.equal(proposal.participationFee) }

        // we want only unconsumed states
        val criteria = VaultCustomQueryCriteria(feeCompare, status = Vault.StateStatus.UNCONSUMED)

        try {
            // find for a HashuffleToken on the vault which matches the criteria
            val hashuffleToken: StateAndRef<HashuffleTokenState> = serviceHub.vaultService
                    .queryBy(HashuffleTokenState::class.java, criteria).states.first()

            // if found, send the list with the token
            subFlow(SendStateAndRefFlow(otherSideSession, listOf(hashuffleToken)))
        } catch (e: NoSuchElementException) {
            // Send empty list if not found
            subFlow(SendStateAndRefFlow(otherSideSession, listOf()))
        }

        return null
    }
}