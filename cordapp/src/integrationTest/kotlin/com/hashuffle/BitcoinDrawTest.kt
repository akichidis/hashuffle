package com.hashuffle

import com.hashuffle.flow.IssueHashuffleTokenFlow
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
import org.bitcoinj.core.BitcoinSerializer
import org.bitcoinj.core.Block
import org.bitcoinj.core.Context
import org.bitcoinj.params.MainNetParams
import org.junit.Ignore
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Future
import java.util.stream.IntStream
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail


class BitcoinDrawTest {
    private val rpcUsers = listOf(User("test", "test", permissions = setOf("ALL")))

    private val notary = TestIdentity(CordaX500Name("Notary", "London", "GB"))
    private val partyA = TestIdentity(CordaX500Name("PartyA", "London", "GB"))
    private val partyB = TestIdentity(CordaX500Name("PartyB", "London", "GB"))
    private val partyC = TestIdentity(CordaX500Name("PartyC", "London", "GB"))

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
        val currentBitcoinBlock = BitcoinDrawState.BitcoinBlock("0000000000000000002214f7766f846fedd7a8f5bb7c3d65d15f13158fe907f0",
                564939, 388914000)

        val drawBlockHeight = 564942
        val blocksForVerification = 6
        val participationFee = 10L

        // The partyA setups a draw
        val pairResult = partyAProxy.startFlow(SetupBitcoinDrawFlow::Setup,
                currentBitcoinBlock,
                drawBlockHeight,
                blocksForVerification,
                participationFee,
                listOf(nodeB.nodeInfo.singleIdentity()))
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
    fun `should successfully setup and perform a draw - only winner can spend the draw state` () = withDriver {
        /*
            The setup is the following:

            partyA ticket ID = 0
            partyB ticket ID = 1
            partyC ticket ID = 2

            draw block hash = 000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b

            partyATicket + hash = 0 + 0000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b = 00000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b
            partyBTicket + hash = 1 + 0000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b = 10000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b
            partyCTicket + hash = 2 + 0000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b = 20000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b

            partyA -> int(00000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b) -> 78269016579367116504809940864964583459298460836234487301508747985658428620108
            partyB -> int(10000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b) -> 100177116069166571919133493156763800146221258703109658905706358661026942163307
            partyC -> int(20000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b) -> 22086422636655313275234111700317867271265478409424734635483530821006776939120

            After ordering ASC, the last one is partyB

            winner -> partyB
         */

        // Start a pair of nodes and wait for them both to be ready.
        val (nodeA, nodeB, nodeC) = startNodes(rpcUsers, partyA, partyB, partyC)

        val partyAProxy: CordaRPCOps = newRPCProxy(nodeA)
        val partyBProxy: CordaRPCOps = newRPCProxy(nodeB)

        // GIVEN the flow parameters
        val currentBlockHeight = 564939
        val block = readBitcoinBlock(currentBlockHeight)
        val currentBlockHashString = block.hashAsString
        val currentDifficultyTarget = block.difficultyTarget

        val currentBitcoinBlock = BitcoinDrawState.BitcoinBlock(currentBlockHashString, currentBlockHeight, currentDifficultyTarget)

        val drawBlockHeight = 564943 //hash id: 000000000000000000133629449fa3c77646df4694a5dd26a165a1719999f88b
        val blocksForVerification = 5
        val participationFee = 10L

        // give some tokens for both party A & B - for simplicity reasons the
        // nodes can issue those tokens them selfs
        val tokenFee = 10L
        partyAProxy.startFlow(IssueHashuffleTokenFlow::Issue, tokenFee).returnValue.getOrThrow()
        partyBProxy.startFlow(IssueHashuffleTokenFlow::Issue, tokenFee).returnValue.getOrThrow()

        // AND partyA setups a draw
        val pairResult = partyAProxy.startFlow(SetupBitcoinDrawFlow::Setup,
                currentBitcoinBlock,
                drawBlockHeight,
                blocksForVerification,
                participationFee,
                listOf(nodeB.nodeInfo.singleIdentity(), nodeC.nodeInfo.singleIdentity()))
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
        val blocks = loadBitcoinBlocks(564940, 564948)

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

    private fun readBitcoinBlock(bitoinBlockHeight: Int): Block {
        val blockBytes = this::class.java.classLoader.getResource("blocks_$bitoinBlockHeight.dat").readBytes()

        val params = MainNetParams.get()
        Context.getOrCreate(params)

        val bitcoinSerializer = BitcoinSerializer(params, false)

        return bitcoinSerializer.deserialize(ByteBuffer.wrap(blockBytes)) as Block
    }

    private fun loadBitcoinBlocks(fromBitcoinBlock : Int, toBitcoinBlock: Int): List<ByteArray> {
        return IntStream
                .rangeClosed(fromBitcoinBlock, toBitcoinBlock)
                .boxed()
                .map { i -> this::class.java.classLoader.getResource("blocks_$i.dat").readBytes() }
                .toList()
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