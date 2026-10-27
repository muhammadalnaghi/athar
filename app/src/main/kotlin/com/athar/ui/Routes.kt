package com.athar.ui

import kotlinx.serialization.Serializable

/**
 * Type-safe Compose Navigation routes (since Navigation 2.8 supports kotlinx.serialization keys).
 * Three tabs total; deep links land in the future under each route's serializer.
 */
object Routes {
    @Serializable data object Onboarding
    @Serializable data object Today
    @Serializable data object Trends
    @Serializable data object Plan
    @Serializable data object Settings
    @Serializable data object Categories
    @Serializable data object SmsAudit
    @Serializable data object ActivityLog
    @Serializable data object UserTemplates
    @Serializable data object RecurringRules
    @Serializable data object Accounts
    @Serializable data object IncomeSources
    @Serializable data object SmsImport
    @Serializable data object History
    @Serializable data object HistoryRepeatedBacklog
}
