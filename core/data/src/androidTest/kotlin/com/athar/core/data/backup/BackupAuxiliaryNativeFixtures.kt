package com.athar.core.data.backup

import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.entity.ActivityLogEntity
import com.athar.core.data.db.entity.UserTemplateEntity
import com.athar.core.data.db.entity.RecurringRuleEntity
import com.athar.core.data.db.entity.WishlistEntity
import com.athar.core.data.planning.SavedFinancialPlanEntity
import kotlinx.datetime.Instant

/** Synthetic-only fixtures and independent raw-field preservation evidence. */
internal object BackupAuxiliaryNativeFixtures {
    suspend fun seed(
        db: AtharDatabase,
        suffix: String,
        accountRef: String,
        categoryRef: String,
        activityCount: Int = 1,
    ) {
        repeat(activityCount) { index ->
            db.activityLogDao().insert(ActivityLogEntity(
                "aux-log-$suffix-$index", Instant.fromEpochMilliseconds(index.toLong()),
                "CREATE", "TRANSACTION", "entity-$suffix-$index", " raw-$suffix-$index\r\n001.2300\t ",
            ))
        }
        db.userTemplateDao().upsert(UserTemplateEntity(
            id = "aux-template-$suffix", displayName = "Template $suffix", sender = " BANK-$suffix ",
            txType = "EXPENSE", amountAnchorBefore = " amount ", amountAnchorAfter = null,
            merchantAnchorBefore = "", merchantAnchorAfter = "after\r\n",
            counterpartyAnchorBefore = null, counterpartyAnchorAfter = "end",
            sampleBody = " \r\nشراء 001.2300 $suffix\t ", createdAt = 17L,
        ))
        db.recurringRuleDao().upsert(RecurringRuleEntity(
            id = "aux-rule-$suffix", displayName = "Rule $suffix", merchant = " Raw $suffix ",
            amountMinor = 125L, amountCurrency = "SAR", type = "EXPENSE", accountId = accountRef,
            categoryId = categoryRef, cadence = "MONTHLY", dayOfMonth = 31, dayOfWeek = null,
            monthOfYear = null, nextRunDate = "2027-01-31", lastRunDate = "2026-12-31",
            isActive = false, notes = "", createdAt = 18L, updatedAt = 19L,
        ))
        db.wishlistDao().upsert(WishlistEntity(
            "aux-wish-$suffix", "Wish $suffix", 100500L, 123L, "SAR", 12, 2026, 1, "raw note $suffix",
        ))
        db.savedFinancialPlanDao().insert(SavedFinancialPlanEntity(
            id = "aux-plan-$suffix", kind = "FUTURE_KIND", title = " Plan $suffix\r\n ", currency = "KWD",
            payloadVersion = 4,
            payloadJson = " \r\n{\"goal\":{\"target\":\"001.2300\",\"opaque\":\"1E+3\"},\"currency\":\"KWD\"}\t ",
            revision = 7L, createdAtEpochMillis = 20L, updatedAtEpochMillis = 21L,
        ))
    }

    suspend fun evidence(db: AtharDatabase): Map<String, List<List<Any?>>> = mapOf(
        "activityLog" to db.activityLogDao().all().sortedBy { it.id }.map {
            listOf(it.id, it.timestamp, it.action, it.entityType, it.entityId, it.summary)
        },
        "userTemplates" to db.userTemplateDao().all().sortedBy { it.id }.map {
            listOf(it.id, it.displayName, it.sender, it.txType, it.amountAnchorBefore, it.amountAnchorAfter, it.merchantAnchorBefore, it.merchantAnchorAfter, it.counterpartyAnchorBefore, it.counterpartyAnchorAfter, it.sampleBody, it.createdAt)
        },
        "recurringRules" to db.recurringRuleDao().all().sortedBy { it.id }.map {
            listOf(it.id, it.displayName, it.merchant, it.amountMinor, it.amountCurrency, it.type, it.accountId, it.categoryId, it.cadence, it.dayOfMonth, it.dayOfWeek, it.monthOfYear, it.nextRunDate, it.lastRunDate, it.isActive, it.notes, it.createdAt, it.updatedAt)
        },
        "savedPlans" to db.savedFinancialPlanDao().all().sortedBy { it.id }.map {
            listOf(it.id, it.kind, it.title, it.currency, it.payloadVersion, it.payloadJson, it.revision, it.createdAtEpochMillis, it.updatedAtEpochMillis)
        },
    )
}
