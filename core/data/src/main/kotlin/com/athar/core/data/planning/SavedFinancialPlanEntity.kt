package com.athar.core.data.planning

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * WM028 fast-store: one durable user-authored planning input document
 * (goal/debt/cash/portfolio). `payloadJson` is an opaque versioned input
 * document — monetary values stay original decimal strings and `currency`
 * stays a literal; nothing here is a cached solver result or posted money.
 *
 * Identity (`id`), `kind` and `createdAtEpochMillis` are immutable after
 * insert; `revision` is owned by [SavedFinancialPlanRepositoryImpl].
 */
@Entity(tableName = "saved_financial_plan", indices = [Index("kind")])
internal data class SavedFinancialPlanEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val currency: String,
    val payloadVersion: Int,
    val payloadJson: String,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
