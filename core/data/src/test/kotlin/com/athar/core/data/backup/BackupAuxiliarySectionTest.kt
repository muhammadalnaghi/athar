package com.athar.core.data.backup

import com.athar.core.data.db.entity.ActivityLogEntity
import com.athar.core.data.db.entity.RecurringRuleEntity
import com.athar.core.data.db.entity.UserTemplateEntity
import com.athar.core.data.planning.SavedFinancialPlanEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Transport exactness only: unknown strings and signed endpoints are not admission certificates. */
class BackupAuxiliarySectionTest {
    private val json = Json { encodeDefaults = true }
    private val log = ActivityLogEntity(
        "log-1", Instant.parse("2026-06-13T01:02:03.123456789Z"), "FUTURE_ACTION",
        "FUTURE_ENTITY", "opaque-1", " \r\n001.2300 SAR\t ",
    )
    private val template = UserTemplateEntity(
        id = "template-1", displayName = "Synthetic template", sender = " BANK ",
        txType = "FUTURE_TYPE", amountAnchorBefore = " before ", amountAnchorAfter = null,
        merchantAnchorBefore = "", merchantAnchorAfter = "after\r\n",
        counterpartyAnchorBefore = "name", counterpartyAnchorAfter = null,
        sampleBody = " \r\nشراء 001.2300\t ", createdAt = Long.MAX_VALUE,
    )
    private val rule = RecurringRuleEntity(
        id = "rule-1", displayName = "Synthetic rule", merchant = " Raw Merchant ",
        amountMinor = Long.MIN_VALUE, amountCurrency = "KWD", type = "FUTURE_TYPE",
        accountId = "account-1", categoryId = null, cadence = "FUTURE_CADENCE",
        dayOfMonth = 31, dayOfWeek = null, monthOfYear = 12,
        nextRunDate = "2027-01-31", lastRunDate = null, isActive = false, notes = "",
        createdAt = Long.MIN_VALUE, updatedAt = Long.MAX_VALUE,
    )
    private val plan = SavedFinancialPlanEntity(
        id = "plan-1", kind = "FUTURE_KIND", title = " Synthetic plan\r\n ", currency = "KWD",
        payloadVersion = Int.MAX_VALUE,
        payloadJson = " \r\n{\"goal\":\"001.2300\",\"opaque\":\"1E+3\\ttab\"}\t ",
        revision = Long.MIN_VALUE, createdAtEpochMillis = Long.MIN_VALUE,
        updatedAtEpochMillis = Long.MAX_VALUE,
    )

    @Test
    fun `activity transport preserves every field and timestamp precision`() {
        assertThat(log.toBackup().toEntity()).isEqualTo(log)
    }

    @Test
    fun `template transport preserves null blank and opaque body separately`() {
        val decoded = template.toBackup().toEntity()
        assertThat(decoded).isEqualTo(template)
        assertThat(decoded.amountAnchorAfter).isNull()
        assertThat(decoded.merchantAnchorBefore).isEmpty()
        assertThat(decoded.sampleBody.toByteArray()).isEqualTo(template.sampleBody.toByteArray())
    }

    @Test
    fun `recurring transport preserves signed endpoints and does not coerce enums`() {
        assertThat(rule.toBackup().toEntity()).isEqualTo(rule)
    }

    @Test
    fun `complete auxiliary section round trips through the real serializer`() {
        val section = BackupAuxiliarySection(
            listOf(log.toBackup()), listOf(template.toBackup()), listOf(rule.toBackup()),
            savedPlans = listOf(plan.toBackup()),
        )
        val decoded = json.decodeFromString(
            BackupAuxiliarySection.serializer(),
            json.encodeToString(BackupAuxiliarySection.serializer(), section),
        )
        assertThat(decoded).isEqualTo(section)
        assertThat(decoded.activityLog.single().toEntity()).isEqualTo(log)
        assertThat(decoded.userTemplates.single().toEntity()).isEqualTo(template)
        assertThat(decoded.recurringRules.single().toEntity()).isEqualTo(rule)
        assertThat(decoded.savedPlans.single().toEntity()).isEqualTo(plan)
    }

    @Test
    fun `saved plan transport keeps the document opaque and never coerces money`() {
        val decoded = plan.toBackup().toEntity()
        assertThat(decoded).isEqualTo(plan)
        assertThat(decoded.payloadJson.toByteArray()).isEqualTo(plan.payloadJson.toByteArray())
        assertThat(decoded.payloadJson).contains("001.2300")
        assertThat(decoded.currency).isEqualTo("KWD")
        assertThat(decoded.kind).isEqualTo("FUTURE_KIND")
    }

    @Test
    fun `savedPlans list is required even when every other list is empty`() {
        listOf(
            """{"activityLog":[],"userTemplates":[],"recurringRules":[]}""",
        ).forEach { incomplete ->
            assertThrows<SerializationException> {
                json.decodeFromString(BackupAuxiliarySection.serializer(), incomplete)
            }
        }
    }

    @Test
    fun `all three table lists are required even when other lists are empty`() {
        listOf(
            """{"userTemplates":[],"recurringRules":[]}""",
            """{"activityLog":[],"recurringRules":[]}""",
            """{"activityLog":[],"userTemplates":[]}""",
        ).forEach { incomplete ->
            assertThrows<SerializationException> {
                json.decodeFromString(BackupAuxiliarySection.serializer(), incomplete)
            }
        }
    }

    @Test
    fun `explicit empty lists and changed nullable fields remain exact`() {
        val empty = BackupAuxiliarySection(emptyList(), emptyList(), emptyList(), savedPlans = emptyList())
        assertThat(json.decodeFromString(BackupAuxiliarySection.serializer(),
            json.encodeToString(BackupAuxiliarySection.serializer(), empty))).isEqualTo(empty)
        val edited = template.toBackup().copy(amountAnchorAfter = "", merchantAnchorBefore = null)
        assertThat(edited.toEntity()).isEqualTo(template.copy(amountAnchorAfter = "", merchantAnchorBefore = null))
        val changedRule = rule.toBackup().copy(amountMinor = 1L, isActive = true)
        assertThat(changedRule.toEntity()).isEqualTo(rule.copy(amountMinor = 1L, isActive = true))
    }
}
