package com.athar.feature.widgets

import android.content.Context
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.calc.selectOperatingTransactions
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.repo.AccountRepository
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
 * Each snapshot suspend fun does one Flow.first() per source, so the call is
 * cheap and bounded. Widgets re-invoke on each Glance composition (which the
 * system retriggers on its own update cadence + when the app calls updateAll).
 */
object WidgetDataLoader {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDataEntryPoint {
        fun transactions(): TransactionRepository
        fun accounts(): AccountRepository
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
        val operating = selectOperatingTransactions(confirmed)
        val incomeSum = Money.sumAmounts(operating.income.map { it.amount }, currency)
        val expenseSum = Money.sumAmounts(operating.expenses.map { it.amount }, currency)
        val netWorth: NetWorth = e.accounts().observeNetWorth(currency).first()
        return MonthlySnapshot(
            yearMonth = "${month.year}/${month.monthValue}",
            netFlow = incomeSum - expenseSum,
            income = incomeSum,
            expense = expenseSum,
            netWorth = netWorth.total,
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
        val todayOperating = selectOperatingTransactions(todayTxns)
        val todayNet = Money.sumAmounts(todayOperating.income.map { it.amount }, currency) -
            Money.sumAmounts(todayOperating.expenses.map { it.amount }, currency)
        val pending = e.transactions().observePending().first()
        return TodaySnapshot(
            todayNet = todayNet,
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

    data class MonthlySnapshot(
        val yearMonth: String,
        val netFlow: Money,
        val income: Money,
        val expense: Money,
        val netWorth: Money,
        val localeTag: String,
    )

    data class TodaySnapshot(
        val todayNet: Money,
        val pendingCount: Int,
        val localeTag: String,
    )

    data class PendingSnapshot(
        val totalCount: Int,
        val head: List<Transaction>,
        val localeTag: String,
    )
}
