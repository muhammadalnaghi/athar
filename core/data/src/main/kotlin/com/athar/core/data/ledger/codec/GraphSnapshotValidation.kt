package com.athar.core.data.ledger.codec

import com.athar.core.domain.calc.financial.BookedBasis
import com.athar.core.domain.calc.financial.KindBasis
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.IncomeNature
import com.athar.core.domain.model.financialgraph.CategoryBasis
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.CompositionResolution
import com.athar.core.domain.model.financialgraph.FeeRelation
import com.athar.core.domain.model.financialgraph.GraphBuildIssue
import com.athar.core.domain.model.financialgraph.RoutingAllocation
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/** Fixed wire-v1 integrity checks, not current-policy or ISO-precision recertification. */
internal object GraphSnapshotValidation {
    private val currency = Regex("[A-Z]{3}")
    private val explicitSupport = setOf("EXPLICIT_BOOKED", "EXPLICIT_TRANSACTION")

    fun validate(value: GraphSnapshotEnvelope): GraphSnapshotCodecFailure? {
        if (value.formatVersion != GraphSnapshotWireFormat.VERSION) {
            return GraphSnapshotCodecFailure.UnsupportedWireVersion(value.formatVersion)
        }
        if (value.policyRevision != GraphSnapshotWireFormat.POLICY_REVISION ||
            value.graphRevision != GraphSnapshotWireFormat.GRAPH_REVISION
        ) return GraphSnapshotCodecFailure.UnsupportedRevision

        val raw = value.rawDraft
        val saved = value.certifiedSnapshot
        if (listOf(raw.components.size, raw.legs.size, saved.components.size, saved.legs.size,
                saved.classificationIssues.size).any { it > GraphSnapshotWireFormat.MAX_COLLECTION_ITEMS }
        ) return GraphSnapshotCodecFailure.BoundExceeded

        require(raw.eventId.isNotBlank() && raw.sourceNamespace.isNotBlank() && raw.observation.stableId.isNotBlank())
        require(saved.eventId.isNotBlank() && saved.observationIdentity.namespace.isNotBlank() &&
            saved.observationIdentity.sourceItemId.isNotBlank())
        listOfNotNull(raw.economicIdentity, saved.economicIdentity).forEach {
            require(it.issuer.isNotBlank() && it.reference.isNotBlank())
        }
        validateRawNumbers(raw)
        validateCertified(saved)
        if (raw.eventId != saved.eventId || raw.sourceNamespace != saved.observationIdentity.namespace ||
            raw.observation.stableId != saved.observationIdentity.sourceItemId ||
            raw.economicIdentity != saved.economicIdentity
        ) return GraphSnapshotCodecFailure.CrossBoundIdentityMismatch
        if (!boundFacts(raw, saved)) return GraphSnapshotCodecFailure.SnapshotBindingMismatch
        return null
    }

    private fun validateRawNumbers(raw: RawGraphDraftDto) {
        // An unsupported/invalid-for-posting secondary value is still valid RAW evidence.
        raw.observation.original?.let { BigDecimal(it.amount) }
        raw.observation.settlement?.let { BigDecimal(it.amount) }
        raw.components.forEach { BigDecimal(it.amount) }
        raw.legs.forEach { BigDecimal(it.delta) }
        raw.observation.eventDate?.let { LocalDate.parse(it) }
        require(raw.observation.routingResolved == (raw.observation.routingAccountRef != null))
    }

