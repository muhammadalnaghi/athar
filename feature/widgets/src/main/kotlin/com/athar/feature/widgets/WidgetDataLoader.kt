package com.athar.feature.widgets

import android.content.Context
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.repo.DeclaredPositionRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.YearMonth

/**
 * Hilt EntryPoint that exposes the data-layer repositories to Glance widgets,
 * which can't use `@Inject` because they have no lifecycle owner.
 *
 * Usage:
 *   val snap = WidgetDataLoader.loadTodaySnapshot(context)
 *
 * Each snapshot is a bounded encrypted-repository read. Renderers key their loader
 * on WidgetRefreshSignal so updateAll also reloads a still-live Glance composition;
 * an unkeyed one-shot producer would otherwise retain its earlier snapshot.
 */
object WidgetDataLoader {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDataEntryPoint {
        fun transactions(): TransactionRepository
        fun positions(): DeclaredPositionRepository
        fun prefs(): UserPreferencesRepository
        fun clock(): Clock
    }

    private fun entry(context: Context): WidgetDataEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetDataEntryPoint::class.java,
        )

    // ---------- Public snapshot loaders ----------

    suspend fun loadMonthlySnapshot(context: Context): MonthlySnapshot {
        val e = entry(context)
        val currency = e.prefs().displayCurrency().first()
        val now = e.clock().now().toLocalDateTime(TimeZone.currentSystemDefault())
        val month = YearMonth.of(now.year, now.monthNumber)
        val period = Period.Month(month)
        val confirmed = e.transactions()
            .observeByPeriod(period, status = TxStatus.CONFIRMED).first()
        // Shared operating selection: transfers are money movement, not consumption;
        // reconciliations only move net worth and never count as income/expense.
        val flow = widgetFlow(confirmed, currency)
        val position = latestDeclaredPositionView(e.positions().observeSnapshots().first(), currency)
        return MonthlySnapshot(
            yearMonth = "${month.year}/${month.monthValue}",
            netFlow = flow.net,
            income = flow.income,
            expense = flow.expense,
            declaredPosition = position,
            localeTag = e.prefs().appLocale().first(),
        )
    }

    suspend fun loadTodaySnapshot(context: Context): TodaySnapshot {
        val e = entry(context)
        val currency = e.prefs().displayCurrency().first()
        val today = e.clock().now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val month = YearMonth.of(today.year, today.monthNumber)
        val period = Period.Month(month)
        val confirmed = e.transactions()
            .observeByPeriod(period, status = TxStatus.CONFIRMED).first()
        val todayTxns = confirmed
            .filter { it.date == today }
        val flow = widgetFlow(todayTxns, currency)
        val pending = e.transactions().observePending().first()
        return TodaySnapshot(
            todayNet = flow.net,
            declaredPosition = latestDeclaredPositionView(e.positions().observeSnapshots().first(), currency),
            pendingCount = pending.size,
            localeTag = e.prefs().appLocale().first(),
        )
    }

    suspend fun loadPendingHead(context: Context, max: Int = 3): PendingSnapshot {
        val e = entry(context)
        val pending: List<Transaction> = e.transactions().observePending().first()
        return PendingSnapshot(
            totalCount = pending.size,
            head = pending.take(max),
            localeTag = e.prefs().appLocale().first(),
        )
    }

    // ---------- Snapshot DTOs ----------

    data class DeclaredPositionView(val asOfDate: String, val net: Money, val revision: Long)

    data class MonthlySnapshot(
        val yearMonth: String,
        val netFlow: Money,
        val income: Money,
        val expense: Money,
        val declaredPosition: DeclaredPositionView?,
        val localeTag: String,
    )

    data class TodaySnapshot(
        val todayNet: Money,
        val declaredPosition: DeclaredPositionView?,
        val pendingCount: Int,
        val localeTag: String,
    )

    data class PendingSnapshot(
        val totalCount: Int,
        val head: List<Transaction>,
        val localeTag: String,
    )
}
