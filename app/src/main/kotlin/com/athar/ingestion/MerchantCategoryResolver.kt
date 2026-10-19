package com.athar.ingestion

import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.CategorySuggestion
import com.athar.core.domain.model.MerchantCatalogEntry
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.MerchantCatalogRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A categorization suggestion plus whether a trusted LOCAL rule produced a valid match. */
data class MerchantCategoryResolution(
    val suggestion: CategorySuggestion,
    val hadTrustedLocalRule: Boolean,
)

/**
 * Stateless app-owned coordinator across the full categorization precedence:
 *
 *   explicit personal rules > `auto-local-` exact rules > verified catalog > bundled seeds > UNKNOWN
 *
 * across entire layers (not pattern-type first). Active kind-compatible
 * category validity is checked at match time through [CategoryRepository];
 * missing/archived/incompatible choices are skipped, never created. Within a
 * layer, existing [CategoryRuleRepository.findMatching] ordering is preserved
 * for rules; the catalog layer prefers EXACT, then longer literal, then stable
 * pattern/category order.
 *
 * Financial-status safety lives in [hadTrustedLocalRule]: it is true iff the
 * pre-existing local-rule stack has a VALID match, even when the catalog
 * outranks it — so a catalog correction changes only the suggested category,
 * never legacy posting eligibility. TRANSFER is never classified. The future
 * model (if it ever passes its own quality gate) would extend only the
 * UNKNOWN fallback of this resolver.
 */
