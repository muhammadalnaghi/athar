package com.athar.core.data.ledger.codec

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
import com.athar.core.domain.model.financialgraph.LegSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * Typed-failure tests: unsupported versions, malformed wire data, byte
 * bounds, exact-decimal enforcement, certified vocabulary and cross-bound
 * identity binding. Decode must fail typed, without mutation or defaults.
 */
class GraphSnapshotCodecRejectionTest {

    private fun encodedPurchase(): String {
        val observation = FinancialObservation(
            stableId = StableObservationId("obs-1"),
            posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
            economicKind = EconomicKind.PURCHASE,
            direction = MovementDirection.OUT,
            original = SupportedMoney(Money.of("100.00", "SAR"), AmountSupport.EXPLICIT_BOOKED),
            settlement = null,
            routing = RoutingCoverage.Unresolved,
            eventDate = null,
            legacy = null,
            userDeclaration = null,
        )
        val draft = FinancialGraphDraft(
            eventId = "event-1",
            observation = observation,
            sourceNamespace = "sms",
            components = listOf(
                ComponentSpec(
                    "principal", ComponentType.PRINCIPAL, Money.of("100.00", "SAR"),
                    support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = "source:principal:amount",
                ),
            ),
            legs = listOf(
                LegSpec(
                    "bank-leg", "bank", Money.of("-100.00", "SAR"), "principal",
                    support = AmountSupport.EXPLICIT_BOOKED, evidenceRef = "source:bank-leg:amount",
                ),
            ),
        )
        val outcome = FinancialGraphFactory.build(draft)
        assertThat(outcome).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        val graph = (outcome as FinancialGraphOutcome.Created).graph as FinancialEventGraph
        return GraphSnapshotCodec.encode(graph)
    }

    private fun edit(encoded: String, transform: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(encoded).jsonObject.toMutableMap()
        transform(root)
        return JsonObject(root).toString()
    }

    private fun failed(action: () -> GraphSnapshotDecodeResult): GraphSnapshotCodecFailure {
        val result = action()
        assertThat(result).isInstanceOf(GraphSnapshotDecodeResult.Failed::class.java)
        return (result as GraphSnapshotDecodeResult.Failed).failure
    }

    @Test
    fun `rejects unsupported wire versions`() {
        for (version in listOf(0, 2, 99)) {
            val edited = edit(encodedPurchase()) { it["formatVersion"] = JsonPrimitive(version) }
            val failure = failed { GraphSnapshotCodec.decode(edited) }
            assertThat(failure).isInstanceOf(GraphSnapshotCodecFailure.UnsupportedWireVersion::class.java)
            assertThat((failure as GraphSnapshotCodecFailure.UnsupportedWireVersion).version).isEqualTo(version)
        }
    }

    @Test
    fun `rejects blank and malformed payloads`() {
        assertThat(failed { GraphSnapshotCodec.decode("") })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
        assertThat(failed { GraphSnapshotCodec.decode("   ") })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
        assertThat(failed { GraphSnapshotCodec.decode("not json at all") })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
        assertThat(failed { GraphSnapshotCodec.decode(encodedPurchase().dropLast(40)) })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
    }

    @Test
    fun `rejects missing required certified fields`() {
        val root = Json.parseToJsonElement(encodedPurchase()).jsonObject.toMutableMap()
        val snapshot = root["certifiedSnapshot"]!!.jsonObject.toMutableMap()
        snapshot.remove("bookedAmount")
        root["certifiedSnapshot"] = JsonObject(snapshot)
        assertThat(failed { GraphSnapshotCodec.decode(JsonObject(root).toString()) })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
    }

    @Test
    fun `rejects blank required identities`() {
        val edited = edit(encodedPurchase()) {
            val snapshot = it["certifiedSnapshot"]!!.jsonObject.toMutableMap()
            snapshot["eventId"] = JsonPrimitive("")
            it["certifiedSnapshot"] = JsonObject(snapshot)
        }
        assertThat(failed { GraphSnapshotCodec.decode(edited) })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
    }

