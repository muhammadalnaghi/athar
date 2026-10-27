package com.athar.core.data.ledger.codec

import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Trusted local snapshot wire boundary; never a graph recertifier or an untrusted backup guard.
 * No parser/policy/factory is invoked on reads. Hardening external duplicate keys/nesting remains
 * the responsibility of the separate production import boundary before this codec is called.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object GraphSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        prettyPrint = false
    }

    /** Reject unsupported technical size rather than truncate or emit an unreadable snapshot. */
    fun encode(graph: FinancialEventGraph): String {
        val envelope = GraphSnapshotMapper.envelope(graph)
        require(GraphSnapshotValidation.validate(envelope) == null) { "Unsupported graph snapshot shape" }
        val encoded = json.encodeToString(GraphSnapshotEnvelope.serializer(), envelope)
        require(!beyondByteBound(encoded)) { "Graph snapshot exceeds the wire byte bound" }
        return encoded
    }

    fun decode(encoded: String): GraphSnapshotDecodeResult {
        if (beyondByteBound(encoded)) {
            return GraphSnapshotDecodeResult.Failed(GraphSnapshotCodecFailure.BoundExceeded)
        }
        return try {
            // Decode the fixed, non-recursive typed shape, not an arbitrary JsonElement tree.
            val envelope = json.decodeFromString(GraphSnapshotEnvelope.serializer(), encoded)
            val failure = GraphSnapshotValidation.validate(envelope)
            if (failure != null) {
                GraphSnapshotDecodeResult.Failed(failure)
            } else {
                GraphSnapshotDecodeResult.Decoded(
                    rawDraft = envelope.rawDraft.copy(
                        components = envelope.rawDraft.components.toPersistentList(),
                        legs = envelope.rawDraft.legs.toPersistentList(),
                    ),
                    certifiedSnapshot = envelope.certifiedSnapshot.copy(
                        components = envelope.certifiedSnapshot.components.toPersistentList(),
                        legs = envelope.certifiedSnapshot.legs.toPersistentList(),
                        classificationIssues = envelope.certifiedSnapshot.classificationIssues.toPersistentList(),
                    ),
                    formatVersion = envelope.formatVersion,
                    policyRevision = envelope.policyRevision,
                    graphRevision = envelope.graphRevision,
                )
            }
        } catch (_: SerializationException) {
            GraphSnapshotDecodeResult.Failed(GraphSnapshotCodecFailure.MalformedWire)
        } catch (_: IllegalArgumentException) {
            GraphSnapshotDecodeResult.Failed(GraphSnapshotCodecFailure.MalformedWire)
        }
    }

    private fun beyondByteBound(value: String): Boolean =
        value.length > GraphSnapshotWireFormat.MAX_ENCODED_BYTES ||
            value.toByteArray(Charsets.UTF_8).size > GraphSnapshotWireFormat.MAX_ENCODED_BYTES
}
