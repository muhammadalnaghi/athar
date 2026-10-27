package com.athar.core.data.backup

import com.athar.core.data.db.entity.ActivityLogEntity
import com.athar.core.data.db.entity.UserTemplateEntity
import com.athar.core.data.db.entity.RecurringRuleEntity
import com.athar.core.data.planning.SavedFinancialPlanEntity

internal fun ActivityLogEntity.toBackup(): BackupActivityLog = BackupActivityLog(
    id = id,
    timestamp = timestamp,
    action = action,
    entityType = entityType,
    entityId = entityId,
    summary = summary,
)

internal fun BackupActivityLog.toEntity(): ActivityLogEntity = ActivityLogEntity(
    id = id,
    timestamp = timestamp,
    action = action,
    entityType = entityType,
    entityId = entityId,
    summary = summary,
)

internal fun UserTemplateEntity.toBackup(): BackupUserTemplate = BackupUserTemplate(
    id = id,
    displayName = displayName,
    sender = sender,
    txType = txType,
    amountAnchorBefore = amountAnchorBefore,
    amountAnchorAfter = amountAnchorAfter,
    merchantAnchorBefore = merchantAnchorBefore,
    merchantAnchorAfter = merchantAnchorAfter,
    counterpartyAnchorBefore = counterpartyAnchorBefore,
    counterpartyAnchorAfter = counterpartyAnchorAfter,
    sampleBody = sampleBody,
    createdAt = createdAt,
)

internal fun BackupUserTemplate.toEntity(): UserTemplateEntity = UserTemplateEntity(
    id = id,
    displayName = displayName,
    sender = sender,
    txType = txType,
    amountAnchorBefore = amountAnchorBefore,
    amountAnchorAfter = amountAnchorAfter,
    merchantAnchorBefore = merchantAnchorBefore,
    merchantAnchorAfter = merchantAnchorAfter,
    counterpartyAnchorBefore = counterpartyAnchorBefore,
    counterpartyAnchorAfter = counterpartyAnchorAfter,
    sampleBody = sampleBody,
    createdAt = createdAt,
)

internal fun RecurringRuleEntity.toBackup(): BackupRecurringRule = BackupRecurringRule(
    id = id,
    displayName = displayName,
    merchant = merchant,
    amountMinor = amountMinor,
    amountCurrency = amountCurrency,
    type = type,
    accountId = accountId,
    categoryId = categoryId,
    cadence = cadence,
    dayOfMonth = dayOfMonth,
    dayOfWeek = dayOfWeek,
    monthOfYear = monthOfYear,
    nextRunDate = nextRunDate,
    lastRunDate = lastRunDate,
    isActive = isActive,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun BackupRecurringRule.toEntity(): RecurringRuleEntity = RecurringRuleEntity(
    id = id,
    displayName = displayName,
    merchant = merchant,
    amountMinor = amountMinor,
    amountCurrency = amountCurrency,
    type = type,
    accountId = accountId,
    categoryId = categoryId,
    cadence = cadence,
    dayOfMonth = dayOfMonth,
    dayOfWeek = dayOfWeek,
    monthOfYear = monthOfYear,
    nextRunDate = nextRunDate,
    lastRunDate = lastRunDate,
    isActive = isActive,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun SavedFinancialPlanEntity.toBackup(): BackupSavedFinancialPlan = BackupSavedFinancialPlan(
    id = id,
    kind = kind,
    title = title,
    currency = currency,
    payloadVersion = payloadVersion,
    payloadJson = payloadJson,
    revision = revision,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun BackupSavedFinancialPlan.toEntity(): SavedFinancialPlanEntity = SavedFinancialPlanEntity(
    id = id,
    kind = kind,
    title = title,
    currency = currency,
    payloadVersion = payloadVersion,
    payloadJson = payloadJson,
    revision = revision,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
