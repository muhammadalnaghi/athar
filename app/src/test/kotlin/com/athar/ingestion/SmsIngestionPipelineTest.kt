package com.athar.ingestion

import com.athar.core.common.money.Money
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.SmsAuditRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.SmsParser
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * Status-independence contract: the ingestion pipeline's initial financial status
 * keys on a trusted LOCAL rule, never on catalog presence or suggestion confidence.
 * A newly downloadable catalog label can never flip a previously-pending payment
 * into a financial posting.
 */
class SmsIngestionPipelineTest {

    private val now: Instant = Instant.parse("2026-10-16T12:00:00Z")

    private val transactions = mockk<TransactionRepository>(relaxed = true) {
        coEvery { upsertIngest(any()) } answers { firstArg<Transaction>().id }
    }
    private val audit = mockk<SmsAuditRepository>(relaxed = true)
    private val accounts = mockk<AccountRepository> {
        coEvery { resolveForIngest(any(), any(), any()) } returns null
    }

    private fun pipeline(
        rules: CategoryRuleRepository,
        categories: CategoryRepository,
        catalog: FakePipelineCatalog,
        ownAccounts: List<String> = emptyList(),
        parserOverride: SmsParser? = null,
    ): SmsIngestionPipeline {
        val prefs = mockk<UserPreferencesRepository> {
            every { ownAccountNumbers() } returns flowOf(ownAccounts)
        }
        val resolver = MerchantCategoryResolver(rules, categories, catalog)
        return SmsIngestionPipeline(
            parser = parserOverride ?: parserFake(),
            transactions = transactions,
            accounts = accounts,
            resolver = resolver,
            audit = audit,
            prefs = prefs,
            clock = object : Clock {
                override fun now(): Instant = this@SmsIngestionPipelineTest.now
            },
        )
    }

    @Test
    fun `catalog-only suggestion stays pending and is tagged with catalog provenance`() = runTest {
        val (rules, categories) = emptyRules()
        val catalog = FakePipelineCatalog(
            entries = listOf(catalogEntry("starbucks", "cat-coffee")),
        )
        val pipeline = pipeline(rules, categories, catalog)

        pipeline.process(event())

        val tx = capturedTransaction()
        assertThat(tx.status).isEqualTo(TxStatus.PENDING)
        assertThat(tx.categoryId).isEqualTo("cat-coffee")
        assertThat(tx.categorySource).isEqualTo(CategorySource.CATALOG)
    }

    @Test
    fun `trusted local rule confirms exactly as before the catalog existed`() = runTest {
        val categories = mockCategories()
        val rules = mockk<CategoryRuleRepository> {
            coEvery { findMatching("starbucks") } returns listOf(
                rule("user-1", "starbucks", priority = 200, learnedFromUser = true),
            )
        }
        val pipeline = pipeline(rules, categories, FakePipelineCatalog(emptyList()))

        pipeline.process(event())

        val tx = capturedTransaction()
        assertThat(tx.status).isEqualTo(TxStatus.CONFIRMED)
        assertThat(tx.categorySource).isEqualTo(CategorySource.RULE_EXACT)
    }

