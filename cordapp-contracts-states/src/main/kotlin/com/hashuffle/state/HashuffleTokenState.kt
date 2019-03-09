package com.hashuffle.state

import com.hashuffle.schema.HashuffleTokenSchemaV1
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

data class HashuffleTokenState(val value: Long,
                               val owner: Party?,
                               override val linearId: UniqueIdentifier = UniqueIdentifier()) :
        LinearState, QueryableState {

    override val participants: List<AbstractParty> get() {
        if (owner != null) {
            return listOf(owner)
        }
        return listOf()
    }

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is HashuffleTokenSchemaV1 -> {
                HashuffleTokenSchemaV1.HashuffleToken(
                        this.value,
                        this.owner,
                        this.linearId.id
                )
            }
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas() = listOf(HashuffleTokenSchemaV1)
}