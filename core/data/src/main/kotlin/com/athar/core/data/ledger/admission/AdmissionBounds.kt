package com.athar.core.data.ledger.admission

import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.RoutingAllocation
import com.athar.core.domain.model.financial.RoutingCoverage
import java.math.BigDecimal

/**
 * Admission bounds, checked BEFORE encoder allocation. Raw secondary money,
 * declarations, category and evidence strings are bounded exactly like
 * certified facts; violations are rejected, never truncated or normalized.
 */
internal object AdmissionBounds {
    const val MAX_ORIGINAL_BYTES: Int = 64 * 1024
    const val MAX_SNAPSHOT_JSON_BYTES: Int = 256 * 1024
    const val MAX_RAW_COLLECTION_ITEMS: Int = 1_000
    const val MAX_MONEY_PRECISION_DIGITS: Int = 256
    const val MAX_ABS_MONEY_SCALE: Int = 1_024
    const val MAX_TEXT_FIELD_CHARS: Int = 4_096
    const val MAX_AGGREGATE_TEXT_BYTES: Int = 128 * 1024

    /**
     * First-slice mode gate: only UNALLOCATED graphs with zero raw/certified
     * legs and no linked represented-fee event are admissible. Routed or
     * linked modes are rejected explicitly; account existence is never proof
     * of ownership and no account/currency check is bypassed here.
     */
    fun unsupportedModeRejection(graph: FinancialEventGraph): AdmissionRejection? = when {
        graph.routing != RoutingAllocation.UNALLOCATED ->
            AdmissionRejection(
                AdmissionRejectionReason.UNSUPPORTED_ROUTING,
                "routing=${graph.routing.name}",
            )
        graph.source.legs.isNotEmpty() || graph.legs.isNotEmpty() ->
            AdmissionRejection(
                AdmissionRejectionReason.UNSUPPORTED_LEG,
                "rawLegs=${graph.source.legs.size} certifiedLegs=${graph.legs.size}",
            )
        graph.source.linkedRepresentedFeeRef != null ->
            AdmissionRejection(
                AdmissionRejectionReason.UNSUPPORTED_LINKED_FEE,
                "linkedRepresentedFeeRef is present",
            )
        else -> null
    }

    fun rawCollectionRejection(graph: FinancialEventGraph): AdmissionRejection? {
        val rawComponents = graph.source.components.size
        if (rawComponents > MAX_RAW_COLLECTION_ITEMS) {
            return AdmissionRejection(
                AdmissionRejectionReason.RAW_COLLECTION_BOUND,
                "rawComponents=$rawComponents",
            )
        }
        return null
    }

    /** Precision/scale bounds over every raw and certified numeric representation. */
    fun moneyRejection(graph: FinancialEventGraph): AdmissionRejection? {
        val representations = buildList<Pair<String, BigDecimal>> {
            graph.source.observation.original?.let {
                add("observation.original" to it.amount.amount)
            }
            graph.source.observation.settlement?.let {
                add("observation.settlement" to it.amount.amount)
            }
            graph.source.components.forEachIndexed { index, component ->
                add("rawComponent[$index].amount" to component.amount.amount)
            }
            add("booked" to graph.bookedAmount.amount)
            graph.originalAmount?.let { add("certifiedOriginal" to it.amount) }
            graph.settlementAmount?.let { add("certifiedSettlement" to it.amount) }
            graph.components.forEachIndexed { index, component ->
                add("component[$index].amount" to component.amount.amount)
            }
        }
        for ((path, value) in representations) {
            // Bound before allocating money strings; do not use abs(Int.MIN_VALUE).
            if (value.precision() > MAX_MONEY_PRECISION_DIGITS ||
                value.scale().toLong() !in -MAX_ABS_MONEY_SCALE.toLong()..MAX_ABS_MONEY_SCALE.toLong()
            ) {
                return AdmissionRejection(
                    AdmissionRejectionReason.MONEY_REPRESENTATION_BOUND,
                    "$path precision=${value.precision()} scale=${value.scale()}",
                )
            }
        }
        return null
    }

    /** Per-field char bound, then aggregate UTF-8 budget over all walked text. */
    fun textRejection(graph: FinancialEventGraph, evidenceRef: String): AdmissionRejection? {
        val fields = buildList {
            val observation = graph.source.observation
            add("evidenceRef" to evidenceRef)
            add("eventId" to graph.source.eventId)
            add("sourceNamespace" to graph.source.sourceNamespace)
            add("observation.stableId" to observation.stableId.value)
            add("observation.postingState" to observation.posting.state.name)
            add("observation.postingSupport" to observation.posting.support.name)
            observation.economicKind?.let { add("observation.economicKind" to it.name) }
            observation.direction?.let { add("observation.direction" to it.name) }
            observation.original?.let {
                add("observation.original.currency" to it.amount.currency)
                add("observation.original.support" to it.support.name)
            }
            observation.settlement?.let {
                add("observation.settlement.currency" to it.amount.currency)
                add("observation.settlement.support" to it.support.name)
            }
            (observation.routing as? RoutingCoverage.Resolved)?.accountRef?.let {
                add("observation.routingAccountRef" to it)
            }
            observation.eventDate?.let { add("observation.eventDate" to it.toString()) }
            observation.legacy?.let {
                it.status?.let { value -> add("observation.legacy.status" to value) }
                it.type?.let { value -> add("observation.legacy.type" to value) }
                it.value?.let { value -> add("observation.legacy.value" to value) }
            }
            observation.userDeclaration?.let {
                add("observation.userDeclaration.kind" to it.kind.name)
                it.note?.let { note -> add("observation.userDeclaration.note" to note) }
            }
            add("observation.incomeNature" to observation.incomeNature.name)
            graph.source.components.forEachIndexed { index, component ->
                add("rawComponent[$index].componentRef" to component.componentRef)
                add("rawComponent[$index].type" to component.type.name)
                add("rawComponent[$index].currency" to component.amount.currency)
                component.feeRelation?.let { add("rawComponent[$index].feeRelation" to it.name) }
                component.feeEvidenceRef?.let { add("rawComponent[$index].feeEvidenceRef" to it) }
                add("rawComponent[$index].support" to component.support.name)
                component.evidenceRef?.let { add("rawComponent[$index].evidenceRef" to it) }
            }
            graph.source.category?.let {
                add("category.categoryId" to it.categoryId)
                add("category.basis" to it.basis.name)
            }
            (graph.economicIdentity as? com.athar.core.domain.model.financialgraph.EconomicIdentity.IssuerReference)?.let {
                add("economicIdentity.issuer" to it.issuer)
                add("economicIdentity.reference" to it.reference)
            }
            graph.components.forEachIndexed { index, component ->
                add("component[$index].componentRef" to component.componentRef)
                component.feeEvidenceRef?.let { add("component[$index].feeEvidenceRef" to it) }
            }
        }
        for ((path, text) in fields) {
            if (text.length > MAX_TEXT_FIELD_CHARS) {
                return AdmissionRejection(AdmissionRejectionReason.RAW_TEXT_BOUND, "$path length=${text.length}")
            }
        }
        val aggregateBytes = fields.sumOf { (_, text) -> text.toByteArray(Charsets.UTF_8).size }
        if (aggregateBytes > MAX_AGGREGATE_TEXT_BYTES) {
            return AdmissionRejection(AdmissionRejectionReason.AGGREGATE_TEXT_BOUND, "bytes=$aggregateBytes")
        }
        return null
    }
}