@Singleton
class MerchantCategoryResolver @Inject constructor(
    private val rules: CategoryRuleRepository,
    private val categories: CategoryRepository,
    private val catalog: MerchantCatalogRepository,
) {

    suspend fun resolve(merchantNormalized: String, type: TxType): MerchantCategoryResolution {
        if (type == TxType.TRANSFER) {
            return MerchantCategoryResolution(unknownSuggestion(), hadTrustedLocalRule = false)
        }
        val merchant = merchantNormalized.lowercase(Locale.ROOT).trim()
        val catalogMerchant = normalizeCatalogMerchant(merchantNormalized)
        val expectedKind = when (type) {
            TxType.EXPENSE -> CategoryKind.EXPENSE
            TxType.INCOME -> CategoryKind.INCOME
            TxType.TRANSFER -> return MerchantCategoryResolution(unknownSuggestion(), hadTrustedLocalRule = false)
        }
        val matches = rules.findMatching(merchant)

        // Full-layer precedence across the whole stack — NOT pattern-type first:
        // personal > auto-local > catalog > bundled. hadTrustedLocalRule is true
        // iff ANY local layer holds a valid match, even when the catalog's
        // correction outranks a bundled rule (that is what preserves legacy
        // posting eligibility for merchants the bundled seeds already cover).
        val localHits = RuleLayer.entries.mapNotNull { layer ->
            matches.asSequence()
                .filter { layerOf(it) == layer }
                .firstOrNull { isCategoryValid(it.categoryId, expectedKind) }
                ?.let { layer to it }
        }
        val personalOrAuto = localHits.firstOrNull { (layer, _) ->
            layer == RuleLayer.PERSONAL || layer == RuleLayer.AUTO_LOCAL
        }
        if (personalOrAuto != null) {
            return MerchantCategoryResolution(
                suggestion = ruleSuggestion(personalOrAuto.second),
                hadTrustedLocalRule = true,
            )
        }
        val bundledHit = localHits.firstOrNull { it.first == RuleLayer.BUNDLED }
        val hadTrustedLocalRule = bundledHit != null

        // Catalog layer replaces only its own remote layer, ahead of bundled seeds.
        val catalogSnapshot = catalog.snapshot()
        val catalogEntries = catalogSnapshot?.entries.orEmpty()
        if (catalogSnapshot != null && catalogEntries.isNotEmpty()) {
            val catalogVersion = catalogSnapshot.version
            val catalogHit = catalogEntries.asSequence()
                .mapNotNull { entry -> catalogMatch(catalogMerchant, entry)?.let { entry to it } }
                .sortedWith(
                    compareBy(
                        { (entry, _) -> if (entry.patternType == PatternType.EXACT) 0 else 1 },
                        { (_, matched) -> -matched.length },
                        { (entry, _) -> entry.pattern },
                        { (entry, _) -> entry.categoryId },
                    ),
                )
                .firstOrNull { (entry, _) -> isCategoryValid(entry.categoryId, expectedKind) }
            if (catalogHit != null) {
                val (entry, _) = catalogHit
                return MerchantCategoryResolution(
                    suggestion = CategorySuggestion(
                        categoryId = entry.categoryId,
                        confidence = when (entry.patternType) {
                            PatternType.EXACT -> CONFIDENCE_EXACT
                            else -> CONFIDENCE_SUBSTRING
                        },
                        source = CategorySource.CATALOG,
                        ruleId = "catalog:$catalogVersion:${entry.patternType.name}:${entry.pattern}",
                    ),
                    hadTrustedLocalRule = hadTrustedLocalRule,
                )
            }
        }

        if (bundledHit != null) {
            return MerchantCategoryResolution(
                suggestion = ruleSuggestion(bundledHit.second),
                hadTrustedLocalRule = true,
            )
        }

        return MerchantCategoryResolution(unknownSuggestion(), hadTrustedLocalRule = false)
    }

    private suspend fun isCategoryValid(categoryId: String, expectedKind: CategoryKind): Boolean {
        val category = categories.get(categoryId) ?: return false
        if (category.archived) return false
        return category.kind == expectedKind
    }

    private fun ruleSuggestion(rule: CategoryRule): CategorySuggestion {
        val source = when (rule.patternType) {
            PatternType.EXACT -> CategorySource.RULE_EXACT
            PatternType.SUBSTRING -> CategorySource.RULE_SUBSTRING
            PatternType.REGEX -> CategorySource.RULE_REGEX
        }
        val confidence = when (rule.patternType) {
            PatternType.EXACT -> CONFIDENCE_EXACT
            PatternType.SUBSTRING -> CONFIDENCE_SUBSTRING
            PatternType.REGEX -> CONFIDENCE_REGEX
        }
        return CategorySuggestion(rule.categoryId, confidence, source, rule.id)
    }

    private fun unknownSuggestion(): CategorySuggestion =
        CategorySuggestion(null, 0f, CategorySource.UNKNOWN, null)

    private enum class RuleLayer { PERSONAL, AUTO_LOCAL, BUNDLED }

    private fun layerOf(rule: CategoryRule): RuleLayer = when {
        rule.learnedFromUser -> RuleLayer.PERSONAL
        rule.id.startsWith(AUTO_LOCAL_PREFIX) && rule.patternType == PatternType.EXACT -> RuleLayer.AUTO_LOCAL
        else -> RuleLayer.BUNDLED
    }

    companion object {
        private const val AUTO_LOCAL_PREFIX = "auto-local-"
        private const val CONFIDENCE_EXACT = 1.0f
        private const val CONFIDENCE_SUBSTRING = 0.85f
        private const val CONFIDENCE_REGEX = 0.80f

        /**
         * Catalog SUBSTRING is a scoped literal: both Latin/digit word edges
         * must be non-word characters (Arabic adjacency never blocks). So the
         * catalog pattern "lulu" does not match "lululemon", and "stc" does
         * not match "mystcother".
         */
        internal fun normalizeCatalogMerchant(raw: String): String =
            raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        internal fun catalogMatch(merchant: String, entry: MerchantCatalogEntry): String? {
            if (entry.patternType == PatternType.EXACT) {
                return if (merchant == entry.pattern) entry.pattern else null
            }
            if (entry.patternType != PatternType.SUBSTRING) return null
            var index = merchant.indexOf(entry.pattern)
            while (index >= 0) {
                val before = merchant.getOrNull(index - 1)
                val after = merchant.getOrNull(index + entry.pattern.length)
                if (!isLatinWordChar(before) && !isLatinWordChar(after)) return entry.pattern
                index = merchant.indexOf(entry.pattern, index + 1)
            }
            return null
        }

        private fun isLatinWordChar(char: Char?): Boolean =
            char != null && (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9')
    }
}
