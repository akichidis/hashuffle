package com.hashuffle.schema

import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import org.hibernate.annotations.Cascade
import java.util.*
import javax.persistence.*

/**
 * The family of schemas for HashuffleTokenState.
 */
object HashuffleTokenSchema

object HashuffleTokenSchemaV1 : MappedSchema(
        schemaFamily = HashuffleTokenSchema.javaClass,
        version = 1,
        mappedTypes = listOf(HashuffleToken::class.java, Owner::class.java)) {

    @Entity
    @Table(name = "hashuffle_token_states")
    class HashuffleToken(
            @Column(name = "value")
            var value: Long,

            @OneToMany(fetch = FetchType.LAZY)
            @JoinColumns(JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id"), JoinColumn(name = "output_index", referencedColumnName = "output_index"))
            @OrderColumn
            @Cascade(org.hibernate.annotations.CascadeType.PERSIST)
            @Column(name = "owners")
            var owners: MutableList<Owner>,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor() : this(0, mutableListOf(), UUID.randomUUID())
    }

    @Entity
    @Table(name = "hashuffle_token_owners")
    class Owner(
            @Id
            @GeneratedValue
            @Column(name = "id", unique = true, nullable = false)
            var id: Int? = null,

            @Column(name = "party")
            var party: AbstractParty
    )

}