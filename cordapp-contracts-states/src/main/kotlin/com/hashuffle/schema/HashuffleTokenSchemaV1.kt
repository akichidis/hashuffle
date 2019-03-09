package com.hashuffle.schema

import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for HashuffleTokenState.
 */
object HashuffleTokenSchema

object HashuffleTokenSchemaV1 : MappedSchema(
        schemaFamily = HashuffleTokenSchema.javaClass,
        version = 1,
        mappedTypes = listOf(HashuffleToken::class.java)) {

    @Entity
    @Table(name = "hashuffle_token_states")
    class HashuffleToken(
            @Column(name = "value")
            var value: Long,

            @Column(name = "owner")
            var owner: AbstractParty?,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor() : this(0, null, UUID.randomUUID())
    }
}