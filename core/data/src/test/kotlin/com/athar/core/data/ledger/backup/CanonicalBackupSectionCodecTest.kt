package com.athar.core.data.ledger.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Trusted row transport tests, not external-input, Room, encryption or restore proof. */
class CanonicalBackupSectionCodecTest {
    @Test
    fun `all eight row types and every column round trip without reinterpretation`() {
        val rows = canonicalBackupFixture()
        val decoded = CanonicalBackupSectionCodec.decode(CanonicalBackupSectionCodec.encode(rows))
        assertRowsExactly(rows, decoded)
    }

    @Test
    fun `decimal spelling and literal currencies remain exact`() {
        val original = canonicalBackupFixture()
        val amounts = listOf("100000001.01" to "SAR", "1E+3" to "USD", "1000" to "JPY", "10.1230" to "KWD")
        val rows = original.copy(events = amounts.mapIndexed { index, (amount, currency) ->
            original.events.single().copy(eventId = "event-$index", bookedAmount = amount, bookedCurrency = currency)
        })
        val decoded = CanonicalBackupSectionCodec.decode(CanonicalBackupSectionCodec.encode(rows))
        assertRowsExactly(rows, decoded)
        assertThat(decoded.events.map { it.bookedAmount to it.bookedCurrency }).containsExactlyElementsIn(amounts).inOrder()
    }

    @Test
    fun `null references dates and details stay distinct from empty strings`() {
        val original = canonicalBackupFixture()
        val rows = original.copy(
            events = listOf(original.events.single().copy(economicIdentityIssuer = null, economicIdentityReference = null, eventDate = null)),
            observations = listOf(
                original.observations.single().copy(evidenceRef = null, payloadDigest = null),
                original.observations.single().copy(sourceId = "empty", evidenceRef = "", payloadDigest = ""),
            ),
            components = listOf(original.components.single().copy(feeRelation = null, feeEvidenceRef = null)),
            legs = listOf(original.legs.single().copy(componentRef = null)),
            conflicts = listOf(
                original.conflicts.single().copy(existingEventId = null, detailJson = null),
                original.conflicts.single().copy(conflictId = "empty", existingEventId = "", detailJson = ""),
            ),
        )
        assertRowsExactly(rows, CanonicalBackupSectionCodec.decode(CanonicalBackupSectionCodec.encode(rows)))
    }

    @Test
    fun `source variants conflicts opaque JSON and input order are preserved`() {
        val original = canonicalBackupFixture()
        val rows = original.copy(
            events = listOf(original.events.single().copy(eventId = "z"), original.events.single().copy(eventId = "a")),
            snapshots = listOf(
                original.snapshots.single(),
                original.snapshots.single().copy(sourcePayloadDigest = "second-Case", snapshotDigest = "other-Digest",
                    sourcePayload = byteArrayOf(-1, 0, 13, 10), snapshotJson = " \r\n{\"opaque\":true}\t "),
            ),
            conflicts = listOf(original.conflicts.single().copy(conflictId = "z"), original.conflicts.single().copy(conflictId = "a")),
        )
        assertRowsExactly(rows, CanonicalBackupSectionCodec.decode(CanonicalBackupSectionCodec.encode(rows)))
    }

    @Test
    fun `Long values are not narrowed or passed through floating point`() {
        val original = canonicalBackupFixture()
        val rows = original.copy(
            revisions = listOf(original.revisions.single().copy(revision = Long.MAX_VALUE)),
            events = listOf(original.events.single().copy(snapshotFormatVersion = Long.MAX_VALUE)),
        )
        assertRowsExactly(rows, CanonicalBackupSectionCodec.decode(CanonicalBackupSectionCodec.encode(rows)))
    }

    @Test
    fun `same supplied rows encode deterministically`() {
        val rows = canonicalBackupFixture()
        assertThat(CanonicalBackupSectionCodec.encode(rows)).isEqualTo(CanonicalBackupSectionCodec.encode(rows))
    }

    @Test
    fun `all original byte values survive and decoded arrays have independent ownership`() {
        val original = canonicalBackupFixture()
        val encoded = CanonicalBackupSectionCodec.encode(original)
        val first = CanonicalBackupSectionCodec.decode(encoded)
        val second = CanonicalBackupSectionCodec.decode(encoded)
        assertRowsExactly(original, first)
        first.snapshots.single().sourcePayload[0] = 99
        assertThat(original.snapshots.single().sourcePayload[0].toInt()).isEqualTo(0)
        assertRowsExactly(original, second)
        assertRowsExactly(original, CanonicalBackupSectionCodec.decode(encoded))
    }

    @Test
    fun `explicit empty sections are valid but absent sections cannot become empty history`() {
        val empty = CanonicalBackupRows(emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList())
        assertRowsExactly(empty, CanonicalBackupSectionCodec.decode(CanonicalBackupSectionCodec.encode(empty)))
        assertThrows(SerializationException::class.java) { CanonicalBackupSectionCodec.decode("{}") }
    }

    @Test
    fun `malformed JSON and unknown section fields fail rather than dropping data`() {
        assertThrows(SerializationException::class.java) { CanonicalBackupSectionCodec.decode("not JSON") }
        val root = Json.parseToJsonElement(CanonicalBackupSectionCodec.encode(canonicalBackupFixture())).jsonObject
        val changed = JsonObject(root + ("unknownSection" to JsonPrimitive("not silently discarded")))
        assertThrows(SerializationException::class.java) { CanonicalBackupSectionCodec.decode(changed.toString()) }
    }

    @Test
    fun `invalid original byte Base64 fails explicitly`() {
        val root = Json.parseToJsonElement(CanonicalBackupSectionCodec.encode(canonicalBackupFixture())).jsonObject.toMutableMap()
        val snapshots = root.getValue("snapshots").jsonArray.toMutableList()
        val snapshot = snapshots.first().jsonObject.toMutableMap()
        snapshot["payloadBase64"] = JsonPrimitive("%%%")
        snapshots[0] = JsonObject(snapshot)
        root["snapshots"] = JsonArray(snapshots)
        assertThrows(IllegalArgumentException::class.java) { CanonicalBackupSectionCodec.decode(JsonObject(root).toString()) }
    }

    private fun assertRowsExactly(expected: CanonicalBackupRows, actual: CanonicalBackupRows) {
        assertThat(actual.events).isEqualTo(expected.events)
        assertThat(actual.observations).isEqualTo(expected.observations)
        assertThat(actual.associations).isEqualTo(expected.associations)
        assertThat(actual.components).isEqualTo(expected.components)
        assertThat(actual.legs).isEqualTo(expected.legs)
        assertThat(actual.revisions).isEqualTo(expected.revisions)
        assertThat(actual.conflicts).isEqualTo(expected.conflicts)
        assertThat(actual.snapshots.size).isEqualTo(expected.snapshots.size)
        for ((before, after) in expected.snapshots.zip(actual.snapshots)) {
            assertThat(after.sourceNamespace).isEqualTo(before.sourceNamespace)
            assertThat(after.sourceId).isEqualTo(before.sourceId)
            assertThat(after.sourcePayloadDigest).isEqualTo(before.sourcePayloadDigest)
            assertThat(after.snapshotDigest).isEqualTo(before.snapshotDigest)
            assertThat(after.snapshotJson).isEqualTo(before.snapshotJson)
            assertThat(after.sourcePayload.toList()).isEqualTo(before.sourcePayload.toList())
        }
    }
}
