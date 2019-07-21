package com.hashuffle.schema

import com.hashuffle.state.BitcoinDrawState
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import org.hibernate.annotations.Cascade
import org.hibernate.annotations.Parent
import java.util.*
import javax.persistence.*

/**
 * The family of schemas for BitcoinDrawState.
 */
object BitcoinDrawSchema

object BitcoinDrawSchemaV1 : MappedSchema(
        schemaFamily = BitcoinDrawSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentDraw::class.java, Participant::class.java)) {

    @Entity
    @Table(name = "bitcoin_draw_states")
    class PersistentDraw(
            @Column(name = "current_block_hash")
            var currentBlockHash: String,

            @Column(name = "current_block_height")
            var currentBlockHeight: Int,

            @Column(name = "current_block_target_difficulty")
            var currentBlockTargetDifficulty: Long,

            @Column(name = "draw_block_height")
            var drawBlockHeight: Int,

            @OneToMany(fetch = FetchType.LAZY)
            @JoinColumns(JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id"), JoinColumn(name = "output_index", referencedColumnName = "output_index"))
            @OrderColumn
            @Cascade(org.hibernate.annotations.CascadeType.PERSIST)
            @Column(name = "draw_participants")
            var drawParticipants: MutableList<Participant>,

            @Column(name = "num_of_blocks_for_verification")
            var numberOfBlocksForVerification: Int,

            @Column(name = "num_of_hash_rounds")
            var numberOfHashRounds: Int,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor() : this("", 0, 0, 0, mutableListOf(), 0, 0, UUID.randomUUID())
    }

    @Entity
    @Table(name = "draw_participant")
    class Participant(
            @Id
            @GeneratedValue
            @Column(name = "id", unique = true, nullable = false)
            var id: Int? = null,

            @Column(name = "party")
            var party: AbstractParty,

            @Column(name = "ticket_id")
            var ticketId: Int
    )

}