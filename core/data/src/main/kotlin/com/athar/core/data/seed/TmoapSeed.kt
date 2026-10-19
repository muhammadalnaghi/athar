package com.athar.core.data.seed

import android.content.Context
import com.athar.core.data.db.dao.CategoryDao
import com.athar.core.data.db.dao.InvestmentDao
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.dao.WishlistDao
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.InvestmentContributionEntity
import com.athar.core.data.db.entity.InvestmentPoolEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.WishlistEntity
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import timber.log.Timber
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

/**
 * Imports the personal TMOAP workbook into the database on first launch of the
 * `-Pathar.seed=true` build. The CSVs live in `app/src/seeded/assets/seed/`
 * (gitignored, never published) and are picked up from the APK's assets at runtime.
 *
 * Strategy: only runs when the transactions table is empty AND the seeded asset is
 * present. Idempotent — the empty-table guard prevents re-importing after the user
 * has added their own data. The seeded source set is only included when the Gradle
 * property `athar.seed=true` is set, so public builds have neither the CSVs nor the
 * intent to import.
 */
internal class TmoapSeed @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val wishlistDao: WishlistDao,
    private val investmentDao: InvestmentDao,
    private val clock: Clock,
) {

    suspend fun seedIfRequested(enabled: Boolean) {
        if (!enabled) return
        if (!hasSeededAssets()) {
            Timber.d("TmoapSeed: no seeded assets in APK — public build, skipping.")
            return
        }
        if (transactionDao.firstId() != null) {
            Timber.d("TmoapSeed: transactions table not empty — skipping.")
            return
        }
        runCatching {
            val categories = categoryDao.all().associateBy { normalize(it.name) }
            seedBudgetTargets(categories)
            seedTransactions(categories)
            seedWishlist()
            seedInvestments()
            Timber.i("TmoapSeed: imported TMOAP workbook.")
        }.onFailure { Timber.e(it, "TmoapSeed: import failed") }
    }

    private fun hasSeededAssets(): Boolean = runCatching {
        context.assets.list("seed")?.contains("transactions.csv") == true
    }.getOrElse { false }

    private suspend fun seedBudgetTargets(byName: Map<String, CategoryEntity>) {
        readCsv("seed/budget_targets.csv").forEach { row ->
            val name = row.getOrNull(0)?.trim() ?: return@forEach
            if (name.equals("Total Expenses", ignoreCase = true)) return@forEach
            val amount = row.getOrNull(1)?.toBigDecimalOrNull() ?: return@forEach
            val category = byName[normalize(name)] ?: run {
                Timber.w("TmoapSeed: no category match for budget target '$name'")
                return@forEach
            }
            categoryDao.upsert(category.copy(monthlyTargetMinor = amount.toMinor()))
        }
    }

    private suspend fun seedTransactions(byName: Map<String, CategoryEntity>) {
        val now = clock.now()
        readCsv("seed/transactions.csv").forEachIndexed { index, row ->
            val date = row.getOrNull(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEachIndexed
            val vendor = row.getOrNull(1)?.trim().orEmpty()
            val amount = row.getOrNull(2)?.toBigDecimalOrNull() ?: return@forEachIndexed
            val categoryName = row.getOrNull(3)?.trim().orEmpty()
            val type = row.getOrNull(4)?.trim()?.uppercase()?.takeIf { it in setOf("EXPENSE", "INCOME", "TRANSFER") } ?: "EXPENSE"
            val notes = row.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
            val categoryId = byName[normalize(categoryName)]?.id
            if (categoryId == null && categoryName.isNotEmpty()) {
                Timber.w("TmoapSeed: no category match for tx category '$categoryName'")
            }
            transactionDao.upsert(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = MANUAL_ACCOUNT_ID,
                    type = type,
                    amountMinor = amount.toMinor(),
                    currency = "SAR",
                    date = date,
                    occurredAt = null,
                    merchant = vendor,
                    merchantNormalized = vendor.lowercase().trim(),
                    categoryId = categoryId,
                    notes = notes,
                    source = "IMPORT",
                    sourceRefId = "tmoap:$index:${date}:${amount}",
                    status = "CONFIRMED",
                    confidence = null,
                    createdAt = now,
                    updatedAt = now,
                    categorySource = null,
                ),
            )
        }
    }

    private suspend fun seedWishlist() {
        readCsv("seed/wishlist.csv").forEach { row ->
            val name = row.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val cost = row.getOrNull(1)?.toBigDecimalOrNull() ?: return@forEach
            val saved = row.getOrNull(2)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val startDate = row.getOrNull(3)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            wishlistDao.upsert(
                WishlistEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    costMinor = cost.toMinor(),
                    currentSavedMinor = saved.toMinor(),
                    currency = "SAR",
                    desiredMonths = null,
                    startYear = startDate?.year ?: 2026,
                    startMonth = startDate?.monthNumber ?: 1,
                    notes = null,
                ),
            )
        }
    }

    private suspend fun seedInvestments() {
        // Single pool — "Family Investments" — with each CSV row as a contribution.
        val poolId = UUID.randomUUID().toString()
        var totalReturn = BigDecimal.ZERO
        val contributions = readCsv("seed/investments.csv").mapNotNull { row ->
            val owner = row.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val amount = row.getOrNull(1)?.toBigDecimalOrNull() ?: return@mapNotNull null
            val ret = row.getOrNull(2)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            totalReturn = totalReturn.add(ret)
            InvestmentContributionEntity(
                id = UUID.randomUUID().toString(),
                poolId = poolId,
                ownerName = owner,
                amountMinor = amount.toMinor(),
                currency = "SAR",
            )
        }
        if (contributions.isEmpty()) return
        investmentDao.upsertPool(
            InvestmentPoolEntity(
                id = poolId,
                name = "استثمارات العائلة",
                period = "3 أشهر",
                totalReturnMinor = totalReturn.toMinor(),
                currency = "SAR",
            ),
        )
        contributions.forEach { investmentDao.upsertContribution(it) }
    }

    private fun readCsv(asset: String): List<List<String>> = try {
        context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1) // header
                .filter { it.isNotBlank() }
                .map { line -> line.split(',').map { it.trim() } }
                .toList()
        }
    } catch (e: IOException) {
        Timber.w(e, "TmoapSeed: missing asset $asset")
        emptyList()
    }

    private fun normalize(s: String) = s.lowercase().trim()

    private fun BigDecimal.toMinor(): Long = movePointRight(2).toLong()
}
