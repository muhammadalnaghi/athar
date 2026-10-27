package com.athar.core.data.ledger.codec

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.ExplicitFinancialDeclaration
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
import com.athar.core.domain.model.financialgraph.FeeRelation
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financialgraph.LegSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class GraphSnapshotCodecIntegrityTest {
    private fun encoded(
        kind: EconomicKind? = EconomicKind.PURCHASE,
        direction: MovementDirection = MovementDirection.OUT,
        declaration: ExplicitFinancialDeclaration? = null,
        settlement: SupportedMoney? = null,
        components: List<ComponentSpec> = emptyList(),
        legs: List<LegSpec> = emptyList(),
    ): String {
        val observation = FinancialObservation(
            StableObservationId("observed-1"),
            PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
            kind, direction,
            SupportedMoney(Money.of("100.00", "SAR"), AmountSupport.EXPLICIT_BOOKED),
            settlement, RoutingCoverage.Unresolved, null, null, declaration,
        )
        val result = FinancialGraphFactory.build(FinancialGraphDraft(
            "event-1", observation, "sms", components = components, legs = legs,
        ))
        assertThat(result).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        return GraphSnapshotCodec.encode((result as FinancialGraphOutcome.Created).graph)
    }

    private fun decoded(value: String): GraphSnapshotDecodeResult.Decoded {
        val result = GraphSnapshotCodec.decode(value)
        assertThat(result).isInstanceOf(GraphSnapshotDecodeResult.Decoded::class.java)
        return result as GraphSnapshotDecodeResult.Decoded
    }

    private fun failure(value: String): GraphSnapshotCodecFailure {
        val result = GraphSnapshotCodec.decode(value)
        assertThat(result).isInstanceOf(GraphSnapshotDecodeResult.Failed::class.java)
        return (result as GraphSnapshotDecodeResult.Failed).failure
    }

    @Test
    fun `captured unknown inflow never turns into recorded income after decode`() {
        val saved = decoded(encoded(kind = null, direction = MovementDirection.IN))
        assertThat(saved.rawDraft.observation.economicKind).isNull()
        assertThat(saved.certifiedSnapshot.kind).isEqualTo("UNRESOLVED_POSTED_MOVEMENT")
        assertThat(saved.certifiedSnapshot.kindResolved).isFalse()
        assertThat(saved.certifiedSnapshot.recordingOutcome).isEqualTo("CAPTURED_UNRESOLVED_KIND")
        assertThat(saved.certifiedSnapshot.kindBasis).isNull()
        assertThat(saved.certifiedSnapshot.incomeNature).isEqualTo("UNCLASSIFIED")
        assertThat(saved.policyRevision).isEqualTo(GraphSnapshotWireFormat.POLICY_REVISION)
        assertThat(saved.graphRevision).isEqualTo(GraphSnapshotWireFormat.GRAPH_REVISION)
    }

    @Test
    fun `actual user declared kind basis is retained not reconstructed from raw classification`() {
        val saved = decoded(encoded(declaration = ExplicitFinancialDeclaration(EconomicKind.PURCHASE, "verified")))
        assertThat(saved.certifiedSnapshot.recordingOutcome).isEqualTo("RECORDED")
        assertThat(saved.certifiedSnapshot.kindBasis).isEqualTo("USER_DECLARED")
        assertThat(saved.rawDraft.observation.userDeclaration?.note).isEqualTo("verified")
    }

    @Test
    fun `unresolved referenced component attribution stays partial and uncertified`() {
        val components = listOf(
            ComponentSpec("p", ComponentType.PRINCIPAL, Money.of("100.00"),
                support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = "principal"),
            ComponentSpec("f", ComponentType.FEE, Money.of("5.00"), FeeRelation.UNKNOWN,
                support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = "fee"),
        )
        val legs = listOf(LegSpec("bank", "a", Money.of("-100.00"), "p",
            AmountSupport.EXPLICIT_BOOKED, "booked"))
        val saved = decoded(encoded(components = components, legs = legs))
        assertThat(saved.rawDraft.legs.single().componentRef).isEqualTo("p")
        assertThat(saved.certifiedSnapshot.legs.single().componentRef).isNull()
        assertThat(saved.certifiedSnapshot.components).isEmpty()
        assertThat(saved.certifiedSnapshot.routing).isEqualTo("PARTIAL")
    }

    @Test
    fun `changed booked money cannot detach an accepted snapshot from its evidence`() {
        val root = Json.parseToJsonElement(encoded()).jsonObject.toMutableMap()
        val saved = root.getValue("certifiedSnapshot").jsonObject.toMutableMap()
        saved["bookedAmount"] = JsonPrimitive("101.00")
        root["certifiedSnapshot"] = JsonObject(saved)
        assertThat(failure(JsonObject(root).toString())).isEqualTo(GraphSnapshotCodecFailure.SnapshotBindingMismatch)
    }

    @Test
    fun `wire money must be quoted decimal strings not JSON numeric values`() {
        val root = Json.parseToJsonElement(encoded()).jsonObject.toMutableMap()
        val saved = root.getValue("certifiedSnapshot").jsonObject.toMutableMap()
        saved["bookedAmount"] = JsonPrimitive(100)
        root["certifiedSnapshot"] = JsonObject(saved)
        assertThat(failure(JsonObject(root).toString())).isEqualTo(GraphSnapshotCodecFailure.MalformedWire)
    }

    @Test
    fun `unknown policy revision fails closed rather than recertifying old facts`() {
        val root = Json.parseToJsonElement(encoded()).jsonObject.toMutableMap()
        root["policyRevision"] = JsonPrimitive("unknown-policy")
        assertThat(failure(JsonObject(root).toString())).isEqualTo(GraphSnapshotCodecFailure.UnsupportedRevision)
    }

    @Test
    fun `invalid raw event date returns a typed wire failure instead of throwing`() {
        val root = Json.parseToJsonElement(encoded()).jsonObject.toMutableMap()
        val raw = root.getValue("rawDraft").jsonObject.toMutableMap()
        val observation = raw.getValue("observation").jsonObject.toMutableMap()
        observation["eventDate"] = JsonPrimitive("not-a-date")
        raw["observation"] = JsonObject(observation)
        root["rawDraft"] = JsonObject(raw)
        assertThat(failure(JsonObject(root).toString())).isEqualTo(GraphSnapshotCodecFailure.MalformedWire)
    }

    @Test
    fun `invalid certified event date returns a typed wire failure instead of throwing`() {
        val root = Json.parseToJsonElement(encoded()).jsonObject.toMutableMap()
        val saved = root.getValue("certifiedSnapshot").jsonObject.toMutableMap()
        saved["eventDate"] = JsonPrimitive("not-a-date")
        root["certifiedSnapshot"] = JsonObject(saved)
        assertThat(failure(JsonObject(root).toString())).isEqualTo(GraphSnapshotCodecFailure.MalformedWire)
    }

    @Test
    fun `raw labels remain raw rather than being reinterpreted by the current policy`() {
        val root = Json.parseToJsonElement(encoded()).jsonObject.toMutableMap()
        val raw = root.getValue("rawDraft").jsonObject.toMutableMap()
        val observation = raw.getValue("observation").jsonObject.toMutableMap()
        observation["incomeNature"] = JsonPrimitive("UNRECOGNIZED_SOURCE_LABEL")
        raw["observation"] = JsonObject(observation)
        root["rawDraft"] = JsonObject(raw)
        val saved = decoded(JsonObject(root).toString())
        assertThat(saved.rawDraft.observation.incomeNature).isEqualTo("UNRECOGNIZED_SOURCE_LABEL")
        assertThat(saved.certifiedSnapshot.incomeNature).isEqualTo("UNCLASSIFIED")
    }

    @Test
    fun `raw heuristic exponent is preserved compactly without expanding or certifying it`() {
        val payload = encoded(settlement = SupportedMoney(
            Money.of("1E+1000000", "USD"), AmountSupport.HEURISTIC_ONLY,
        ))
        val saved = decoded(payload)
        assertThat(payload.length).isLessThan(8000)
        assertThat(saved.rawDraft.observation.settlement?.amount).isEqualTo("1E+1000000")
        assertThat(saved.certifiedSnapshot.certifiedSettlement).isNull()
        assertThat(saved.certifiedSnapshot.bookedAmount).isEqualTo("100.00")
    }
}
