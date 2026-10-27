package com.athar.core.data.ledger.codec

import kotlinx.serialization.Serializable

/** Fixed wire revisions, not the app version or a claim supplied by UI callers. */
internal object GraphSnapshotWireFormat {
    const val VERSION = 1
    const val POLICY_REVISION = "WM028-07-R6-v1"
    const val GRAPH_REVISION = "WM028-11-M2E-v1"
    const val MAX_ENCODED_BYTES = 4 * 1024 * 1024
    const val MAX_COLLECTION_ITEMS = 10_000
}

@Serializable
internal data class GraphSnapshotEnvelope(
    val formatVersion: Int,
    val policyRevision: String,
    val graphRevision: String,
    val rawDraft: RawGraphDraftDto,
    val certifiedSnapshot: CertifiedGraphSnapshotDto,
)

@Serializable
internal data class ObservationIdentityDto(val namespace: String, val sourceItemId: String)

@Serializable
internal data class EconomicIdentityDto(val issuer: String, val reference: String)

@Serializable
internal data class SnapshotMoneyDto(val amount: String, val currency: String)

@Serializable
internal data class SupportedMoneyDto(val amount: String, val currency: String, val support: String)

@Serializable
internal data class LegacyFactsDto(val status: String?, val type: String?, val value: String?)

@Serializable
internal data class DeclarationDto(val kind: String, val note: String?)

@Serializable
internal data class CategoryDto(val categoryId: String, val basis: String)

@Serializable
internal data class RawObservationDto(
    val stableId: String,
    val postingState: String,
    val postingSupport: String,
    val economicKind: String?,
    val direction: String?,
    val original: SupportedMoneyDto?,
    val settlement: SupportedMoneyDto?,
    val routingResolved: Boolean,
    val routingAccountRef: String?,
    val eventDate: String?,
    val legacy: LegacyFactsDto?,
    val userDeclaration: DeclarationDto?,
    val incomeNature: String,
)

@Serializable
internal data class RawComponentDto(
    val componentRef: String,
    val type: String,
    val amount: String,
    val currency: String,
    val feeRelation: String?,
    val feeEvidenceRef: String?,
    val support: String,
    val evidenceRef: String?,
)

@Serializable
internal data class RawLegDto(
    val legRef: String,
    val accountRef: String,
    val delta: String,
    val currency: String,
    val componentRef: String?,
    val support: String,
    val evidenceRef: String?,
)

@Serializable
internal data class RawGraphDraftDto(
    val eventId: String,
    val sourceNamespace: String,
    val observation: RawObservationDto,
    val economicIdentity: EconomicIdentityDto?,
    val components: List<RawComponentDto>,
    val legs: List<RawLegDto>,
    val category: CategoryDto?,
    val linkedRepresentedFeeRef: String?,
)

@Serializable
internal data class CertifiedComponentDto(
    val componentRef: String,
    val type: String,
    val amount: String,
    val currency: String,
    val feeRelation: String?,
    val feeEvidenceRef: String?,
)

@Serializable
internal data class CertifiedLegDto(
    val legRef: String,
    val accountRef: String,
    val delta: String,
    val currency: String,
    val componentRef: String?,
)

/** A persisted read representation, deliberately not a domain graph certificate. */
@Serializable
internal data class CertifiedGraphSnapshotDto(
    val eventId: String,
    val observationIdentity: ObservationIdentityDto,
    val economicIdentity: EconomicIdentityDto?,
    val bookedAmount: String,
    val bookedCurrency: String,
    val bookedBasis: String,
    val certifiedOriginal: SnapshotMoneyDto?,
    val certifiedSettlement: SnapshotMoneyDto?,
    val kind: String,
    val kindResolved: Boolean,
    val incomeNature: String,
    val recordingOutcome: String,
    val kindBasis: String?,
    val eventDate: String?,
    val routing: String,
    val composition: String,
    val category: CategoryDto?,
    val linkedRepresentedFeeRef: String?,
    val components: List<CertifiedComponentDto>,
    val legs: List<CertifiedLegDto>,
    val classificationIssues: List<String>,
)

internal sealed interface GraphSnapshotDecodeResult {
    data class Decoded(
        val rawDraft: RawGraphDraftDto,
        val certifiedSnapshot: CertifiedGraphSnapshotDto,
        val formatVersion: Int,
        val policyRevision: String,
        val graphRevision: String,
    ) : GraphSnapshotDecodeResult

    data class Failed(val failure: GraphSnapshotCodecFailure) : GraphSnapshotDecodeResult
}

internal sealed interface GraphSnapshotCodecFailure {
    data class UnsupportedWireVersion(val version: Int) : GraphSnapshotCodecFailure
    data object UnsupportedRevision : GraphSnapshotCodecFailure
    data object MalformedWire : GraphSnapshotCodecFailure
    data object BoundExceeded : GraphSnapshotCodecFailure
    data object CrossBoundIdentityMismatch : GraphSnapshotCodecFailure
    data object SnapshotBindingMismatch : GraphSnapshotCodecFailure
}
