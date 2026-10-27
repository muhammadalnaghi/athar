package com.athar.core.data.backup

import com.athar.core.data.ledger.backup.CanonicalBackupSection
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackupAuxiliaryValidationTest {
    private val at = Instant.parse("2026-06-13T00:00:00Z")
    private val empty = BackupAuxiliarySection(emptyList(), emptyList(), emptyList(), savedPlans = emptyList())
    private val canonical = CanonicalBackupSection(
        events = emptyList(), observations = emptyList(), associations = emptyList(),
        components = emptyList(), legs = emptyList(), revisions = emptyList(),
        snapshots = emptyList(), conflicts = emptyList(),
    )
    private val rule = BackupRecurringRule(
        id = "r", displayName = "Rule", merchant = "Raw", amountMinor = 125L, amountCurrency = "SAR",
        type = "EXPENSE", accountId = "a", categoryId = "c", cadence = "MONTHLY",
        dayOfMonth = 31, dayOfWeek = null, monthOfYear = null, nextRunDate = "2027-01-31",
        lastRunDate = null, isActive = false, notes = null, createdAt = 1L, updatedAt = 2L,
    )
    private val plan = BackupSavedFinancialPlan(
        id = "p", kind = "FUTURE_KIND", title = "Plan", currency = "KWD", payloadVersion = 7,
        payloadJson = " \r\n{\"opaque\":\"001.2300\"}\t ", revision = 41L,
        createdAtEpochMillis = 100L, updatedAtEpochMillis = 200L,
    )
    private fun snapshot(version: Int = 4): BackupSnapshot = BackupSnapshot(
        version = version, exportedAt = at,
        accounts = listOf(BackupAccount("a", "Cash", "CASH", "SAR", "", true, at)),
        categories = listOf(BackupCategory("c", "Food", "طعام", "EXPENSE", null, null, "SAR", false, 0)),
        transactions = emptyList(), categoryRules = emptyList(), wishlist = emptyList(),
        investmentPools = emptyList(), investmentContributions = emptyList(), smsAudit = emptyList(),
        canonicalLedger = if (version == 4) canonical else null,
        auxiliaryTables = if (version == 4) empty else null,
    )

    @Test
    fun `v4 cannot silently omit the auxiliary section`() {
        val error = assertThrows<BackupValidationException> {
            BackupRestoreValidator.validate(snapshot().copy(auxiliaryTables = null), emptyList(), 0L, false)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.V4_REQUIRES_AUXILIARY_TABLES)
    }

    @Test
    fun `legacy payloads cannot pretend to carry complete auxiliary tables`() {
        (1..3).forEach { version ->
            val error = assertThrows<BackupValidationException> {
                BackupRestoreValidator.validate(snapshot(version).copy(auxiliaryTables = empty), emptyList(), 0L, false)
            }
            assertThat(error.code).isEqualTo(BackupValidationCode.LEGACY_CANNOT_CARRY_AUXILIARY_TABLES)
        }
    }

    @Test
    fun `legacy replacement refuses live auxiliary data even without canonical rows`() {
        (1..3).forEach { version ->
            val error = assertThrows<BackupValidationException> {
                BackupRestoreValidator.validate(snapshot(version), emptyList(), 0L, true)
            }
            assertThat(error.code).isEqualTo(BackupValidationCode.AUXILIARY_DATA_PRESENT)
        }
    }

    @Test
    fun `legacy replacement remains possible in an auxiliary and canonical free target`() {
        (1..3).forEach { version ->
            val plan = BackupRestoreValidator.validate(snapshot(version), emptyList(), 0L, false)
            assertThat(plan.canonicalReplaced).isFalse()
            assertThat(plan.snapshot.auxiliaryTables).isNull()
        }
    }

    @Test
    fun `duplicate ids are rejected independently for every auxiliary table`() {
        val log = BackupActivityLog("l", at, "CREATE", "TRANSACTION", "t", "raw")
        val template = BackupUserTemplate("u", "Name", "BANK", "EXPENSE", "amount", null, null, null, null, null, "raw", 1L)
        listOf(
            empty.copy(activityLog = listOf(log, log)),
            empty.copy(userTemplates = listOf(template, template)),
            empty.copy(recurringRules = listOf(rule, rule)),
            empty.copy(savedPlans = listOf(plan, plan)),
        ).forEach { section ->
            val error = assertThrows<BackupValidationException> {
                BackupRestoreValidator.validate(snapshot().copy(auxiliaryTables = section), emptyList(), 0L, false)
            }
            assertThat(error.code).isEqualTo(BackupValidationCode.DUPLICATE_BACKUP_KEY)
        }
    }

    @Test
    fun `saved plan rows carry no account or category reference and stay opaque`() {
        val carrying = snapshot().copy(auxiliaryTables = empty.copy(savedPlans = listOf(plan)))
        assertThat(BackupRestoreValidator.validate(carrying, emptyList(), 0L, false).snapshot)
            .isEqualTo(carrying)
    }

    @Test
    fun `account and category references are checked with all other parents valid`() {
        listOf(rule.copy(accountId = "missing"), rule.copy(categoryId = "missing")).forEach { invalid ->
            val error = assertThrows<BackupValidationException> {
                BackupRestoreValidator.validate(snapshot().copy(
                    auxiliaryTables = empty.copy(recurringRules = listOf(invalid)),
                ), emptyList(), 0L, false)
            }
            assertThat(error.code).isEqualTo(BackupValidationCode.LEGACY_REFERENCE_MISSING)
        }
        val valid = snapshot().copy(auxiliaryTables = empty.copy(recurringRules = listOf(rule)))
        assertThat(BackupRestoreValidator.validate(valid, emptyList(), 0L, false).snapshot).isEqualTo(valid)
    }

    @Test
    fun `constructed unsupported snapshots cannot bypass the literal version gate`() {
        listOf(0, 5).forEach { version ->
            val error = assertThrows<BackupValidationException> {
                BackupRestoreValidator.validate(snapshot(version), emptyList(), 0L, false)
            }
            assertThat(error.code).isEqualTo(BackupValidationCode.UNSUPPORTED_VERSION)
        }
    }
}
