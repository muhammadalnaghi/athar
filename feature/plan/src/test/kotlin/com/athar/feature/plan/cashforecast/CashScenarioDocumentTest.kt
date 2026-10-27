package com.athar.feature.plan.cashforecast

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class CashScenarioDocumentTest {
    private val document = CashScenarioDocument(
        currency = "KWD",
        openingDate = "2026-11-21",
        openingCash = "-10.123",
        reserve = "0.500",
        horizonEnd = "2026-11-23",
        scopeComplete = true,
        anchorFresh = false,
        flows = listOf(
            CashScenarioFlowDocument("2026-11-22", "0.500"),
            CashScenarioFlowDocument("2026-11-23", "-1.250"),
        ),
    )

    @Test fun `round trip keeps exact decimal strings and literal currency`() {
        val decoded = classifyCashDocument(encodeCashScenarioDocument(document))
        val restored = (decoded as CashDocumentClassification.CashScenario).document
        assertThat(restored).isEqualTo(document)
        assertThat(restored.openingCash).isEqualTo("-10.123")
        assertThat(restored.flows[1].amount).isEqualTo("-1.250")
        assertThat(restored.currency).isEqualTo("KWD")
        assertThat(restored.reserve).isEqualTo("0.500")
    }

    @Test fun `encoded document self describes as version one cash scenario`() {
        val root = encodeCashScenarioDocument(document).let {
            kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject
        }
        assertThat(root["documentType"].let { (it as JsonPrimitive).content }).isEqualTo("cash_scenario")
        assertThat(root["payloadVersion"].let { (it as JsonPrimitive).content.toInt() }).isEqualTo(1)
        assertThat(root["flows"]!!.jsonArray).hasSize(2)
    }

    @Test fun `reserve unknown stays absent rather than becoming zero`() {
        val encoded = encodeCashScenarioDocument(document.copy(reserve = null))
        val restored = (classifyCashDocument(encoded) as CashDocumentClassification.CashScenario).document
        assertThat(restored.reserve).isNull()
    }

    @Test fun `known foreign balance anchor is never classified as a cash scenario`() {
        val payload = """{"documentType":"balance_anchor","payloadVersion":3,"asOf":"2026-11-01"}"""
        assertThat(classifyCashDocument(payload)).isEqualTo(CashDocumentClassification.BalanceAnchor)
    }

    @Test fun `unknown future document types are retained as foreign without guessing`() {
        val payload = """{"documentType":"loan_book_v9","payloadVersion":9,"anything":"goes"}"""
        assertThat(classifyCashDocument(payload)).isEqualTo(CashDocumentClassification.ForeignType)
    }

    @Test fun `newer cash scenario versions stay explicitly unloadable`() {
        val payload = """{"documentType":"cash_scenario","payloadVersion":2,"currency":"SAR"}"""
        assertThat(classifyCashDocument(payload)).isEqualTo(CashDocumentClassification.UnsupportedVersion)
    }

    @Test fun `unknown keys make a v1 document malformed instead of silently dropped`() {
        val payload = """{"documentType":"cash_scenario","payloadVersion":1,"currency":"SAR","surprise":1}"""
        assertThat(classifyCashDocument(payload)).isEqualTo(CashDocumentClassification.Malformed)
    }

    @Test fun `wrong value types are malformed under the strict codec`() {
        val payload = """{"documentType":"cash_scenario","payloadVersion":1,"currency":"SAR",""" +
            """"openingDate":"2026-11-21","openingCash":10,"horizonEnd":"2026-11-23",""" +
            """"scopeComplete":true,"anchorFresh":true}"""
        assertThat(classifyCashDocument(payload)).isEqualTo(CashDocumentClassification.Malformed)
    }

    @Test fun `missing required fields are malformed`() {
        val payload = """{"documentType":"cash_scenario","payloadVersion":1,"currency":"SAR"}"""
        assertThat(classifyCashDocument(payload)).isEqualTo(CashDocumentClassification.Malformed)
    }

    @Test fun `non object payloads are malformed`() {
        assertThat(classifyCashDocument("[]")).isEqualTo(CashDocumentClassification.Malformed)
        assertThat(classifyCashDocument("\"cash_scenario\"")).isEqualTo(CashDocumentClassification.Malformed)
        assertThat(classifyCashDocument("not json")).isEqualTo(CashDocumentClassification.Malformed)
        assertThat(classifyCashDocument("")).isEqualTo(CashDocumentClassification.Malformed)
    }

    @Test fun `a numeric version string is malformed rather than coerced`() {
        val payload = """{"documentType":"cash_scenario","payloadVersion":"1","currency":"SAR",""" +
            """"openingDate":"2026-11-21","openingCash":"1","horizonEnd":"2026-11-23",""" +
            """"scopeComplete":true,"anchorFresh":true}"""
        assertThat(classifyCashDocument(payload)).isEqualTo(CashDocumentClassification.Malformed)
    }
}
