package com.athar.core.data.position

import com.athar.core.domain.model.position.DeclaredPositionStatus
import com.athar.core.domain.model.position.latestDeclaredPosition
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DeclaredPositionPayloadReaderTest {
    @Test
    fun `liability only remains negative and never fills an undeclared currency`() {
        val result = DeclaredPositionPayloadReader.read(plan(entry("LIABILITY", "SAR", "1000.02")))!!
        assertThat(result.netByCurrency).containsExactly("SAR", "-1000.02")
        assertThat(result.assetsByCurrency).isEmpty()
        assertThat(result.status).isEqualTo(DeclaredPositionStatus.USER_DECLARED)
        assertThat(result.hasUnstructuredOwnershipLabels).isTrue()
    }

    @Test
    fun `literal currencies and exact decimals stay separate`() {
        val result = DeclaredPositionPayloadReader.read(plan(
            entry("ASSET", "SAR", "1000.01") + "," + entry("LIABILITY", "SAR", "600.02") + "," + entry("ASSET", "KWD", "3.125"),
        ))!!
        assertThat(result.netByCurrency).containsExactly("SAR", "399.99", "KWD", "3.125")
        assertThat(result.revision).isEqualTo(2L)
    }

    @Test
    fun `wrong primitive types and version syntax never become money`() {
        val valid = plan(entry("ASSET", "SAR", "1"))
        listOf("\"1\"", "1.0", "1e0", "null", "true", "2").forEach { token ->
            assertThat(DeclaredPositionPayloadReader.read(valid.copy(payloadJson = valid.payloadJson.replace("\"balanceAnchorVersion\":1", "\"balanceAnchorVersion\":$token")))).isNull()
        }
        assertThat(DeclaredPositionPayloadReader.read(valid.copy(payloadJson = valid.payloadJson.replace("\"amount\":\"1\"", "\"amount\":1")))).isNull()
    }

    @Test
    fun `foreign and malformed documents remain excluded`() {
        val valid = plan(entry("ASSET", "SAR", "1"))
        assertThat(DeclaredPositionPayloadReader.read(valid.copy(kind = SavedFinancialPlanKind.GOAL))).isNull()
        assertThat(DeclaredPositionPayloadReader.read(valid.copy(payloadVersion = 2))).isNull()
        assertThat(DeclaredPositionPayloadReader.read(valid.copy(payloadJson = valid.payloadJson.replace("balance_anchor", "cash_scenario")))).isNull()
        assertThat(DeclaredPositionPayloadReader.read(valid.copy(payloadJson = valid.payloadJson.replace("2026-10-24", "2026-02-30")))).isNull()
        assertThat(DeclaredPositionPayloadReader.read(plan(""))).isNull()
    }

    @Test
    fun `projection magnitude is bounded before arithmetic and formatting`() {
        assertThat(DeclaredPositionPayloadReader.read(plan(entry("ASSET", "SAR", "1E+1000000000")))).isNull()
        assertThat(DeclaredPositionPayloadReader.read(plan(entry("ASSET", "SAR", "1E-1000000000")))).isNull()
    }

    @Test
    fun `latest is selected by date then revision time and identity without adding documents`() {
        val a = DeclaredPositionPayloadReader.read(plan(entry("ASSET", "SAR", "100")))!!
        val b = a.copy(id = "b", updatedAtEpochMillis = 2L, netByCurrency = mapOf("SAR" to "200"))
        val old = a.copy(id = "old", asOfDate = "2026-10-23", updatedAtEpochMillis = 9999L)
        val foreign = a.copy(id = "foreign", asOfDate = "2026-10-25", netByCurrency = mapOf("USD" to "999999"))
        assertThat(latestDeclaredPosition(listOf(old, a, foreign, b), "SAR")).isEqualTo(b)
        assertThat(latestDeclaredPosition(listOf(a, b), "KWD")).isNull()
        assertThat(latestDeclaredPosition(listOf(b, b.copy(id = "z")), "SAR")!!.id).isEqualTo("z")
    }

    private fun entry(side: String, currency: String, amount: String): String =
        """{"id":"entry","label":"Synthetic","side":"$side","currency":"$currency","amount":"$amount","ownerScope":"Free text only"}"""

    private fun plan(entries: String) = SavedFinancialPlan(
        id = "position", kind = SavedFinancialPlanKind.CASH, title = "Synthetic position", currency = "USD",
        payloadVersion = 1, revision = 2L, createdAtEpochMillis = 0L, updatedAtEpochMillis = 1L,
        payloadJson = """{"documentType":"balance_anchor","balanceAnchorVersion":1,"asOfDate":"2026-10-24","entries":[$entries]}""",
    )
}