    @Test
    fun `rejects non exact decimal amounts`() {
        val badBooked = edit(encodedPurchase()) {
            val snapshot = it["certifiedSnapshot"]!!.jsonObject.toMutableMap()
            snapshot["bookedAmount"] = JsonPrimitive("1,50")
            it["certifiedSnapshot"] = JsonObject(snapshot)
        }
        assertThat(failed { GraphSnapshotCodec.decode(badBooked) })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)

        val badRaw = edit(encodedPurchase()) {
            val raw = it["rawDraft"]!!.jsonObject.toMutableMap()
            val observation = raw["observation"]!!.jsonObject.toMutableMap()
            val original = observation["original"]!!.jsonObject.toMutableMap()
            original["amount"] = JsonPrimitive("abc")
            observation["original"] = JsonObject(original)
            raw["observation"] = JsonObject(observation)
            it["rawDraft"] = JsonObject(raw)
        }
        assertThat(failed { GraphSnapshotCodec.decode(badRaw) })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
    }

    @Test
    fun `rejects unknown certified vocabulary`() {
        val badKind = edit(encodedPurchase()) {
            val snapshot = it["certifiedSnapshot"]!!.jsonObject.toMutableMap()
            snapshot["kind"] = JsonPrimitive("WIZARDRY")
            it["certifiedSnapshot"] = JsonObject(snapshot)
        }
        assertThat(failed { GraphSnapshotCodec.decode(badKind) })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)

        val badIssue = edit(encodedPurchase()) {
            val snapshot = it["certifiedSnapshot"]!!.jsonObject.toMutableMap()
            snapshot["classificationIssues"] = JsonArray(listOf(JsonPrimitive("MADE_UP_ISSUE")))
            it["certifiedSnapshot"] = JsonObject(snapshot)
        }
        assertThat(failed { GraphSnapshotCodec.decode(badIssue) })
            .isInstanceOf(GraphSnapshotCodecFailure.MalformedWire::class.java)
    }

    @Test
    fun `rejects cross bound identity mismatches`() {
        val badEvent = edit(encodedPurchase()) {
            val snapshot = it["certifiedSnapshot"]!!.jsonObject.toMutableMap()
            snapshot["eventId"] = JsonPrimitive("event-other")
            it["certifiedSnapshot"] = JsonObject(snapshot)
        }
        assertThat(failed { GraphSnapshotCodec.decode(badEvent) })
            .isInstanceOf(GraphSnapshotCodecFailure.CrossBoundIdentityMismatch::class.java)

        val badObservation = edit(encodedPurchase()) {
            val snapshot = it["certifiedSnapshot"]!!.jsonObject.toMutableMap()
            snapshot["observationIdentity"] = JsonObject(
                linkedMapOf(
                    "namespace" to JsonPrimitive("sms"),
                    "sourceItemId" to JsonPrimitive("obs-other"),
                ),
            )
            it["certifiedSnapshot"] = JsonObject(snapshot)
        }
        assertThat(failed { GraphSnapshotCodec.decode(badObservation) })
            .isInstanceOf(GraphSnapshotCodecFailure.CrossBoundIdentityMismatch::class.java)

        val badEconomic = edit(encodedPurchase()) {
            val snapshot = it["certifiedSnapshot"]!!.jsonObject.toMutableMap()
            snapshot["economicIdentity"] = JsonObject(
                linkedMapOf(
                    "issuer" to JsonPrimitive("ISSUER-1"),
                    "reference" to JsonPrimitive("REF-9"),
                ),
            )
            it["certifiedSnapshot"] = JsonObject(snapshot)
        }
        assertThat(failed { GraphSnapshotCodec.decode(badEconomic) })
            .isInstanceOf(GraphSnapshotCodecFailure.CrossBoundIdentityMismatch::class.java)
    }

    @Test
    fun `rejects payloads beyond the byte bound`() {
        val root = Json.parseToJsonElement(encodedPurchase()).jsonObject.toMutableMap()
        val raw = root["rawDraft"]!!.jsonObject.toMutableMap()
        raw["eventId"] = JsonPrimitive("a".repeat(GraphSnapshotWireFormat.MAX_ENCODED_BYTES + 1))
        root["rawDraft"] = JsonObject(raw)
        assertThat(failed { GraphSnapshotCodec.decode(JsonObject(root).toString()) })
            .isInstanceOf(GraphSnapshotCodecFailure.BoundExceeded::class.java)
    }
}
