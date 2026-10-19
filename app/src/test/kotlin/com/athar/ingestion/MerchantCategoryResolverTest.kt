package com.athar.ingestion

import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.MerchantCatalogEntry
import com.athar.core.domain.model.MerchantCatalogSnapshot
import com.athar.core.domain.model.MerchantCatalogState
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.MerchantCatalogRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Layered precedence tests for the app-owned resolver:
 * personal > auto-local > catalog > bundled > UNKNOWN, with valid-category
 * filtering at match time and deterministic in-layer ordering.
 */
class MerchantCategoryResolverTest {

    private val catCoffee = category("cat-coffee", CategoryKind.EXPENSE)
    private val catRestaurant = category("cat-restaurant", CategoryKind.EXPENSE)
    private val catSalary = category("cat-salary", CategoryKind.INCOME)
    private val catArchived = category("cat-old", CategoryKind.EXPENSE, archived = true)

    private fun category(
        id: String,
        kind: CategoryKind,
        archived: Boolean = false,
    ): Category = Category(
        id = id, name = id, nameAr = id, kind = kind,
        icon = null, monthlyTarget = null, archived = archived, sortOrder = 0,
    )

    private fun rule(
        id: String,
        pattern: String,
        patternType: PatternType,
        categoryId: String,
        priority: Int,
        learnedFromUser: Boolean,
    ): CategoryRule = CategoryRule(
        id = id, pattern = pattern, patternType = patternType, categoryId = categoryId,
        priority = priority, learnedFromUser = learnedFromUser,
        createdAt = Instant.parse("2026-06-13T00:00:00Z"),
    )

    private val rules = mockk<CategoryRuleRepository>()
    private val categories = mockk<CategoryRepository>()

    private fun resolverWith(catalogEntries: List<MerchantCatalogEntry>): MerchantCategoryResolver {
        // Broad matcher FIRST — MockK lets later stubs override earlier ones.
        coEvery { categories.get(any()) } returns null
        coEvery { categories.get("cat-coffee") } returns catCoffee
        coEvery { categories.get("cat-restaurant") } returns catRestaurant
        coEvery { categories.get("cat-salary") } returns catSalary
        coEvery { categories.get("cat-old") } returns catArchived
        val catalog = FakeCatalogRepository(catalogEntries)
        return MerchantCategoryResolver(rules, categories, catalog)
    }

