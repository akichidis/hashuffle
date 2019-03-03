package com.hashuffle

import com.hashuffle.flow.PerformDrawFlow
import com.hashuffle.flow.SetupBitcoinDrawFlow
import com.hashuffle.state.BitcoinDrawState
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.TestIdentity
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail


class BitcoinDrawTest {
    private val rpcUsers = listOf(User("test", "test", permissions = setOf("ALL")))

    private val notary = TestIdentity(CordaX500Name("Notary", "London", "GB"))
    private val partyA = TestIdentity(CordaX500Name("PartyA", "London", "GB"))
    private val partyB = TestIdentity(CordaX500Name("PartyB", "London", "GB"))

    @Test
    @Ignore
    fun `node test`() = withDriver {
        // Start a pair of nodes and wait for them both to be ready.
        val (partyAHandle, partyBHandle) = startNodes(listOf(), partyA, partyB)

        assertEquals(partyB.name, partyAHandle.resolveName(partyB.name))
        assertEquals(partyA.name, partyBHandle.resolveName(partyA.name))
    }

    @Test
    @Ignore
    fun `should successfully setup a bitcoin draw for two participates`() = withDriver {
        // Start a pair of nodes and wait for them both to be ready.
        val (nodeA, nodeB) = startNodes(rpcUsers, partyA, partyB)

        val partyAProxy: CordaRPCOps = newRPCProxy(nodeA)
        val partyBProxy: CordaRPCOps = newRPCProxy(nodeB)

        // The flow parameters
        val currentBitcoinBlock = BitcoinDrawState.BitcoinBlock("00000000000000000009100c3b97060ecaec44d843285f115b0d784502bf4d90",
                564946, 6071846049920)

        val drawBlockHeight = 564947
        val blocksForVerification = 1

        // The partyA setups a draw
        val pairResult = partyAProxy.startFlow(SetupBitcoinDrawFlow::Setup,
                currentBitcoinBlock,
                drawBlockHeight,
                blocksForVerification,
                nodeB.nodeInfo.singleIdentity())
                .returnValue
                .getOrThrow()

        val drawStateId = pairResult.first

        // Query partyA and successfully retrieve draw state
        val drawStateA = partyAProxy
                .vaultQueryByCriteria(QueryCriteria.LinearStateQueryCriteria(null, listOf(drawStateId)), BitcoinDrawState::class.java)
                .states.firstOrNull()

        assertNotNull(drawStateA)

        // Query partyB and successfully retrieve draw state
        val drawStateB = partyBProxy
                .vaultQueryByCriteria(QueryCriteria.LinearStateQueryCriteria(null, listOf(drawStateId)), BitcoinDrawState::class.java)
                .states.firstOrNull()

        assertNotNull(drawStateB)
    }

    @Test
    fun `should successfully setup and perform a draw` () = withDriver {
        // Start a pair of nodes and wait for them both to be ready.
        val (nodeA, nodeB) = startNodes(rpcUsers, partyA, partyB)

        val partyAProxy: CordaRPCOps = newRPCProxy(nodeA)
        val partyBProxy: CordaRPCOps = newRPCProxy(nodeB)

        // GIVEN the flow parameters
        val currentBitcoinBlock = BitcoinDrawState.BitcoinBlock("00000000000000000009100c3b97060ecaec44d843285f115b0d784502bf4d90",
                564946, 388914000)

        val drawBlockHeight = 564947
        val blocksForVerification = 1

        // AND partyA setups a draw
        val pairResult = partyAProxy.startFlow(SetupBitcoinDrawFlow::Setup,
                currentBitcoinBlock,
                drawBlockHeight,
                blocksForVerification,
                nodeB.nodeInfo.singleIdentity())
                .returnValue
                .getOrThrow()

        val drawStateId = pairResult.first

        // AND partyA should successfully retrieve draw state
        val drawStateA = partyAProxy
                .vaultQueryByCriteria(QueryCriteria.LinearStateQueryCriteria(null, listOf(drawStateId)), BitcoinDrawState::class.java)
                .states.firstOrNull()

        assertNotNull(drawStateA)

        // AND partyB should successfully retrieve draw state
        val drawStateB = partyBProxy
                .vaultQueryByCriteria(QueryCriteria.LinearStateQueryCriteria(null, listOf(drawStateId)), BitcoinDrawState::class.java)
                .states.firstOrNull()

        assertNotNull(drawStateB)

        // WHEN partyB performs the draw based on the provided Bitcoin blocks they should be able
        // to spend the transaction. Based on the winning block hash, partyB should be the winner (deterministically defined)
        val winningBlockBytes_564947 = this::class.java.classLoader.getResource("blocks_564947.dat").readBytes()
        val winningBlockBytes_564948 = this::class.java.classLoader.getResource("blocks_564948.dat").readBytes()

        // populate the list of blocks
        val blocks = mutableListOf(winningBlockBytes_564947, winningBlockBytes_564948)

        // AND the first party can't spend the transaction because is not the winner
        try {
            val drawTransaction = partyAProxy.startFlow(PerformDrawFlow::Draw, drawStateId, blocks).returnValue.getOrThrow()
            fail("Party A shouldn't be able to spend the winning state, but they did")
        } catch (ex: TransactionVerificationException) {
        }

        val drawTransaction = partyBProxy.startFlow(PerformDrawFlow::Draw, drawStateId, blocks).returnValue.getOrThrow()

        // THEN it should be the winner and the transaction should pass
        assertNotNull(drawTransaction)
    }

    // Runs a test inside the Driver DSL, which provides useful functions for starting nodes, etc.
    private fun withDriver(test: DriverDSL.() -> Unit) = driver(
            DriverParameters(isDebug = true, startNodesInProcess = true)
    ) { test() }

    private fun newRPCProxy(nodeHandle: NodeHandle): CordaRPCOps = CordaRPCClient(nodeHandle.rpcAddress).start("test", "test").proxy

    // Makes an RPC call to retrieve another node's name from the network map.
    private fun NodeHandle.resolveName(name: CordaX500Name) = rpc.wellKnownPartyFromX500Name(name)!!.name

    // Resolves a list of futures to a list of the promised values.
    private fun <T> List<Future<T>>.waitForAll(): List<T> = map { it.getOrThrow() }

    // Starts multiple nodes simultaneously, then waits for them all to be ready.
    private fun DriverDSL.startNodes(rpcUsers: List<User>, vararg identities: TestIdentity) = identities
            .map { startNode(providedName = it.name, rpcUsers = rpcUsers) }
            .waitForAll()
}