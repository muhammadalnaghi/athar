package com.athar.core.data.rules

import com.athar.core.data.db.dao.CategoryRuleDao
import com.athar.core.data.db.dao.CategoryUsageCount
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.isSpecificMerchantKey
import com.athar.core.domain.model.isReconciliation
import kotlinx.datetime.Clock
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grows the user's local categorization library from repeated, unambiguous history.
 *
 * The explicit "Always categorize X" flow still creates priority-200 user rules.
 * This learner is lower priority and exact-match only: after the same normalized
 * merchant is confirmed in the same category at least three times, future ingests
 * can auto-categorize that exact merchant without another prompt.
 */
@Singleton
internal class LocalCategoryRuleLearner @Inject constructor(
    private val ruleDao: CategoryRuleDao,
    private val transactionDao: TransactionDao,
    private val clock: Clock,
) {

    suspend fun maybeLearnFrom(transaction: Transaction): CategoryRuleEntity? {
        val candidate = LearningCandidate.from(transaction) ?: return null
        val evidence = transactionDao.confirmedCategoryCountsForMerchant(candidate.merchantNormalized)
        val existingRules = ruleDao.all()
        val decision = LocalCategoryRuleLearningPolicy.decide(
            candidate = candidate,
            evidence = evidence,
            matchingRules = existingRules.filter { it.matches(candidate.merchantNormalized) },
        )
        return when (decision) {
            LocalCategoryRuleLearningPolicy.Decision.Learn -> upsertAutoRule(candidate)
            LocalCategoryRuleLearningPolicy.Decision.RemoveAutoRule -> {
                ruleDao.deleteAutoLearnedForPattern(candidate.merchantNormalized, PatternType.EXACT.name)
                null
            }
            LocalCategoryRuleLearningPolicy.Decision.Skip -> null
        }
    }

    private suspend fun upsertAutoRule(candidate: LearningCandidate): CategoryRuleEntity {
        val rule = CategoryRuleEntity(
            id = autoRuleId(candidate.merchantNormalized),
            pattern = candidate.merchantNormalized,
            patternType = PatternType.EXACT.name,
            categoryId = candidate.categoryId,
            priority = AutoLearnedPriority,
            learnedFromUser = false,
            createdAt = clock.now(),
        )
        ruleDao.upsert(rule)
        Timber.i(
            "Auto-learned exact category rule for merchant '%s' -> %s",
            candidate.merchantNormalized,
            candidate.categoryId,
        )
        return rule
    }

    private fun autoRuleId(merchantNormalized: String): String =
        AutoRuleIdPrefix + UUID.nameUUIDFromBytes(merchantNormalized.toByteArray(Charsets.UTF_8))

    internal companion object {
        const val AutoRuleIdPrefix = "auto-local-"
        const val AutoLearnedPriority = 150
    }
}

internal data class LearningCandidate(
    val merchantNormalized: String,
    val categoryId: String,
) {
    companion object {
        fun from(transaction: Transaction): LearningCandidate? {
            val categoryId = transaction.categoryId?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            val merchant = transaction.merchantNormalized.lowercase().trim()
            if (transaction.status != TxStatus.CONFIRMED) return null
            if (transaction.type == TxType.TRANSFER) return null
            if (transaction.isReconciliation()) return null
            if (transaction.categorySource == CategorySource.CLASSIFIER) return null
            if (transaction.categorySource == CategorySource.CATALOG) return null
            if (!merchant.isSpecificMerchantKey()) return null
            return LearningCandidate(merchantNormalized = merchant, categoryId = categoryId)
        }
    }
}

internal object LocalCategoryRuleLearningPolicy {
    private const val MinimumConsistentConfirmations = 3

    enum class Decision { Learn, RemoveAutoRule, Skip }

    fun decide(
        candidate: LearningCandidate,
        evidence: List<CategoryUsageCount>,
        matchingRules: List<CategoryRuleEntity>,
    ): Decision {
        val autoRules = matchingRules.filter { it.isAutoLocalRuleFor(candidate.merchantNormalized) }
        val explicitRules = matchingRules.filter { it.learnedFromUser && it.priority >= 200 }
        if (explicitRules.isNotEmpty()) return Decision.Skip

        val categoryEvidence = evidence.filter { it.categoryId.isNotBlank() && it.count > 0 }
        val winning = categoryEvidence.singleOrNull()
        if (
            winning == null ||
            winning.categoryId != candidate.categoryId ||
            winning.count < MinimumConsistentConfirmations
        ) {
            return if (autoRules.isNotEmpty()) Decision.RemoveAutoRule else Decision.Skip
        }

        if (autoRules.any { it.categoryId == candidate.categoryId }) return Decision.Skip
        if (matchingRules.any { it.categoryId == candidate.categoryId && !it.isAutoLocalRuleFor(candidate.merchantNormalized) }) {
            return Decision.Skip
        }
        return Decision.Learn
    }
}

private fun CategoryRuleEntity.isAutoLocalRuleFor(merchantNormalized: String): Boolean =
    id.startsWith(LocalCategoryRuleLearner.AutoRuleIdPrefix) &&
        patternType == PatternType.EXACT.name &&
        pattern.equals(merchantNormalized, ignoreCase = true)

private fun CategoryRuleEntity.matches(merchantNormalized: String): Boolean =
    when (patternType) {
        PatternType.EXACT.name -> pattern.equals(merchantNormalized, ignoreCase = true)
        PatternType.SUBSTRING.name -> merchantNormalized.contains(pattern.lowercase().trim())
        PatternType.REGEX.name -> runCatching { Regex(pattern).containsMatchIn(merchantNormalized) }
            .getOrDefault(false)
        else -> false
    }