    @Test
    fun `personal substring rule beats a catalog exact match`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("user-1", "merchant coffee", PatternType.SUBSTRING, "cat-restaurant", priority = 200, learnedFromUser = true),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)

        assertThat(result.suggestion.source).isEqualTo(CategorySource.RULE_SUBSTRING)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-restaurant")
        assertThat(result.hadTrustedLocalRule).isTrue()
    }

    @Test
    fun `auto-local exact rule beats the catalog`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("auto-local-1", "merchant coffee", PatternType.EXACT, "cat-restaurant", priority = 150, learnedFromUser = false),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)

        assertThat(result.suggestion.categoryId).isEqualTo("cat-restaurant")
        assertThat(result.hadTrustedLocalRule).isTrue()
    }

    @Test
    fun `regression - unlearned auto-local substring falls behind catalog`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("auto-local-substring", "merchant coffee", PatternType.SUBSTRING, "cat-restaurant", priority = 150, learnedFromUser = false),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )
        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)
        assertThat(result.suggestion.source).isEqualTo(CategorySource.CATALOG)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-coffee")
        assertThat(result.hadTrustedLocalRule).isTrue()
    }

    @Test
    fun `regression - learned auto-local substring remains personal`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("auto-local-learned", "merchant coffee", PatternType.SUBSTRING, "cat-restaurant", priority = 150, learnedFromUser = true),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )
        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)
        assertThat(result.suggestion.source).isEqualTo(CategorySource.RULE_SUBSTRING)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-restaurant")
        assertThat(result.hadTrustedLocalRule).isTrue()
    }

    @Test
    fun `regression - catalog matching canonicalizes whitespace without changing personal lookup`() = runTest {
        coEvery { rules.findMatching("merchant  coffee") } returns emptyList()
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )
        val result = resolver.resolve("merchant  coffee", TxType.EXPENSE)
        assertThat(result.suggestion.source).isEqualTo(CategorySource.CATALOG)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-coffee")
    }

    @Test
    fun `regression - entries and version must come from one snapshot`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns emptyList()
        coEvery { categories.get("cat-coffee") } returns catCoffee
        val catalog = OldApiBarrierCatalogRepository(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )
        val resolver = MerchantCategoryResolver(rules, categories, catalog)
        val resolution = async { resolver.resolve("merchant coffee", TxType.EXPENSE) }
        catalog.entriesStarted.await()
        catalog.state.value = catalog.state.value.copy(version = 2L)
        catalog.releaseEntries.complete(Unit)
        assertThat(resolution.await().suggestion.ruleId).isEqualTo("catalog:1:EXACT:merchant coffee")
    }

    @Test
    fun `regression - conflicting catalog vs bundled yields catalog category with trusted-local true`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("seed-1", "coffee", PatternType.SUBSTRING, "cat-restaurant", priority = 100, learnedFromUser = false),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)

        // Downloaded corrections outrank bundled seeds…
        assertThat(result.suggestion.source).isEqualTo(CategorySource.CATALOG)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-coffee")
        assertThat(result.suggestion.ruleId).isEqualTo("catalog:1:EXACT:merchant coffee")
        // …but a trusted local rule still existed, so legacy status eligibility is unchanged.
        assertThat(result.hadTrustedLocalRule).isTrue()
    }

    @Test
    fun `regression - personal rule still outranks catalog even when catalog exact matches`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("user-1", "merchant coffee", PatternType.EXACT, "cat-restaurant", priority = 200, learnedFromUser = true),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)

        assertThat(result.suggestion.source).isEqualTo(CategorySource.RULE_EXACT)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-restaurant")
        assertThat(result.hadTrustedLocalRule).isTrue()
    }

    @Test
    fun `catalog hit without any local match reports no trusted local rule`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns emptyList()
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)

        assertThat(result.suggestion.source).isEqualTo(CategorySource.CATALOG)
        assertThat(result.hadTrustedLocalRule).isFalse()
    }

    @Test
    fun `archived selected category is skipped and the next valid layer wins`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("seed-1", "merchant coffee", PatternType.EXACT, "cat-old", priority = 100, learnedFromUser = false),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)

        assertThat(result.suggestion.source).isEqualTo(CategorySource.CATALOG)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-coffee")
        assertThat(result.hadTrustedLocalRule).isFalse()
    }

    @Test
    fun `missing category id is skipped the same as an archived one`() = runTest {
        coEvery { rules.findMatching("merchant coffee") } returns listOf(
            rule("user-1", "merchant coffee", PatternType.EXACT, "cat-deleted", priority = 200, learnedFromUser = true),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("merchant coffee", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("merchant coffee", TxType.EXPENSE)

        assertThat(result.suggestion.source).isEqualTo(CategorySource.CATALOG)
        assertThat(result.hadTrustedLocalRule).isFalse()
    }

    @Test
    fun `kind-incompatible layers are skipped`() = runTest {
        // Bundled rule points at an EXPENSE category — invalid for an INCOME tx.
        coEvery { rules.findMatching("acme payroll") } returns listOf(
            rule("seed-1", "acme payroll", PatternType.EXACT, "cat-coffee", priority = 100, learnedFromUser = false),
        )
        // The catalog entry carries a valid INCOME category and wins.
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("acme payroll", PatternType.EXACT, "cat-salary")),
        )

        val result = resolver.resolve("acme payroll", TxType.INCOME)

        assertThat(result.suggestion.source).isEqualTo(CategorySource.CATALOG)
        assertThat(result.suggestion.categoryId).isEqualTo("cat-salary")
        assertThat(result.hadTrustedLocalRule).isFalse()
    }

    @Test
    fun `transfers are never classified`() = runTest {
        coEvery { rules.findMatching("internal move") } returns listOf(
            rule("user-1", "internal move", PatternType.EXACT, "cat-restaurant", priority = 200, learnedFromUser = true),
        )
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("internal move", PatternType.EXACT, "cat-coffee")),
        )

        val result = resolver.resolve("internal move", TxType.TRANSFER)

        assertThat(result.suggestion.categoryId).isNull()
        assertThat(result.suggestion.source).isEqualTo(CategorySource.UNKNOWN)
        assertThat(result.hadTrustedLocalRule).isFalse()
    }

    @Test
    fun `catalog substring respects latin word boundaries`() = runTest {
        coEvery { rules.findMatching("mystcother") } returns emptyList()
        val resolver = resolverWith(
            listOf(MerchantCatalogEntry("stc", PatternType.SUBSTRING, "cat-coffee")),
        )

        val noMatch = resolver.resolve("mystcother", TxType.EXPENSE)
        assertThat(noMatch.suggestion.categoryId).isNull()

        coEvery { rules.findMatching("stc pay") } returns emptyList()
        val match = resolver.resolve("stc pay", TxType.EXPENSE)
        assertThat(match.suggestion.categoryId).isEqualTo("cat-coffee")
    }

    @Test
    fun `within the catalog exact beats longer substring deterministically`() = runTest {
        coEvery { rules.findMatching("starbucks") } returns emptyList()
        val resolver = resolverWith(
            listOf(
                MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-restaurant"),
                MerchantCatalogEntry("starbucks", PatternType.EXACT, "cat-coffee"),
            ),
        )

        val result = resolver.resolve("starbucks", TxType.EXPENSE)

        assertThat(result.suggestion.categoryId).isEqualTo("cat-coffee")
        assertThat(result.suggestion.ruleId).isEqualTo("catalog:1:EXACT:starbucks")
    }

    @Test
    fun `everything unknown when no layer matches`() = runTest {
        coEvery { rules.findMatching("unknown merchant") } returns emptyList()
        val resolver = resolverWith(emptyList())

        val result = resolver.resolve("unknown merchant", TxType.EXPENSE)

        assertThat(result.suggestion.categoryId).isNull()
        assertThat(result.suggestion.source).isEqualTo(CategorySource.UNKNOWN)
        assertThat(result.hadTrustedLocalRule).isFalse()
    }
}

