package com.athar.feature.plan.position

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class PositionCodecTest {

    private val doc = PositionDocument(
        asOfDate = "2026-10-01",
        note = "أرصدة معلنة",
        entries = listOf(
            PositionEntryDocument("e1", "حساب جاري", PositionSide.ASSET, "SAR", "15000.00", "شخصي"),
            PositionEntryDocument("e2", "قرض سيارة", PositionSide.LIABILITY, "USD", "4200.5", "عائلي"),
        ),
    )

    @Test
    fun `encode decode round trip preserves exact declared strings`() {
        val decoded = PositionDocumentCodec.decode(PositionDocumentCodec.encode(doc))
        assertThat(decoded).isEqualTo(PositionDecodeResult.Success(doc))
    }

    @Test
    fun `amounts stay exact decimal string literals in the stored json`() {
        val json = PositionDocumentCodec.encode(doc)
        val entries = Json.parseToJsonElement(json).jsonObject["entries"]!!.let {
            Json.parseToJsonElement(it.toString())
        }
        // Structural assertion lives in the round trip; here we pin the literal text.
        assertThat(json).contains("\"amount\":\"15000.00\"")
        assertThat(json).contains("\"amount\":\"4200.5\"")
        assertThat(json).contains("\"currency\":\"SAR\"")
        assertThat(json).contains("\"documentType\":\"balance_anchor\"")
        assertThat(Json.parseToJsonElement(json).jsonObject["balanceAnchorVersion"]!!.jsonPrimitive.content).isEqualTo("1")
    }

    @Test
    fun `quoted version is a field type malformation not a version`() {
        val json = PositionDocumentCodec.encode(doc).replace("\"balanceAnchorVersion\":1", "\"balanceAnchorVersion\":\"1\"")
        val decoded = PositionDocumentCodec.decode(json)
        assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.BAD_FIELD_TYPE))
    }

    @Test
    fun `future version stays retained but unloadable`() {
        val json = PositionDocumentCodec.encode(doc).replace("\"balanceAnchorVersion\":1", "\"balanceAnchorVersion\":2")
        val decoded = PositionDocumentCodec.decode(json)
        assertThat(decoded).isEqualTo(PositionDecodeResult.UnsupportedVersion("2"))
    }

    @Test
    fun `extra root key is refused`() {
        val json = PositionDocumentCodec.encode(doc).removeSuffix("}").plus(",\"extra\":1}")
        val decoded = PositionDocumentCodec.decode(json)
        assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.MISSING_OR_EXTRA_ROOT_KEY))
    }

    @Test
    fun `non decimal amount is a strict field type failure`() {
        val bad = doc.copy(entries = listOf(doc.entries[0].copy(amount = "12.3.4")))
        val decoded = PositionDocumentCodec.decode(PositionDocumentCodec.encode(bad))
        assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.BAD_AMOUNT))
    }

    @Test
    fun `bad as of date is refused`() {
        val decoded = PositionDocumentCodec.decode(PositionDocumentCodec.encode(doc.copy(asOfDate = "01-10-2026")))
        assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.BAD_DATE))
    }

    @Test
    fun `unknown side is an entry shape failure`() {
        val json = PositionDocumentCodec.encode(doc).replace("\"side\":\"ASSET\"", "\"side\":\"MAYBE\"")
        val decoded = PositionDocumentCodec.decode(json)
        assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE))
    }

    @Test
    fun `cash scenario siblings classify as foreign`() {
        val foreign = """{"documentType":"cash_scenario","cashScenarioVersion":1}"""
        assertThat(PositionDocumentCodec.decode(foreign)).isEqualTo(PositionDecodeResult.Foreign)
    }

    @Test
    fun `non object root is refused`() {
        assertThat(PositionDocumentCodec.decode("[1]"))
            .isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.NOT_JSON_OBJECT))
    }

    @Test
    fun `encodeLoadablePosition refuses documents that do not reopen identically`() {
        assertThat(encodeLoadablePosition(doc)).isNotNull()
        val undecodable = doc.copy(asOfDate = "2026-13-40")
        assertThat(encodeLoadablePosition(undecodable)).isNull()
    }

    @Test
    fun `present non string note is a field type malformation never silently dropped`() {
        val base = PositionDocumentCodec.encode(doc.copy(note = null)).removeSuffix("}")
        listOf("\"note\":123", "\"note\":null", "\"note\":true", "\"note\":[\"x\"]", "\"note\":{\"k\":1}").forEach { raw ->
            val decoded = PositionDocumentCodec.decode("$base,$raw}")
            assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.BAD_FIELD_TYPE))
        }
    }

    @Test
    fun `note at the declared bound decodes and one char more is over bounds`() {
        val atBound = doc.copy(note = "ن".repeat(POSITION_MAX_NOTE_CHARS))
        assertThat(PositionDocumentCodec.decode(PositionDocumentCodec.encode(atBound)))
            .isEqualTo(PositionDecodeResult.Success(atBound))
        val overBound = doc.copy(note = "ن".repeat(POSITION_MAX_NOTE_CHARS + 1))
        assertThat(PositionDocumentCodec.decode(PositionDocumentCodec.encode(overBound)))
            .isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.OVER_BOUNDS))
    }

    @Test
    fun `entry count over the declared bound is over bounds`() {
        val many = List(POSITION_MAX_ENTRIES + 1) { index ->
            PositionEntryDocument("e$index", "بند", PositionSide.ASSET, "SAR", "1", "شخصي")
        }
        val decoded = PositionDocumentCodec.decode(PositionDocumentCodec.encode(doc.copy(entries = many)))
        assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.OVER_BOUNDS))
    }

    @Test
    fun `entry field over the declared bound is over bounds`() {
        val wide = doc.entries[0].copy(label = "ط".repeat(POSITION_MAX_FIELD_CHARS + 1))
        val decoded = PositionDocumentCodec.decode(PositionDocumentCodec.encode(doc.copy(entries = listOf(wide))))
        assertThat(decoded).isEqualTo(PositionDecodeResult.Malformed(PositionCodecProblem.OVER_BOUNDS))
    }
}
