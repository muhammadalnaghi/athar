package com.athar.core.data.backup

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Lossless wire transport only; not a certificate of financial admission.
 *
 * v4 captures the complete authoritative schema: the mandatory section carries
 * all auxiliary tables including every saved financial plan. `savedPlans` is
 * required (no default): the complete v4 envelope is the SINGLE published v4
 * format, and a payload omitting any auxiliary table fails closed instead of
 * decoding as silently incomplete. Plan documents stay opaque: `payloadJson`
 * is never parsed, versioned input documents ride byte-exact with literal
 * currencies and original decimal strings.
 */
@Serializable
internal data class BackupAuxiliarySection(
    val activityLog: List<BackupActivityLog>,
    val userTemplates: List<BackupUserTemplate>,
    val recurringRules: List<BackupRecurringRule>,
    val savedPlans: List<BackupSavedFinancialPlan>,
)

@Serializable
internal data class BackupActivityLog(
    val id: String,
    val timestamp: Instant,
    val action: String,
    val entityType: String,
    val entityId: String,
    val summary: String,
)

@Serializable
internal data class BackupUserTemplate(
    val id: String,
    val displayName: String,
    val sender: String,
    val txType: String,
    val amountAnchorBefore: String,
    val amountAnchorAfter: String?,
    val merchantAnchorBefore: String?,
    val merchantAnchorAfter: String?,
    val counterpartyAnchorBefore: String?,
    val counterpartyAnchorAfter: String?,
    val sampleBody: String,
    val createdAt: Long,
)

@Serializable
internal data class BackupRecurringRule(
    val id: String,
    val displayName: String,
    val merchant: String,
    val amountMinor: Long,
    val amountCurrency: String,
    val type: String,
    val accountId: String,
    val categoryId: String?,
    val cadence: String,
    val dayOfMonth: Int?,
    val dayOfWeek: Int?,
    val monthOfYear: Int?,
    val nextRunDate: String,
    val lastRunDate: String?,
    val isActive: Boolean,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class BackupSavedFinancialPlan(
    val id: String,
    val kind: String,
    val title: String,
    val currency: String,
    val payloadVersion: Int,
    val payloadJson: String,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