private class OldApiBarrierCatalogRepository(
    private val items: List<MerchantCatalogEntry>,
) : MerchantCatalogRepository {
    override val state = MutableStateFlow(
        MerchantCatalogState(version = 1L, entryCount = items.size),
    )
    val entriesStarted = CompletableDeferred<Unit>()
    val releaseEntries = CompletableDeferred<Unit>()
    override suspend fun initialize() = Unit
    override suspend fun setUpdatesEnabled(enabled: Boolean) = Unit
    override suspend fun refresh(force: Boolean) = Unit
    override suspend fun resetToBundled() = Unit
    override suspend fun snapshot(): MerchantCatalogSnapshot {
        val captured = MerchantCatalogSnapshot(items.toList(), requireNotNull(state.value.version))
        entriesStarted.complete(Unit)
        releaseEntries.await()
        return captured
    }
}

/** Minimal honest fake of the domain catalog repository for resolver tests. */
private class FakeCatalogRepository(
    private val items: List<MerchantCatalogEntry>,
) : MerchantCatalogRepository {
    override val state = MutableStateFlow(
        MerchantCatalogState(
            updatesEnabled = true,
            networkSupported = false,
            version = 1L,
            entryCount = items.size,
            checkedAt = null,
            phase = com.athar.core.domain.model.CatalogRefreshPhase.CURRENT,
        ),
    )

    override suspend fun initialize() = Unit
    override suspend fun setUpdatesEnabled(enabled: Boolean) = Unit
    override suspend fun refresh(force: Boolean) = Unit
    override suspend fun resetToBundled() = Unit
    override suspend fun snapshot(): MerchantCatalogSnapshot = MerchantCatalogSnapshot(items.toList(), 1L)
}
