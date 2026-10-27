package com.athar.core.data.csv

import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.specificMerchantKey
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.MerchantBulkImportCategoryImpact
import com.athar.core.domain.repo.MerchantBulkImportResult
import com.athar.core.domain.repo.MerchantBulkImportSkipSummary
import com.athar.core.domain.repo.MerchantBulkImportTrigger
import com.athar.core.domain.repo.TransactionRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import timber.log.Timber
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a CSV emitted by [MerchantBulkExporter] (or any CSV with `id`/`stable_key` +
 * `category_id` columns), applies the chosen category to each matching transaction
 * (status → CONFIRMED). When one repeated-merchant row is filled and its blank
 * peers are left blank, the same category propagates to those peers inside the
 * imported CSV as long as the merchant group has no conflicting filled category.
 * Unambiguous merchant groups also record an exact learned `CategoryRule` so
 * future ingests of the same normalized merchant benefit without creating broad
 * substring matches.
 *
 * The category column may be either a category **id** (`cat-restaurant`) or the English /
 * Arabic display name — for AI-edited files the id is preferred since it's unambiguous.
 */
@Singleton
internal class MerchantBulkImporter @Inject constructor(
    private val transactions: TransactionRepository,
    private val rules: CategoryRuleRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
) : MerchantBulkImportTrigger {

    override suspend fun previewCategorizations(input: InputStream): MerchantBulkImportResult =
        processCategorizations(input, applyChanges = false)

    override suspend fun importCategorizations(input: InputStream): MerchantBulkImportResult =
        processCategorizations(input, applyChanges = true)

    private suspend fun processCategorizations(
        input: InputStream,
        applyChanges: Boolean,
    ): MerchantBulkImportResult {
        val text = runCatching { input.bufferedReader().use { it.readText() } }
            .getOrElse { return MerchantBulkImportResult.Failed("Couldn't read CSV: ${it.message}") }
        val rows = parseRows(normalizeImportText(text)).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return MerchantBulkImportResult.Failed("Empty CSV file.")

        val header = rows[0].map { it.lowercase().trim() }
        val idIdx = header.indexOf("id")
        val stableKeyIdx = header.indexOf("stable_key")
        val sourceRefIdx = header.indexOf("source_ref_id")
        val merchantIdx = header.indexOfFirst { it == "merchant" || it == "vendor" }
        val merchantNormIdx = header.indexOf("merchant_normalized")
        val amountIdx = header.indexOf("amount")
        val currencyIdx = header.indexOf("currency")
        val typeIdx = header.indexOf("type")
        val dateIdx = header.indexOf("date")
        val categoryIdx = header.indexOfFirst {
            it == "category_id" || it == "categoryid" || it == "category"
        }
        if (categoryIdx < 0 || (idIdx < 0 && stableKeyIdx < 0)) {
            return MerchantBulkImportResult.Failed(
                "CSV needs a `category_id` (or `category`) column plus either `id` or `stable_key`.",
            )
        }

        val cats = categories.observeAll(kind = null, includeArchived = false).first()
        val byId = cats.associateBy { it.id }
        val byIdLower = cats.associateBy { it.id.lowercase().trim() }
        val byName = cats.groupBy { it.name.lowercase().trim() }
        val byAr = cats.groupBy { it.nameAr.trim() }

        var updated = 0
        var skipped = 0
        var malformedRows = 0
        var unknownCategories = 0
        var incompatibleCategories = 0
        var missingTransactions = 0
        var conflictingGroups = 0
        var blankRowsWithoutGroupChoice = 0
        // Track unique (pattern → categoryId) so we train one exact rule per merchant, not per row.
        val rulesToAdd = mutableMapOf<String, String>()
        val allTransactions = transactions.observeAll().first()
        val byStableKey = uniqueBy(allTransactions, MerchantBulkStableKey::sourceAware)
        val byContentKey = uniqueBy(allTransactions, MerchantBulkStableKey::contentOnly)
        val categoryImpact = linkedMapOf<String, CategoryImpactAccumulator>()

        val dataRows = rows.drop(1).mapIndexed { index, row ->
            CsvDataRow(number = index + 2, cells = row)
        }
        val updatedTransactionIds = mutableSetOf<String>()
        val categoriesByPattern = linkedMapOf<String, MutableSet<String>>()
        val patternsWithIncompatibleTypes = linkedSetOf<String>()

        for (csvRow in dataRows) {
            val row = csvRow.cells
            if (!hasRequiredColumns(row, idIdx, stableKeyIdx, categoryIdx)) {
                skipped++
                malformedRows++
                continue
            }
            val txId = if (idIdx >= 0 && idIdx < row.size) row[idIdx].trim() else ""
            val rawCat = row[categoryIdx].trim()
            if (rawCat.isEmpty()) continue

            val tx = resolveTransaction(
                txId = txId,
                row = row,
                stableKeyIdx = stableKeyIdx,
                sourceRefIdx = sourceRefIdx,
                merchantIdx = merchantIdx,
                merchantNormIdx = merchantNormIdx,
                amountIdx = amountIdx,
                currencyIdx = currencyIdx,
                typeIdx = typeIdx,
                dateIdx = dateIdx,
                byStableKey = byStableKey,
                byContentKey = byContentKey,
            )
            if (tx == null) {
                Timber.w("CSV row %d skipped: no matching transaction for id/stable key", csvRow.number)
                skipped++
                missingTransactions++
                continue
            }

            val pattern = merchantPattern(tx, row, merchantIdx, merchantNormIdx)
            val categoryCandidates = resolveCategoryCandidates(rawCat, byId, byIdLower, byName, byAr)
            if (categoryCandidates.isEmpty()) {
                Timber.w("CSV row %d skipped: unknown category '%s'", csvRow.number, rawCat)
                skipped++
                unknownCategories++
                continue
            }
            val category = categoryCandidates.firstOrNull { it.isCompatibleWith(tx.type) }
            if (category == null) {
                Timber.w(
                    "CSV row %d skipped: category '%s' is incompatible with transaction type %s",
                    csvRow.number,
                    rawCat,
                    tx.type,
                )
                if (pattern.isNotBlank()) patternsWithIncompatibleTypes += pattern
                skipped++
                incompatibleCategories++
                continue
            }

            if (applyCategory(tx, category.id, updatedTransactionIds, applyChanges)) {
                updated++
                categoryImpact.record(category)
            }

            if (pattern.isNotBlank()) {
                categoriesByPattern.getOrPut(pattern) { linkedSetOf() }.add(category.id)
            }
        }

        val unambiguousGroups = categoriesByPattern
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        val conflictingPatterns = categoriesByPattern
            .filterValues { it.size > 1 }
            .keys

        for (csvRow in dataRows) {
            val row = csvRow.cells
            if (!hasRequiredColumns(row, idIdx, stableKeyIdx, categoryIdx)) continue
            if (row[categoryIdx].trim().isNotEmpty()) continue

            val txId = if (idIdx >= 0 && idIdx < row.size) row[idIdx].trim() else ""
            val tx = resolveTransaction(
                txId = txId,
                row = row,
                stableKeyIdx = stableKeyIdx,
                sourceRefIdx = sourceRefIdx,
                merchantIdx = merchantIdx,
                merchantNormIdx = merchantNormIdx,
                amountIdx = amountIdx,
                currencyIdx = currencyIdx,
                typeIdx = typeIdx,
                dateIdx = dateIdx,
                byStableKey = byStableKey,
                byContentKey = byContentKey,
            )
            if (tx == null) {
                Timber.w("CSV row %d skipped: no matching transaction for group propagation", csvRow.number)
                skipped++
                missingTransactions++
                continue
            }

            val pattern = merchantPattern(tx, row, merchantIdx, merchantNormIdx)
            val categoryId = unambiguousGroups[pattern]
            if (categoryId == null) {
                if (pattern in conflictingPatterns) {
                    conflictingGroups++
                } else {
                    blankRowsWithoutGroupChoice++
                }
                skipped++
                continue
            }
            val category = byId[categoryId]
            if (category == null || !category.isCompatibleWith(tx.type)) {
                Timber.w(
                    "CSV row %d skipped: group category '%s' is incompatible with transaction type %s",
                    csvRow.number,
                    categoryId,
                    tx.type,
                )
                if (pattern.isNotBlank()) patternsWithIncompatibleTypes += pattern
                skipped++
                incompatibleCategories++
                continue
            }
            if (applyCategory(tx, categoryId, updatedTransactionIds, applyChanges)) {
                updated++
                categoryImpact.record(category)
            }
        }

        rulesToAdd.putAll(
            unambiguousGroups.filterKeys { it !in patternsWithIncompatibleTypes },
        )

        val existingExactLocalRules = rules.observeAll()
            .first()
            .filter { it.learnedFromUser && it.patternType == PatternType.EXACT }
            .groupBy { it.pattern.lowercase().trim() }

        var rulesAdded = 0
        rulesToAdd.forEach { (pattern, cat) ->
            runCatching {
                val changed = if (applyChanges) {
                    upsertExactLocalRule(pattern, cat, existingExactLocalRules[pattern].orEmpty())
                } else {
                    wouldUpsertExactLocalRule(pattern, cat, existingExactLocalRules[pattern].orEmpty())
                }
                if (changed) {
                    rulesAdded++
                }
            }.onFailure { Timber.w(it, "Failed to add rule for '$pattern'") }
        }

        Timber.i(
            "Merchant bulk %s: updated=%d rules=%d skipped=%d",
            if (applyChanges) "import" else "preview",
            updated,
            rulesAdded,
            skipped,
        )
        return MerchantBulkImportResult.Done(
            updated = updated,
            rulesAdded = rulesAdded,
            skipped = skipped,
            skipSummary = MerchantBulkImportSkipSummary(
                malformedRows = malformedRows,
                unknownCategories = unknownCategories,
                incompatibleCategories = incompatibleCategories,
                missingTransactions = missingTransactions,
                conflictingGroups = conflictingGroups,
                blankRowsWithoutGroupChoice = blankRowsWithoutGroupChoice,
            ),
            categoryImpact = if (applyChanges) emptyList() else categoryImpact.toImpactList(),
        )
    }

    private fun wouldUpsertExactLocalRule(
        pattern: String,
        categoryId: String,
        existingRules: List<CategoryRule>,
    ): Boolean {
        val exactRules = exactLocalRulesFor(pattern, existingRules)
        return exactRules.size != 1 || exactRules.single().categoryId != categoryId
    }

    private suspend fun upsertExactLocalRule(
        pattern: String,
        categoryId: String,
        existingRules: List<CategoryRule>,
    ): Boolean {
        val normalizedPattern = pattern.lowercase().trim()
        val exactRules = exactLocalRulesFor(pattern, existingRules)
        if (exactRules.size == 1 && exactRules.single().categoryId == categoryId) return false

        exactRules.forEach { rules.delete(it.id) }
        rules.learnFromCorrection(
            merchantNormalized = normalizedPattern,
            categoryId = categoryId,
            patternType = PatternType.EXACT,
        )
        return true
    }

    private fun exactLocalRulesFor(
        pattern: String,
        existingRules: List<CategoryRule>,
    ): List<CategoryRule> {
        val normalizedPattern = pattern.lowercase().trim()
        return existingRules.filter {
            it.learnedFromUser &&
                it.patternType == PatternType.EXACT &&
                it.pattern.equals(normalizedPattern, ignoreCase = true)
        }
    }

    private suspend fun applyCategory(
        tx: Transaction,
        categoryId: String,
        updatedTransactionIds: MutableSet<String>,
        applyChanges: Boolean,
    ): Boolean {
        if (!updatedTransactionIds.add(tx.id)) return false
        if (applyChanges) {
            transactions.upsert(
                tx.copy(
                    categoryId = categoryId,
                    // A category-only CSV does not certify or revive a payment.
                    updatedAt = clock.now(),
                ),
            )
        }
        return true
    }

    private fun merchantPattern(
        tx: Transaction,
        row: List<String>,
        merchantIdx: Int,
        merchantNormIdx: Int,
    ): String {
        val rowMerchant = rowMerchantNormalized(row, merchantIdx, merchantNormIdx) ?: tx.merchant
        return specificMerchantKey(
            merchantNormalized = tx.merchantNormalized,
            merchant = rowMerchant,
        ).orEmpty()
    }

    private suspend fun resolveTransaction(
        txId: String,
        row: List<String>,
        stableKeyIdx: Int,
        sourceRefIdx: Int,
        merchantIdx: Int,
        merchantNormIdx: Int,
        amountIdx: Int,
        currencyIdx: Int,
        typeIdx: Int,
        dateIdx: Int,
        byStableKey: Map<String, Transaction>,
        byContentKey: Map<String, Transaction>,
    ): Transaction? {
        if (txId.isNotEmpty()) {
            transactions.get(txId)?.let { return it }
        }

        val exportedStableKey = row.getOrNull(stableKeyIdx)?.trim().orEmpty()
        if (exportedStableKey.isNotEmpty()) {
            byStableKey[exportedStableKey]?.let { return it }
        }

        val rowSourceAware = rowSourceAwareKey(
            row,
            sourceRefIdx,
            merchantIdx,
            merchantNormIdx,
            amountIdx,
            currencyIdx,
            typeIdx,
            dateIdx,
        )
        rowSourceAware?.let { byStableKey[it] }?.let { return it }

        val rowContentKey = rowContentKey(row, merchantIdx, merchantNormIdx, amountIdx, currencyIdx, typeIdx, dateIdx)
        return rowContentKey?.let { byContentKey[it] }
    }

    private fun rowSourceAwareKey(
        row: List<String>,
        sourceRefIdx: Int,
        merchantIdx: Int,
        merchantNormIdx: Int,
        amountIdx: Int,
        currencyIdx: Int,
        typeIdx: Int,
        dateIdx: Int,
    ): String? {
        val sourceRef = row.getOrNull(sourceRefIdx)?.trim().orEmpty()
        if (sourceRef.isEmpty()) return null
        return MerchantBulkStableKey.sourceAware(
            sourceRefId = sourceRef,
            merchantNormalized = rowMerchantNormalized(row, merchantIdx, merchantNormIdx) ?: return null,
            amount = row.getOrNull(amountIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            currency = row.getOrNull(currencyIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            type = row.getOrNull(typeIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            date = row.getOrNull(dateIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
        )
    }

    private fun rowContentKey(
        row: List<String>,
        merchantIdx: Int,
        merchantNormIdx: Int,
        amountIdx: Int,
        currencyIdx: Int,
        typeIdx: Int,
        dateIdx: Int,
    ): String? {
        return MerchantBulkStableKey.contentOnly(
            merchantNormalized = rowMerchantNormalized(row, merchantIdx, merchantNormIdx) ?: return null,
            amount = row.getOrNull(amountIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            currency = row.getOrNull(currencyIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            type = row.getOrNull(typeIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            date = row.getOrNull(dateIdx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
        )
    }

    private fun rowMerchantNormalized(row: List<String>, merchantIdx: Int, merchantNormIdx: Int): String? {
        val normalized = row.getOrNull(merchantNormIdx)?.lowercase()?.trim().orEmpty()
        if (normalized.isNotEmpty()) return normalized
        return row.getOrNull(merchantIdx)?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun uniqueBy(
        transactions: List<Transaction>,
        keyOf: (Transaction) -> String,
    ): Map<String, Transaction> =
        transactions.groupBy(keyOf)
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

    private fun hasRequiredColumns(row: List<String>, vararg indexes: Int): Boolean =
        row.size > requiredMaxIndex(*indexes)

    private fun requiredMaxIndex(vararg indexes: Int): Int =
        indexes.filter { it >= 0 }.maxOrNull() ?: -1

    private fun resolveCategoryCandidates(
        raw: String,
        byId: Map<String, Category>,
        byIdLower: Map<String, Category>,
        byName: Map<String, List<Category>>,
        byAr: Map<String, List<Category>>,
    ): List<Category> {
        // Prefer id match (cat-restaurant). Fall back to copied option labels or display names.
        categoryInputCandidates(raw).forEach { candidate ->
            byId[candidate]?.let { return listOf(it) }
            byIdLower[candidate.lowercase()]?.let { return listOf(it) }
            byAr[candidate]?.let { return it }
            byName[candidate.lowercase()]?.let { return it }
        }
        return emptyList()
    }

    private fun categoryInputCandidates(raw: String): List<String> {
        val candidates = linkedSetOf<String>()
        fun add(value: String) {
            val cleaned = value.trim().stripCategoryWrappers()
            if (cleaned.isNotEmpty()) candidates += cleaned
        }

        val cleaned = raw.trim().stripCategoryWrappers()
        add(cleaned)
        if ('=' in cleaned) {
            add(cleaned.substringBefore('='))
            cleaned.substringAfter('=').split('/').forEach(::add)
        } else if ('/' in cleaned) {
            cleaned.split('/').forEach(::add)
        }
        return candidates.toList()
    }

    private fun String.stripCategoryWrappers(): String =
        removeSurrounding("`")
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()

    private fun Category.isCompatibleWith(type: TxType): Boolean =
        when (type) {
            TxType.EXPENSE -> kind == CategoryKind.EXPENSE
            TxType.INCOME -> kind == CategoryKind.INCOME
            TxType.TRANSFER -> false
        }

    private data class CsvDataRow(
        val number: Int,
        val cells: List<String>,
    )

    private data class CategoryImpactAccumulator(
        val categoryId: String,
        val categoryName: String,
        val categoryNameAr: String,
        var updated: Int,
    )

    private fun MutableMap<String, CategoryImpactAccumulator>.record(category: Category) {
        val existing = get(category.id)
        if (existing != null) {
            existing.updated += 1
        } else {
            put(
                category.id,
                CategoryImpactAccumulator(
                    categoryId = category.id,
                    categoryName = category.name,
                    categoryNameAr = category.nameAr,
                    updated = 1,
                ),
            )
        }
    }

    private fun Map<String, CategoryImpactAccumulator>.toImpactList(): List<MerchantBulkImportCategoryImpact> =
        values.sortedWith(
            compareByDescending<CategoryImpactAccumulator> { it.updated }
                .thenBy { it.categoryName.lowercase() },
        ).map {
            MerchantBulkImportCategoryImpact(
                categoryId = it.categoryId,
                categoryName = it.categoryName,
                categoryNameAr = it.categoryNameAr,
                updated = it.updated,
            )
        }

    private fun normalizeImportText(text: String): String {
        val trimmed = text.removePrefix("\uFEFF").trim()
        return extractFencedCsv(trimmed) ?: trimmed
    }

    private fun extractFencedCsv(text: String): String? {
        var inFence = false
        var acceptedFence = false
        val candidate = StringBuilder()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!inFence && trimmed.startsWith("```")) {
                val info = trimmed.removePrefix("```").trim().lowercase()
                inFence = true
                acceptedFence = info.isEmpty() || info.startsWith("csv")
                candidate.clear()
            } else if (inFence && trimmed.startsWith("```")) {
                if (acceptedFence) {
                    val csv = candidate.toString().trim()
                    if (looksLikeBulkCsv(csv)) return csv
                }
                inFence = false
                acceptedFence = false
                candidate.clear()
            } else if (inFence && acceptedFence) {
                candidate.appendLine(line)
            }
        }

        if (inFence && acceptedFence) {
            val csv = candidate.toString().trim()
            if (looksLikeBulkCsv(csv)) return csv
        }
        return null
    }

    private fun looksLikeBulkCsv(text: String): Boolean {
        val header = parseRows(text)
            .firstOrNull { row -> row.any { it.isNotBlank() } }
            ?.map { it.lowercase().trim() }
            .orEmpty()
        val hasCategory = header.any { it == "category_id" || it == "categoryid" || it == "category" }
        val hasIdentity = header.any { it == "id" || it == "stable_key" }
        return hasCategory && hasIdentity
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.clear() }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    out.add(cur.toString())
                    rows.add(out.toList())
                    out.clear()
                    cur.clear()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cur.append(c)
            }
            i += 1
        }
        out.add(cur.toString())
        if (out.size > 1 || out.firstOrNull()?.isNotEmpty() == true) rows.add(out.toList())
        return rows
    }
}
