package com.athar.core.data.rules

import com.athar.core.common.money.Money
import com.athar.core.data.db.dao.EngineProvenanceExclusion
import com.athar.core.data.db.dao.CategoryUsageCount
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.RECONCILE_REF_PREFIX
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class LocalCategoryRuleLearnerTest {

    @Test
    fun `learns exact local rule after three consistent confirmations`() {
        val candidate = LearningCandidate("jarir bookstore", "education")
        val decision = LocalCategoryRuleLearningPolicy.decide(
            candidate = candidate,
            evidence = listOf(CategoryUsageCount("education", 3)),
            matchingRules = emptyList(),
        )

        assertThat(decision).isEqualTo(LocalCategoryRuleLearningPolicy.Decision.Learn)
    }

    @Test
    fun `does not learn below confirmation threshold`() {
        val candidate = LearningCandidate("jarir bookstore", "education")
        val decision = LocalCategoryRuleLearningPolicy.decide(
            candidate = candidate,
            evidence = listOf(CategoryUsageCount("education", 2)),
            matchingRules = emptyList(),
        )

        assertThat(decision).isEqualTo(LocalCategoryRuleLearningPolicy.Decision.Skip)
    }

    @Test
    fun `removes existing auto rule when merchant history becomes ambiguous`() {
        val candidate = LearningCandidate("amazon", "shopping")
        val decision = LocalCategoryRuleLearningPolicy.decide(
            candidate = candidate,
            evidence = listOf(
                CategoryUsageCount("shopping", 3),
                CategoryUsageCount("subscriptions", 1),
            ),
            matchingRules = listOf(autoRule("amazon", "shopping")),
        )

        assertThat(decision).isEqualTo(LocalCategoryRuleLearningPolicy.Decision.RemoveAutoRule)
    }

    @Test
    fun `explicit user rules are never overridden`() {
        val candidate = LearningCandidate("amazon", "shopping")
        val decision = LocalCategoryRuleLearningPolicy.decide(
            candidate = candidate,
            evidence = listOf(CategoryUsageCount("shopping", 5)),
            matchingRules = listOf(explicitRule("amazon", "subscriptions")),
        )

        assertThat(decision).isEqualTo(LocalCategoryRuleLearningPolicy.Decision.Skip)
    }

    @Test
    fun `does not duplicate a matching seed rule`() {
        val candidate = LearningCandidate("starbucks", "restaurants")
        val decision = LocalCategoryRuleLearningPolicy.decide(
            candidate = candidate,
            evidence = listOf(CategoryUsageCount("restaurants", 4)),
            matchingRules = listOf(seedRule("starbucks", "restaurants")),
        )

        assertThat(decision).isEqualTo(LocalCategoryRuleLearningPolicy.Decision.Skip)
    }

    @Test
    fun `can learn exact override when broad seed category differs`() {
        val candidate = LearningCandidate("amazon web services", "software")
        val decision = LocalCategoryRuleLearningPolicy.decide(
            candidate = candidate,
            evidence = listOf(CategoryUsageCount("software", 3)),
            matchingRules = listOf(seedRule("amazon", "shopping", PatternType.SUBSTRING)),
        )

        assertThat(decision).isEqualTo(LocalCategoryRuleLearningPolicy.Decision.Learn)
    }

    @Test
    fun `candidate ignores transfers reconciliation and generic merchants`() {
        assertThat(LearningCandidate.from(tx(type = TxType.TRANSFER))).isNull()
        assertThat(LearningCandidate.from(tx(sourceRefId = RECONCILE_REF_PREFIX + "123"))).isNull()
        assertThat(LearningCandidate.from(tx(merchantNormalized = "online purchase"))).isNull()
        assertThat(LearningCandidate.from(tx(merchantNormalized = "كاش"))).isNull()
        assertThat(LearningCandidate.from(tx(status = TxStatus.PENDING))).isNull()
    }

    @Test
    fun `candidate refuses engine-derived provenance so the engine cannot teach itself`() {
        assertThat(LearningCandidate.from(tx(categorySource = CategorySource.CATALOG))).isNull()
        assertThat(LearningCandidate.from(tx(categorySource = CategorySource.CLASSIFIER))).isNull()
        // Explicit user decisions and legacy rows remain learnable.
        assertThat(LearningCandidate.from(tx(categorySource = CategorySource.USER))).isNotNull()
        assertThat(LearningCandidate.from(tx(categorySource = null))).isNotNull()
    }

    @Test
    fun `sql evidence query excludes engine-derived provenance rows`() {
        // Guarded by the migrated SQL in TransactionDao (see TransactionDao provenance queries);
        // this assertion pins the exclusion constants so an edit cannot silently drop them.
        assertThat(EngineProvenanceExclusion.SOURCE_NAMES)
            .containsExactly("CLASSIFIER", "CATALOG")
            .inOrder()
    }

    private fun autoRule(pattern: String, categoryId: String): CategoryRuleEntity =
        rule(
            id = LocalCategoryRuleLearner.AutoRuleIdPrefix + pattern,
            pattern = pattern,
            categoryId = categoryId,
            priority = LocalCategoryRuleLearner.AutoLearnedPriority,
            learnedFromUser = false,
        )

    private fun explicitRule(pattern: String, categoryId: String): CategoryRuleEntity =
        rule(
            id = "explicit-$pattern",
            pattern = pattern,
            categoryId = categoryId,
            priority = 200,
            learnedFromUser = true,
        )

    private fun seedRule(
        pattern: String,
        categoryId: String,
        patternType: PatternType = PatternType.EXACT,
    ): CategoryRuleEntity =
        rule(
            id = "seed-$pattern",
            pattern = pattern,
            patternType = patternType,
            categoryId = categoryId,
            priority = 100,
            learnedFromUser = false,
        )

    private fun rule(
        id: String,
        pattern: String,
        categoryId: String,
        priority: Int,
        learnedFromUser: Boolean,
        patternType: PatternType = PatternType.EXACT,
    ): CategoryRuleEntity = CategoryRuleEntity(
        id = id,
        pattern = pattern,
        patternType = patternType.name,
        categoryId = categoryId,
        priority = priority,
        learnedFromUser = learnedFromUser,
        createdAt = Instant.parse("2026-06-13T00:00:00Z"),
    )

    private fun tx(
        type: TxType = TxType.EXPENSE,
        status: TxStatus = TxStatus.CONFIRMED,
        merchantNormalized: String = "jarir bookstore",
        sourceRefId: String? = null,
        categorySource: CategorySource? = null,
    ): Transaction = Transaction(
        id = "tx-1",
        accountId = "cash",
        type = type,
        amount = Money.of("10.00"),
        date = LocalDate.parse("2026-06-13"),
        occurredAt = Instant.parse("2026-06-13T00:00:00Z"),
        merchant = merchantNormalized,
        merchantNormalized = merchantNormalized,
        categoryId = "education",
        notes = null,
        source = IngestSource.MANUAL,
        sourceRefId = sourceRefId,
        status = status,
        confidence = null,
        createdAt = Instant.parse("2026-06-13T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-13T00:00:00Z"),
        categorySource = categorySource,
    )
}