    @Test
    fun `bundled seed rule keeps legacy auto-confirm behavior`() = runTest {
        val categories = mockCategories()
        val rules = mockk<CategoryRuleRepository> {
            coEvery { findMatching("starbucks") } returns listOf(
                rule("seed-1", "starbucks", priority = 100, learnedFromUser = false),
            )
        }
        val pipeline = pipeline(rules, categories, FakePipelineCatalog(emptyList()))

        pipeline.process(event())

        val tx = capturedTransaction()
        assertThat(tx.status).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `archived local category falls through to catalog and stays pending`() = runTest {
        val categories = mockCategories(withArchived = true)
        val rules = mockk<CategoryRuleRepository> {
            coEvery { findMatching("starbucks") } returns listOf(
                rule("seed-1", "starbucks", priority = 100, learnedFromUser = false, categoryId = "cat-old"),
            )
        }
        val catalog = FakePipelineCatalog(
            entries = listOf(catalogEntry("starbucks", "cat-coffee")),
        )
        val pipeline = pipeline(rules, categories, catalog)

        pipeline.process(event())

        val tx = capturedTransaction()
        assertThat(tx.status).isEqualTo(TxStatus.PENDING)
        assertThat(tx.categoryId).isEqualTo("cat-coffee")
        assertThat(tx.categorySource).isEqualTo(CategorySource.CATALOG)
    }

    @Test
    fun `self-transfer stays pending regardless of any match`() = runTest {
        val categories = mockCategories()
        val rules = mockk<CategoryRuleRepository> {
            coEvery { findMatching(any()) } returns listOf(
                rule("user-1", "تحويل داخلي", priority = 200, learnedFromUser = true),
            )
        }
        val pipeline = pipeline(rules, categories, FakePipelineCatalog(emptyList()), ownAccounts = listOf("0930"))

        pipeline.process(event(type = TxType.TRANSFER, counterparty = "acc 0930"))

        val tx = capturedTransaction()
        assertThat(tx.status).isEqualTo(TxStatus.PENDING)
        assertThat(tx.categoryId).isNull()
    }

    @Test
    fun `no match at all lands pending with unknown provenance`() = runTest {
        val (rules, categories) = emptyRules()
        val pipeline = pipeline(rules, categories, FakePipelineCatalog(emptyList()))

        pipeline.process(event())

        val tx = capturedTransaction()
        assertThat(tx.status).isEqualTo(TxStatus.PENDING)
        assertThat(tx.categoryId).isNull()
        assertThat(tx.categorySource).isNull()
    }

    @Test
    fun `audit links the stored source owner rather than a fresh replay uuid`() = runTest {
        coEvery { transactions.upsertIngest(any()) } returns "original-source-owner"
        coEvery { audit.record(any()) } returns "replay-audit"
        val (rules, categories) = emptyRules()
        pipeline(rules, categories, FakePipelineCatalog(emptyList())).process(event())
        coVerify(exactly = 1) {
            audit.updateParseResult("replay-audit", com.athar.core.domain.model.SmsParseStatus.PARSED, "original-source-owner", null)
        }
        coVerify(exactly = 0) { transactions.upsert(any()) }
    }

    @Test
    fun `local category rule cannot confirm held money while rollout is off`() = runTest {
        val categories = mockCategories()
        val rules = mockk<CategoryRuleRepository> {
            coEvery { findMatching("starbucks") } returns listOf(rule("user-1", "starbucks", priority = 200, learnedFromUser = true))
        }
        val held = SmsParser { event -> (parserFake().parse(event) as ParseResult.Success).copy(
            posting = com.athar.core.domain.model.financial.PostingEvidence(
                com.athar.core.domain.model.financial.PostingState.AUTHORIZED,
                com.athar.core.domain.model.financial.PostingSupport.HEURISTIC_ONLY,
            ),
        ) }
        pipeline(rules, categories, FakePipelineCatalog(emptyList()), parserOverride = held).process(event())
        assertThat(capturedTransaction().status).isEqualTo(TxStatus.PENDING)
    }

    @Test
    fun `local category rule cannot confirm declined money while rollout is off`() = runTest {
        val categories = mockCategories()
        val rules = mockk<CategoryRuleRepository> {
            coEvery { findMatching("starbucks") } returns listOf(rule("user-1", "starbucks", priority = 200, learnedFromUser = true))
        }
        val declined = SmsParser { event -> (parserFake().parse(event) as ParseResult.Success).copy(
            posting = com.athar.core.domain.model.financial.PostingEvidence(
                com.athar.core.domain.model.financial.PostingState.DECLINED,
                com.athar.core.domain.model.financial.PostingSupport.HEURISTIC_ONLY,
            ),
        ) }
        pipeline(rules, categories, FakePipelineCatalog(emptyList()), parserOverride = declined).process(event())
        assertThat(capturedTransaction().status).isEqualTo(TxStatus.PENDING)
    }

    // ---- helpers ----

    private fun parserFake(): SmsParser = SmsParser { event ->
        ParseResult.Success(
            type = if (event.body.contains("transfer")) TxType.TRANSFER else TxType.EXPENSE,
            amount = Money.ofMinor(20_00, "SAR"),
            merchant = "Starbucks",
            counterparty = event.body.takeIf { it.contains("transfer") }?.let { "acc 0930" },
            balanceAfter = null,
            occurredAt = null,
            confidence = 0.95f,
            templateId = "test-template",
        )
    }

    private fun rule(
        id: String,
        pattern: String,
        priority: Int,
        learnedFromUser: Boolean,
        categoryId: String = "cat-coffee",
    ): com.athar.core.domain.model.CategoryRule = com.athar.core.domain.model.CategoryRule(
        id = id,
        pattern = pattern,
        patternType = com.athar.core.domain.model.PatternType.EXACT,
        categoryId = categoryId,
        priority = priority,
        learnedFromUser = learnedFromUser,
        createdAt = now,
    )

    private fun catalogEntry(pattern: String, categoryId: String): com.athar.core.domain.model.MerchantCatalogEntry =
        com.athar.core.domain.model.MerchantCatalogEntry(
            pattern = pattern,
            patternType = com.athar.core.domain.model.PatternType.EXACT,
            categoryId = categoryId,
        )

    private fun mockCategories(withArchived: Boolean = false): CategoryRepository {
        val coffee = com.athar.core.domain.model.Category(
            id = "cat-coffee", name = "Coffee", nameAr = "قهوة",
            kind = com.athar.core.domain.model.CategoryKind.EXPENSE,
            icon = null, monthlyTarget = null, archived = false, sortOrder = 0,
        )
        val repo = mockk<CategoryRepository>()
        // Broad matcher FIRST — MockK lets later stubs override earlier ones.
        coEvery { repo.get(any()) } returns null
        coEvery { repo.get("cat-coffee") } returns coffee
        coEvery { repo.get("cat-old") } returns if (withArchived) coffee.copy(archived = true) else coffee
        return repo
    }

    private fun emptyRules(): Pair<CategoryRuleRepository, CategoryRepository> {
        val rules = mockk<CategoryRuleRepository>()
        coEvery { rules.findMatching(any()) } returns emptyList()
        return rules to mockCategories()
    }

    private fun capturedTransaction(): Transaction {
        val slot = slot<Transaction>()
        coVerify(exactly = 1) { transactions.upsertIngest(capture(slot)) }
        return slot.captured
    }

    private fun event(
        type: TxType = TxType.EXPENSE,
        counterparty: String? = null,
    ): RawIngestEvent = RawIngestEvent(
        id = "evt-1",
        source = IngestSource.SMS,
        sender = "AlRajhiBank",
        body = if (type == TxType.TRANSFER) "transfer" else "purchase",
        receivedAt = now,
        rawId = "raw-1",
    ).let { evt ->
        if (counterparty != null) evt.copy(body = "transfer $counterparty") else evt
    }
}

/** Minimal honest fake of the domain catalog repository for pipeline tests. */
private class FakePipelineCatalog(
    private val entries: List<com.athar.core.domain.model.MerchantCatalogEntry>,
) : com.athar.core.domain.repo.MerchantCatalogRepository {

    override val state = MutableStateFlow(
        com.athar.core.domain.model.MerchantCatalogState(
            updatesEnabled = true,
            networkSupported = false,
            version = 1L,
            entryCount = entries.size,
            checkedAt = null,
            phase = com.athar.core.domain.model.CatalogRefreshPhase.CURRENT,
        ),
    )

    override suspend fun initialize() = Unit
    override suspend fun setUpdatesEnabled(enabled: Boolean) = Unit
    override suspend fun refresh(force: Boolean) = Unit
    override suspend fun resetToBundled() = Unit
    override suspend fun snapshot(): com.athar.core.domain.model.MerchantCatalogSnapshot =
        com.athar.core.domain.model.MerchantCatalogSnapshot(entries.toList(), 1L)
}