    private fun validateCertified(saved: CertifiedGraphSnapshotDto) {
        positiveMoney(saved.bookedAmount, saved.bookedCurrency)
        saved.certifiedOriginal?.let { positiveMoney(it.amount, it.currency) }
        saved.certifiedSettlement?.let { positiveMoney(it.amount, it.currency) }
        enumValueOf<BookedBasis>(saved.bookedBasis)
        enumValueOf<EconomicKind>(saved.kind)
        enumValueOf<IncomeNature>(saved.incomeNature)
        enumValueOf<RoutingAllocation>(saved.routing)
        enumValueOf<CompositionResolution>(saved.composition)
        saved.eventDate?.let { LocalDate.parse(it) }
        require(saved.recordingOutcome in setOf("RECORDED", "CAPTURED_UNRESOLVED_KIND"))
        require(saved.kindResolved == (saved.recordingOutcome == "RECORDED"))
        if (saved.kindResolved) enumValueOf<KindBasis>(requireNotNull(saved.kindBasis))
        else require(saved.kindBasis == null)
        saved.category?.let {
            require(it.categoryId.isNotBlank())
            enumValueOf<CategoryBasis>(it.basis)
        }
        saved.classificationIssues.forEach { enumValueOf<GraphBuildIssue>(it) }
        require(saved.classificationIssues.distinct().size == saved.classificationIssues.size)
        uniqueRefs(saved.components.map { it.componentRef })
        uniqueRefs(saved.legs.map { it.legRef })
        val components = saved.components.map { it.componentRef }.toSet()
        saved.components.forEach {
            positiveMoney(it.amount, it.currency)
            enumValueOf<ComponentType>(it.type)
            it.feeRelation?.let { relation -> enumValueOf<FeeRelation>(relation) }
        }
        saved.legs.forEach {
            require(it.accountRef.isNotBlank() && currency.matches(it.currency))
            require(BigDecimal(it.delta).signum() != 0)
            require(it.componentRef == null || it.componentRef in components)
        }
    }

    private fun boundFacts(raw: RawGraphDraftDto, saved: CertifiedGraphSnapshotDto): Boolean {
        if (raw.observation.eventDate != saved.eventDate ||
            raw.linkedRepresentedFeeRef != saved.linkedRepresentedFeeRef
        ) return false
        fun matchesCertified(money: SnapshotMoneyDto?, observed: SupportedMoneyDto?): Boolean =
            money == null || (observed != null && observed.support in explicitSupport &&
                sameMoney(money.amount, money.currency, observed.amount, observed.currency))
        if (!matchesCertified(saved.certifiedOriginal, raw.observation.original) ||
            !matchesCertified(saved.certifiedSettlement, raw.observation.settlement)
        ) return false
        val backing = when (saved.bookedBasis) {
            "ORIGINAL" -> saved.certifiedOriginal
            "SETTLEMENT" -> saved.certifiedSettlement
            else -> null
        } ?: return false
        if (!sameMoney(saved.bookedAmount, saved.bookedCurrency, backing.amount, backing.currency)) return false
        val rawComponents = raw.components.associateBy { it.componentRef }
        val rawLegs = raw.legs.associateBy { it.legRef }
        if (rawComponents.size != raw.components.size || rawLegs.size != raw.legs.size) return false
        if (saved.components.any { item ->
                val source = rawComponents[item.componentRef]
                source == null || item.type != source.type || item.feeRelation != source.feeRelation ||
                    item.feeEvidenceRef != source.feeEvidenceRef ||
                    !sameMoney(item.amount, item.currency, source.amount, source.currency)
            }
        ) return false
        if (saved.legs.any { item ->
                val source = rawLegs[item.legRef]
                source == null || item.accountRef != source.accountRef ||
                    (item.componentRef != null && item.componentRef != source.componentRef) ||
                    !sameMoney(item.delta, item.currency, source.delta, source.currency)
            }
        ) return false
        // Invalid optional raw category metadata can legitimately produce a null accepted category.
        return saved.category == null || saved.category == raw.category
    }

    private fun positiveMoney(amount: String, unit: String) {
        require(currency.matches(unit) && BigDecimal(amount).signum() > 0)
    }

    private fun sameMoney(a: String, aUnit: String, b: String, bUnit: String): Boolean =
        aUnit == bUnit && BigDecimal(a).compareTo(BigDecimal(b)) == 0

    private fun uniqueRefs(refs: List<String>) {
        require(refs.all { it.isNotBlank() } && refs.distinct().size == refs.size)
    }
}
