package com.athar.core.data.ledger.admission

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Real factory graphs; pure preflight is not proof of Room atomicity. */
class AdmissionPreparationTest {
    @Test
    fun `evidence reference is subject to the same per field resource bound`() {
        val result = AdmissionPreparation.prepare(graph(), byteArrayOf(1), "x".repeat(4_097))
        assertThat(result).isInstanceOf(AdmissionPreparation.Preparation.Unprepared::class.java)
        assertThat((result as AdmissionPreparation.Preparation.Unprepared).rejection.reason)
            .isEqualTo(AdmissionRejectionReason.RAW_TEXT_BOUND)
    }

    @Test
    fun `prepared original bytes cannot be mutated through the input alias`() {
        val original = byteArrayOf(1, 0, -1)
        val prepared = ready(AdmissionPreparation.prepare(graph(), original, "synthetic:evidence"))
        original[0] = 99
        assertThat(prepared.originalBytes.toList()).isEqualTo(listOf<Byte>(1, 0, -1))
    }

    @Test
    fun `prepared original bytes cannot be mutated through a returned alias`() {
        val prepared = ready(AdmissionPreparation.prepare(graph(), byteArrayOf(1, 0, -1), "synthetic:evidence"))
        prepared.originalBytes[0] = 99
        assertThat(prepared.originalBytes.toList()).isEqualTo(listOf<Byte>(1, 0, -1))
    }

    @Test
    fun `exact literal multicurrency money and missing date remain intact`() {
        for ((amount, currency) in listOf("100000001.01" to "SAR", "99.99" to "USD", "1000" to "JPY", "10.123" to "KWD")) {
            val prepared = ready(AdmissionPreparation.prepare(graph(amount, currency), byteArrayOf(0, -1), "synthetic:evidence"))
            assertThat(prepared.toEventEntity().bookedAmount).isEqualTo(amount)
            assertThat(prepared.toEventEntity().bookedCurrency).isEqualTo(currency)
            assertThat(prepared.toEventEntity().eventDate).isNull()
            assertThat(prepared.componentEntities().single().amount).isEqualTo(amount)
            assertThat(prepared.payloadDigest).hasLength(64)
            assertThat(prepared.snapshotDigest).hasLength(64)
            assertThat(prepared.payloadDigest).isNotEqualTo(prepared.snapshotDigest)
        }
    }

    @Test
    fun `original byte bound is inclusive and does not truncate`() {
        val accepted = ready(AdmissionPreparation.prepare(graph(), ByteArray(64 * 1024) { 7 }, "synthetic:evidence"))
        assertThat(accepted.originalBytes.size).isEqualTo(64 * 1024)
        val rejected = AdmissionPreparation.prepare(graph(), ByteArray(64 * 1024 + 1), "synthetic:evidence")
        assertThat((rejected as AdmissionPreparation.Preparation.Unprepared).rejection.reason)
            .isEqualTo(AdmissionRejectionReason.ORIGINAL_BYTES_BOUND)
    }

    @Test
    fun `blank evidence reference is rejected without substituting a digest`() {
        val rejected = AdmissionPreparation.prepare(graph(), byteArrayOf(1), "   ")
        assertThat((rejected as AdmissionPreparation.Preparation.Unprepared).rejection.reason)
            .isEqualTo(AdmissionRejectionReason.BLANK_EVIDENCE_REF)
    }

    private fun ready(result: AdmissionPreparation.Preparation): PreparedAdmission {
        assertThat(result).isInstanceOf(AdmissionPreparation.Preparation.Ready::class.java)
        return (result as AdmissionPreparation.Preparation.Ready).prepared
    }

    private fun graph(amount: String = "200.00", currency: String = "SAR"): FinancialEventGraph {
        val money = Money.of(amount, currency)
        val observation = FinancialObservation(
            stableId = StableObservationId("synthetic-observation"),
            posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
            economicKind = EconomicKind.PURCHASE,
            direction = MovementDirection.OUT,
            original = SupportedMoney(money, AmountSupport.EXPLICIT_BOOKED),
            settlement = null,
            routing = RoutingCoverage.Unresolved,
            eventDate = null,
            legacy = null,
            userDeclaration = null,
        )
        val outcome = FinancialGraphFactory.build(
            FinancialGraphDraft(
                eventId = "synthetic-event",
                observation = observation,
                sourceNamespace = "synthetic-sms",
                components = listOf(
                    ComponentSpec(
                        "principal", ComponentType.PRINCIPAL, money,
                        support = AmountSupport.EXPLICIT_TRANSACTION,
                        evidenceRef = "synthetic:principal:amount",
                    ),
                ),
            ),
        )
        assertThat(outcome).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        return (outcome as FinancialGraphOutcome.Created).graph
    }
}
