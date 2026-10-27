package com.athar.core.data.csv

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.SmsParseStatus
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.MerchantBulkExportMode
import com.athar.core.domain.repo.MerchantBulkImportCategoryImpact
import com.athar.core.domain.repo.MerchantBulkImportResult
import com.athar.core.domain.repo.MerchantBulkImportSkipSummary
import com.athar.core.domain.repo.SmsAuditEntry
import com.athar.core.domain.repo.SmsAuditRepository
import com.athar.core.domain.repo.TransactionRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MerchantBulkCsvTest {

    @Test
    fun `export emits stable key and importer matches changed transaction id`() = runTest {
        val oldTx = tx(
            id = "old-id",
            sourceRefId = "inbox-42",
            notes = "شراء\nمبلغ:SAR 19\nمن:BARNS",
        )
        val exportRepo = FakeTransactionRepository(listOf(oldTx))
        val out = ByteArrayOutputStream()

        val exported = MerchantBulkExporter(
            transactions = exportRepo,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            smsAudit = FakeSmsAuditRepository(),
        ).exportUncategorized(out, MerchantBulkExportMode.FULL_CONTEXT)

        assertThat(exported).isEqualTo(com.athar.core.domain.repo.MerchantBulkExportResult.Done(rows = 1))
        val csv = out.toString(Charsets.UTF_8)
        assertThat(csv).contains(
            "id,stable_key,source_ref_id,merchant,merchant_normalized,merchant_group_count," +
                "merchant_group_rank,merchant_group_share_permille,merchant_group_cumulative_share_permille," +
                "category_options",
        )
        assertThat(csv).contains("cat-coffee=Coffee / قهوة")
        assertThat(csv).contains("\"شراء\nمبلغ:SAR 19\nمن:BARNS\"")

        val filledCsv = csv.trimEnd().removeSuffix(",") + ",cat-coffee\n"
        val currentTx = oldTx.copy(id = "new-id", categoryId = null, status = TxStatus.PENDING)
        val importRepo = FakeTransactionRepository(listOf(currentTx))
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = importRepo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.importCategorizations(ByteArrayInputStream(filledCsv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 1, skipped = 0))
        assertThat(importRepo.get("new-id")?.categoryId).isEqualTo("cat-coffee")
        assertThat(importRepo.get("new-id")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules.map { it.pattern to it.categoryId })
            .containsExactly("barns" to "cat-coffee")
        assertThat(rules.learnedRules.single().patternType).isEqualTo(PatternType.EXACT)
    }

    @Test
    fun `private export leaves raw body blank while keeping import columns`() = runTest {
        val tx = tx(
            id = "private-row",
            sourceRefId = "inbox-private",
            merchant = "Private Shop",
            notes = "sensitive merchant detail and card hint",
        )
        val out = ByteArrayOutputStream()

        val exported = MerchantBulkExporter(
            transactions = FakeTransactionRepository(listOf(tx)),
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            smsAudit = FakeSmsAuditRepository(),
        ).exportUncategorized(out, MerchantBulkExportMode.NO_RAW_BODY)

        assertThat(exported).isEqualTo(com.athar.core.domain.repo.MerchantBulkExportResult.Done(rows = 1))
        val csv = out.toString(Charsets.UTF_8)
        assertThat(csv).doesNotContain("sensitive merchant detail")
        val lines = csv.trim().lines()
        val header = lines.first().split(",")
        val row = lines.drop(1).single().split(",")
        assertThat(row[header.indexOf("raw_body")]).isEmpty()
        assertThat(row[header.indexOf("id")]).isEqualTo("private-row")
        assertThat(row[header.indexOf("stable_key")]).isNotEmpty()
        assertThat(row[header.indexOf("category_options")]).contains("cat-coffee=Coffee")
    }

    @Test
    fun `full context export uses linked audit body before transaction notes`() = runTest {
        val tx = tx(
            id = "parsed-row",
            sourceRefId = "sms-raw-1",
            merchant = "Ambiguous Branch",
            notes = "manual note fallback",
        )
        val auditBody = "شراء بمبلغ 42.00 ر.س\nمن Ambiguous Branch - Riyadh"
        val out = ByteArrayOutputStream()

        val exported = MerchantBulkExporter(
            transactions = FakeTransactionRepository(listOf(tx)),
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            smsAudit = FakeSmsAuditRepository(
                listOf(
                    SmsAuditEntry(
                        id = "audit-1",
                        sender = "AlRajhiBank",
                        body = auditBody,
                        receivedAt = FixedInstant,
                        parsedTransactionId = "parsed-row",
                        status = SmsParseStatus.PARSED,
                        error = null,
                    ),
                ),
            ),
        ).exportUncategorized(out, MerchantBulkExportMode.FULL_CONTEXT)

        assertThat(exported).isEqualTo(com.athar.core.domain.repo.MerchantBulkExportResult.Done(rows = 1))
        val csv = out.toString(Charsets.UTF_8)
        assertThat(csv).contains(auditBody)
        assertThat(csv).doesNotContain("manual note fallback")
    }

    @Test
    fun `private export omits linked audit body`() = runTest {
        val tx = tx(
            id = "private-audit-row",
            sourceRefId = "sms-private",
            merchant = "Private Branch",
            notes = "private note fallback",
        )
        val auditBody = "sensitive raw bank body"
        val out = ByteArrayOutputStream()

        val exported = MerchantBulkExporter(
            transactions = FakeTransactionRepository(listOf(tx)),
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            smsAudit = FakeSmsAuditRepository(
                listOf(
                    SmsAuditEntry(
                        id = "audit-private",
                        sender = "Bank",
                        body = auditBody,
                        receivedAt = FixedInstant,
                        parsedTransactionId = "private-audit-row",
                        status = SmsParseStatus.PARSED,
                        error = null,
                    ),
                ),
            ),
        ).exportUncategorized(out, MerchantBulkExportMode.NO_RAW_BODY)

        assertThat(exported).isEqualTo(com.athar.core.domain.repo.MerchantBulkExportResult.Done(rows = 1))
        val csv = out.toString(Charsets.UTF_8)
        assertThat(csv).doesNotContain(auditBody)
        assertThat(csv).doesNotContain("private note fallback")
        val lines = csv.trim().lines()
        val header = lines.first().split(",")
        val row = lines.drop(1).single().split(",")
        assertThat(row[header.indexOf("raw_body")]).isEmpty()
    }

    @Test
    fun `importer can match old csv without stable key by content fingerprint`() = runTest {
        val currentTx = tx(id = "new-id", sourceRefId = null, merchant = "Hemmah", amount = "99.00")
        val repo = FakeTransactionRepository(listOf(currentTx))
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = FakeCategoryRuleRepository(),
            categories = FakeCategoryRepository(listOf(homeCategory())),
            clock = FixedClock,
        )
        val oldCsv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,raw_body,category_id
            old-id,Hemmah,hemmah,99.0,SAR,EXPENSE,PENDING,2026-02-14,"line one
            line two",cat-home-maintenance
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(oldCsv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("new-id")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("new-id")?.status).isEqualTo(TxStatus.PENDING)
    }

    @Test
    fun `preview reports import impact without mutating transactions or rules`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "hemmah-1", sourceRefId = "inbox-1", merchant = "Hemmah", amount = "25.00"),
                tx(id = "hemmah-2", sourceRefId = "inbox-2", merchant = "Hemmah", amount = "35.00"),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(homeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            hemmah-1,Hemmah,hemmah,25.00,SAR,EXPENSE,PENDING,2026-02-14,cat-home-maintenance
            hemmah-2,Hemmah,hemmah,35.00,SAR,EXPENSE,PENDING,2026-02-14,
        """.trimIndent()
        val bytes = csv.toByteArray()

        val preview = importer.previewCategorizations(ByteArrayInputStream(bytes))

        assertThat(preview).isEqualTo(
            MerchantBulkImportResult.Done(
                updated = 2,
                rulesAdded = 1,
                skipped = 0,
                categoryImpact = listOf(
                    MerchantBulkImportCategoryImpact(
                        categoryId = "cat-home-maintenance",
                        categoryName = "Home maintenance",
                        categoryNameAr = "صيانة منزل",
                        updated = 2,
                    ),
                ),
            ),
        )
        assertThat(repo.get("hemmah-1")?.categoryId).isNull()
        assertThat(repo.get("hemmah-2")?.categoryId).isNull()
        assertThat(repo.get("hemmah-1")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(repo.get("hemmah-2")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules).isEmpty()

        val imported = importer.importCategorizations(ByteArrayInputStream(bytes))

        assertThat(imported).isEqualTo(MerchantBulkImportResult.Done(updated = 2, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("hemmah-1")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("hemmah-2")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(rules.learnedRules.map { it.pattern to it.categoryId })
            .containsExactly("hemmah" to "cat-home-maintenance")
    }

    @Test
    fun `importer does not duplicate an existing exact local rule for same category`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(tx(id = "hemmah", sourceRefId = "inbox-hemmah", merchant = "Hemmah")),
        )
        val rules = FakeCategoryRuleRepository(
            listOf(
                learnedRule(
                    id = "existing-exact-hemmah",
                    pattern = "hemmah",
                    categoryId = "cat-home-maintenance",
                    patternType = PatternType.EXACT,
                ),
            ),
        )
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(homeCategory(), coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            hemmah,Hemmah,hemmah,19.00,SAR,EXPENSE,PENDING,2026-02-14,cat-home-maintenance
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 0, skipped = 0))
        assertThat(repo.get("hemmah")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(rules.learnedRules.map { Triple(it.id, it.pattern, it.categoryId) })
            .containsExactly(Triple("existing-exact-hemmah", "hemmah", "cat-home-maintenance"))
    }

    @Test
    fun `importer replaces stale exact local rule when category changes`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(tx(id = "hemmah", sourceRefId = "inbox-hemmah", merchant = "Hemmah")),
        )
        val rules = FakeCategoryRuleRepository(
            listOf(
                learnedRule(
                    id = "old-exact-hemmah",
                    pattern = "hemmah",
                    categoryId = "cat-coffee",
                    patternType = PatternType.EXACT,
                ),
                learnedRule(
                    id = "always-hemmah",
                    pattern = "hemmah",
                    categoryId = "cat-coffee",
                    patternType = PatternType.SUBSTRING,
                ),
                learnedRule(
                    id = "seed-hemmah",
                    pattern = "hemmah",
                    categoryId = "cat-coffee",
                    patternType = PatternType.EXACT,
                    learnedFromUser = false,
                    priority = 90,
                ),
            ),
        )
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(homeCategory(), coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            hemmah,Hemmah,hemmah,19.00,SAR,EXPENSE,PENDING,2026-02-14,cat-home-maintenance
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("hemmah")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(rules.learnedRules.map { it.id }).doesNotContain("old-exact-hemmah")
        assertThat(rules.learnedRules.map { it.id }).contains("always-hemmah")
        assertThat(rules.learnedRules.map { it.id }).contains("seed-hemmah")
        assertThat(
            rules.learnedRules.filter {
                it.learnedFromUser &&
                    it.patternType == PatternType.EXACT &&
                    it.pattern == "hemmah"
            }.map { it.categoryId },
        ).containsExactly("cat-home-maintenance")
    }

    @Test
    fun `importer propagates one category choice to blank rows for the same merchant group`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "hemmah-1", sourceRefId = "inbox-1", merchant = "Hemmah", amount = "25.00"),
                tx(id = "hemmah-2", sourceRefId = "inbox-2", merchant = "Hemmah", amount = "35.00"),
                tx(id = "other", sourceRefId = "inbox-3", merchant = "Other Shop", amount = "45.00"),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(homeCategory(), coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            hemmah-1,Hemmah,hemmah,25.00,SAR,EXPENSE,PENDING,2026-02-14,cat-home-maintenance
            hemmah-2,Hemmah,hemmah,35.00,SAR,EXPENSE,PENDING,2026-02-14,
            other,Other Shop,other shop,45.00,SAR,EXPENSE,PENDING,2026-02-14,
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(
            MerchantBulkImportResult.Done(
                updated = 2,
                rulesAdded = 1,
                skipped = 1,
                skipSummary = MerchantBulkImportSkipSummary(blankRowsWithoutGroupChoice = 1),
            ),
        )
        assertThat(repo.get("hemmah-1")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("hemmah-2")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("hemmah-1")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(repo.get("hemmah-2")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(repo.get("other")?.categoryId).isNull()
        assertThat(rules.learnedRules.map { it.pattern to it.categoryId })
            .containsExactly("hemmah" to "cat-home-maintenance")
        assertThat(rules.learnedRules.single().patternType).isEqualTo(PatternType.EXACT)
    }

    @Test
    fun `importer updates explicit generic merchant rows without propagating or training rules`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "payment-1", sourceRefId = "inbox-1", merchant = "Payment", amount = "25.00"),
                tx(id = "payment-2", sourceRefId = "inbox-2", merchant = "Payment", amount = "35.00"),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            payment-1,Payment,payment,25.00,SAR,EXPENSE,PENDING,2026-02-14,cat-coffee
            payment-2,Payment,payment,35.00,SAR,EXPENSE,PENDING,2026-02-14,
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(
            MerchantBulkImportResult.Done(
                updated = 1,
                rulesAdded = 0,
                skipped = 1,
                skipSummary = MerchantBulkImportSkipSummary(blankRowsWithoutGroupChoice = 1),
            ),
        )
        assertThat(repo.get("payment-1")?.categoryId).isEqualTo("cat-coffee")
        assertThat(repo.get("payment-1")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(repo.get("payment-2")?.categoryId).isNull()
        assertThat(repo.get("payment-2")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules).isEmpty()
    }

    @Test
    fun `importer does not update same merchant transactions outside the imported csv`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "hemmah-1", sourceRefId = "inbox-1", merchant = "Hemmah", amount = "25.00"),
                tx(id = "hemmah-2", sourceRefId = "inbox-2", merchant = "Hemmah", amount = "35.00"),
                tx(id = "outside-csv", sourceRefId = "inbox-outside", merchant = "Hemmah", amount = "95.00"),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(homeCategory(), coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            hemmah-1,Hemmah,hemmah,25.00,SAR,EXPENSE,PENDING,2026-02-14,cat-home-maintenance
            hemmah-2,Hemmah,hemmah,35.00,SAR,EXPENSE,PENDING,2026-02-14,
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 2, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("hemmah-1")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("hemmah-2")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("outside-csv")?.categoryId).isNull()
        assertThat(repo.get("outside-csv")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules.map { it.pattern to it.categoryId })
            .containsExactly("hemmah" to "cat-home-maintenance")
        assertThat(rules.learnedRules.single().patternType).isEqualTo(PatternType.EXACT)
    }

    @Test
    fun `importer does not train or propagate a merchant group with conflicting categories`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "hemmah-1", sourceRefId = "inbox-1", merchant = "Hemmah", amount = "25.00"),
                tx(id = "hemmah-2", sourceRefId = "inbox-2", merchant = "Hemmah", amount = "35.00"),
                tx(id = "hemmah-3", sourceRefId = "inbox-3", merchant = "Hemmah", amount = "45.00"),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(homeCategory(), coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            hemmah-1,Hemmah,hemmah,25.00,SAR,EXPENSE,PENDING,2026-02-14,cat-home-maintenance
            hemmah-2,Hemmah,hemmah,35.00,SAR,EXPENSE,PENDING,2026-02-14,cat-coffee
            hemmah-3,Hemmah,hemmah,45.00,SAR,EXPENSE,PENDING,2026-02-14,
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(
            MerchantBulkImportResult.Done(
                updated = 2,
                rulesAdded = 0,
                skipped = 1,
                skipSummary = MerchantBulkImportSkipSummary(conflictingGroups = 1),
            ),
        )
        assertThat(repo.get("hemmah-1")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("hemmah-2")?.categoryId).isEqualTo("cat-coffee")
        assertThat(repo.get("hemmah-3")?.categoryId).isNull()
        assertThat(repo.get("hemmah-3")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules).isEmpty()
    }

    @Test
    fun `importer skips explicit category that does not match transaction type`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "expense", sourceRefId = "inbox-expense", merchant = "Coffee Shop"),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(coffeeCategory(), salaryCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            expense,Coffee Shop,coffee shop,19.00,SAR,EXPENSE,PENDING,2026-02-14,cat-salary
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(
            MerchantBulkImportResult.Done(
                updated = 0,
                rulesAdded = 0,
                skipped = 1,
                skipSummary = MerchantBulkImportSkipSummary(incompatibleCategories = 1),
            ),
        )
        assertThat(repo.get("expense")?.categoryId).isNull()
        assertThat(repo.get("expense")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules).isEmpty()
    }

    @Test
    fun `importer accepts category option copied into category column`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "coffee", sourceRefId = "inbox-coffee", merchant = "Coffee Shop"),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            coffee,Coffee Shop,coffee shop,19.00,SAR,EXPENSE,PENDING,2026-02-14,cat-coffee=Coffee / قهوة
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("coffee")?.categoryId).isEqualTo("cat-coffee")
        assertThat(repo.get("coffee")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules.map { it.pattern to it.categoryId })
            .containsExactly("coffee shop" to "cat-coffee")
    }

    @Test
    fun `importer accepts copied bilingual category display label`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "coffee", sourceRefId = "inbox-coffee", merchant = "Coffee Shop"),
            ),
        )
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = FakeCategoryRuleRepository(),
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            coffee,Coffee Shop,coffee shop,19.00,SAR,EXPENSE,PENDING,2026-02-14,Coffee / قهوة
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("coffee")?.categoryId).isEqualTo("cat-coffee")
    }

    @Test
    fun `importer resolves category ids case insensitively`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "coffee", sourceRefId = "inbox-coffee", merchant = "Coffee Shop"),
            ),
        )
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = FakeCategoryRuleRepository(),
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            coffee,Coffee Shop,coffee shop,19.00,SAR,EXPENSE,PENDING,2026-02-14,CAT-COFFEE
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("coffee")?.categoryId).isEqualTo("cat-coffee")
    }

    @Test
    fun `importer reports actionable skip reasons`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "known", sourceRefId = "inbox-known", merchant = "Coffee Shop"),
            ),
        )
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = FakeCategoryRuleRepository(),
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            known,Coffee Shop,coffee shop,19.00,SAR,EXPENSE,PENDING,2026-02-14,cat-missing
            missing,Other Shop,other shop,99.00,SAR,EXPENSE,PENDING,2026-02-14,cat-coffee
            broken
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(
            MerchantBulkImportResult.Done(
                updated = 0,
                rulesAdded = 0,
                skipped = 3,
                skipSummary = MerchantBulkImportSkipSummary(
                    malformedRows = 1,
                    unknownCategories = 1,
                    missingTransactions = 1,
                ),
            ),
        )
        assertThat(repo.get("known")?.categoryId).isNull()
        assertThat(repo.get("known")?.status).isEqualTo(TxStatus.PENDING)
    }

    @Test
    fun `importer accepts csv code block copied from ai response`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "coffee", sourceRefId = "inbox-coffee", merchant = "Coffee Shop"),
            ),
        )
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = FakeCategoryRuleRepository(),
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val csv = """
            Here is the completed CSV.

            ```csv
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            coffee,Coffee Shop,coffee shop,19.00,SAR,EXPENSE,PENDING,2026-02-14,cat-coffee
            ```
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(MerchantBulkImportResult.Done(updated = 1, rulesAdded = 1, skipped = 0))
        assertThat(repo.get("coffee")?.categoryId).isEqualTo("cat-coffee")
        assertThat(repo.get("coffee")?.status).isEqualTo(TxStatus.PENDING)
    }

    @Test
    fun `importer does not propagate or train category across mixed transaction types`() = runTest {
        val repo = FakeTransactionRepository(
            listOf(
                tx(id = "merchant-expense", sourceRefId = "inbox-1", merchant = "Acme", amount = "25.00"),
                tx(
                    id = "merchant-income",
                    sourceRefId = "inbox-2",
                    merchant = "Acme",
                    amount = "100.00",
                    type = TxType.INCOME,
                ),
            ),
        )
        val rules = FakeCategoryRuleRepository()
        val importer = MerchantBulkImporter(
            transactions = repo,
            rules = rules,
            categories = FakeCategoryRepository(listOf(homeCategory(), salaryCategory())),
            clock = FixedClock,
        )
        val csv = """
            id,merchant,merchant_normalized,amount,currency,type,status,date,category_id
            merchant-expense,Acme,acme,25.00,SAR,EXPENSE,PENDING,2026-02-14,cat-home-maintenance
            merchant-income,Acme,acme,100.00,SAR,INCOME,PENDING,2026-02-14,
        """.trimIndent()

        val result = importer.importCategorizations(ByteArrayInputStream(csv.toByteArray()))

        assertThat(result).isEqualTo(
            MerchantBulkImportResult.Done(
                updated = 1,
                rulesAdded = 0,
                skipped = 1,
                skipSummary = MerchantBulkImportSkipSummary(incompatibleCategories = 1),
            ),
        )
        assertThat(repo.get("merchant-expense")?.categoryId).isEqualTo("cat-home-maintenance")
        assertThat(repo.get("merchant-expense")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(repo.get("merchant-income")?.categoryId).isNull()
        assertThat(repo.get("merchant-income")?.status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learnedRules).isEmpty()
    }

    @Test
    fun `export groups repeated merchants first and skips transfers`() = runTest {
        val rows = listOf(
            tx(id = "singleton", sourceRefId = "inbox-1", merchant = "Zed Market"),
            tx(id = "transfer", sourceRefId = "inbox-2", merchant = "Internal Transfer", type = TxType.TRANSFER),
            tx(id = "repeated-new", sourceRefId = "inbox-3", merchant = "Hemmah", date = LocalDate(2026, 3, 2)),
            tx(
                id = "categorized",
                sourceRefId = "inbox-4",
                merchant = "Starbucks",
                status = TxStatus.CONFIRMED,
                categoryId = "cat-coffee",
            ),
            tx(
                id = "repeated-old",
                sourceRefId = "inbox-5",
                merchant = "Hemmah",
                status = TxStatus.DISMISSED,
                date = LocalDate(2026, 3, 1),
            ),
            tx(
                id = "confirmed-empty",
                sourceRefId = "inbox-6",
                merchant = "BARNS",
                status = TxStatus.CONFIRMED,
            ),
        )
        val out = ByteArrayOutputStream()

        val exported = MerchantBulkExporter(
            transactions = FakeTransactionRepository(rows),
            categories = FakeCategoryRepository(
                listOf(
                    homeCategory(),
                    coffeeCategory(),
                    salaryCategory(),
                    coffeeCategory().copy(id = "cat-archived", archived = true),
                ),
            ),
            smsAudit = FakeSmsAuditRepository(),
        ).exportUncategorized(out, MerchantBulkExportMode.FULL_CONTEXT)

        assertThat(exported).isEqualTo(com.athar.core.domain.repo.MerchantBulkExportResult.Done(rows = 4))
        val lines = out.toString(Charsets.UTF_8).trim().lines()
        val header = lines.first().split(",")
        val merchantIdx = header.indexOf("merchant")
        val groupCountIdx = header.indexOf("merchant_group_count")
        val groupRankIdx = header.indexOf("merchant_group_rank")
        val groupShareIdx = header.indexOf("merchant_group_share_permille")
        val groupCumulativeIdx = header.indexOf("merchant_group_cumulative_share_permille")
        val optionsIdx = header.indexOf("category_options")
        val dataRows = lines.drop(1).map { it.split(",") }

        assertThat(dataRows.map { it[merchantIdx] })
            .containsExactly("Hemmah", "Hemmah", "BARNS", "Zed Market")
            .inOrder()
        assertThat(dataRows.map { it[groupCountIdx] })
            .containsExactly("2", "2", "1", "1")
            .inOrder()
        assertThat(dataRows.map { it[groupRankIdx] })
            .containsExactly("1", "1", "2", "3")
            .inOrder()
        assertThat(dataRows.map { it[groupShareIdx] })
            .containsExactly("500", "500", "250", "250")
            .inOrder()
        assertThat(dataRows.map { it[groupCumulativeIdx] })
            .containsExactly("500", "500", "750", "1000")
            .inOrder()
        assertThat(dataRows.first()[optionsIdx]).contains("cat-home-maintenance=Home maintenance")
        assertThat(dataRows.first()[optionsIdx]).contains("cat-coffee=Coffee")
        assertThat(dataRows.first()[optionsIdx]).doesNotContain("cat-salary")
        assertThat(dataRows.first()[optionsIdx]).doesNotContain("cat-archived")
        assertThat(out.toString(Charsets.UTF_8)).doesNotContain("Internal Transfer")
        assertThat(out.toString(Charsets.UTF_8)).doesNotContain("Starbucks")
    }

    @Test
    fun `export does not group generic merchant names as repeated cleanup targets`() = runTest {
        val rows = listOf(
            tx(id = "generic-1", sourceRefId = "inbox-1", merchant = "Payment", date = LocalDate(2026, 3, 4)),
            tx(id = "generic-2", sourceRefId = "inbox-2", merchant = "Payment", date = LocalDate(2026, 3, 3)),
            tx(id = "arabic-generic-1", sourceRefId = "inbox-5", merchant = "كاش", date = LocalDate(2026, 3, 6)),
            tx(id = "arabic-generic-2", sourceRefId = "inbox-6", merchant = "كاش", date = LocalDate(2026, 3, 5)),
            tx(id = "specific-1", sourceRefId = "inbox-3", merchant = "Hemmah", date = LocalDate(2026, 3, 2)),
            tx(id = "specific-2", sourceRefId = "inbox-4", merchant = "Hemmah", date = LocalDate(2026, 3, 1)),
        )
        val out = ByteArrayOutputStream()

        val exported = MerchantBulkExporter(
            transactions = FakeTransactionRepository(rows),
            categories = FakeCategoryRepository(listOf(homeCategory(), coffeeCategory())),
            smsAudit = FakeSmsAuditRepository(),
        ).exportUncategorized(out, MerchantBulkExportMode.FULL_CONTEXT)

        assertThat(exported).isEqualTo(com.athar.core.domain.repo.MerchantBulkExportResult.Done(rows = 6))
        val lines = out.toString(Charsets.UTF_8).trim().lines()
        val header = lines.first().split(",")
        val idIdx = header.indexOf("id")
        val merchantIdx = header.indexOf("merchant")
        val groupCountIdx = header.indexOf("merchant_group_count")
        val groupRankIdx = header.indexOf("merchant_group_rank")
        val dataRows = lines.drop(1).map { it.split(",") }
        val rowsById = dataRows.associateBy { it[idIdx] }

        assertThat(dataRows.take(2).map { it[merchantIdx] })
            .containsExactly("Hemmah", "Hemmah")
            .inOrder()
        assertThat(listOf(rowsById.getValue("specific-1")[groupCountIdx], rowsById.getValue("specific-2")[groupCountIdx]))
            .containsExactly("2", "2")
            .inOrder()
        val genericIds = listOf("generic-1", "generic-2", "arabic-generic-1", "arabic-generic-2")
        assertThat(genericIds.map { rowsById.getValue(it)[groupCountIdx] }).containsExactly("1", "1", "1", "1")
        assertThat(genericIds.map { rowsById.getValue(it)[groupRankIdx] }.toSet()).hasSize(4)
    }

    @Test
    fun `export uses income category options for income rows`() = runTest {
        val out = ByteArrayOutputStream()

        val exported = MerchantBulkExporter(
            transactions = FakeTransactionRepository(
                listOf(
                    tx(
                        id = "income",
                        sourceRefId = "inbox-income",
                        merchant = "Monthly Profit",
                        type = TxType.INCOME,
                    ),
                ),
            ),
            categories = FakeCategoryRepository(listOf(coffeeCategory(), salaryCategory())),
            smsAudit = FakeSmsAuditRepository(),
        ).exportUncategorized(out, MerchantBulkExportMode.FULL_CONTEXT)

        assertThat(exported).isEqualTo(com.athar.core.domain.repo.MerchantBulkExportResult.Done(rows = 1))
        val lines = out.toString(Charsets.UTF_8).trim().lines()
        val header = lines.first().split(",")
        val options = lines.drop(1).single().split(",")[header.indexOf("category_options")]

        assertThat(options).contains("cat-salary=Salary / راتب")
        assertThat(options).doesNotContain("cat-coffee")
    }

    private fun tx(
        id: String,
        sourceRefId: String?,
        merchant: String = "BARNS",
        amount: String = "19.00",
        notes: String? = null,
        type: TxType = TxType.EXPENSE,
        status: TxStatus = TxStatus.PENDING,
        categoryId: String? = null,
        date: LocalDate = LocalDate(2026, 2, 14),
    ): Transaction = Transaction(
        id = id,
        accountId = "acc-1",
        type = type,
        amount = Money.of(amount),
        date = date,
        occurredAt = FixedInstant,
        merchant = merchant,
        merchantNormalized = merchant.lowercase().trim(),
        categoryId = categoryId,
        notes = notes,
        source = IngestSource.SMS,
        sourceRefId = sourceRefId,
        status = status,
        confidence = 0.8f,
        createdAt = FixedInstant,
        updatedAt = FixedInstant,
    )

    private fun coffeeCategory(): Category = Category(
        id = "cat-coffee",
        name = "Coffee",
        nameAr = "قهوة",
        kind = CategoryKind.EXPENSE,
        icon = null,
        monthlyTarget = null,
        archived = false,
        sortOrder = 0,
    )

    private fun homeCategory(): Category = Category(
        id = "cat-home-maintenance",
        name = "Home maintenance",
        nameAr = "صيانة منزل",
        kind = CategoryKind.EXPENSE,
        icon = null,
        monthlyTarget = null,
        archived = false,
        sortOrder = 1,
    )

    private fun salaryCategory(): Category = Category(
        id = "cat-salary",
        name = "Salary",
        nameAr = "راتب",
        kind = CategoryKind.INCOME,
        icon = null,
        monthlyTarget = null,
        archived = false,
        sortOrder = 100,
    )

    private class FakeTransactionRepository(initial: List<Transaction>) : TransactionRepository {
        private val rows = MutableStateFlow(initial.associateBy { it.id })

        override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> = observeAll()
        override fun observePending(): Flow<List<Transaction>> =
            flowOf(rows.value.values.filter { it.status == TxStatus.PENDING })

        override fun observeAll(): Flow<List<Transaction>> = flowOf(rows.value.values.toList())
        override suspend fun get(id: String): Transaction? = rows.value[id]
        override suspend fun upsertIngest(transaction: Transaction): String =
            error("Ingestion is outside this UI or import test fixture")

        override suspend fun upsert(transaction: Transaction) {
            rows.value = rows.value + (transaction.id to transaction)
        }

        override suspend fun delete(id: String) = unsupported()
        override suspend fun setStatus(id: String, status: TxStatus) = unsupported()
        override suspend fun clearPending(): Int = unsupported()
        override suspend fun confirmAllConfident(minConfidence: Float): Int = unsupported()
        override suspend fun dismissAllLowConfidence(maxConfidence: Float): Int = unsupported()
        override suspend fun dismissAllPending(): Int = unsupported()
        override suspend fun recoverDismissedToPending(): Int = unsupported()
        override suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int = unsupported()

    }

    private class FakeCategoryRepository(private val rows: List<Category>) : CategoryRepository {
        override fun observeAll(kind: CategoryKind?, includeArchived: Boolean): Flow<List<Category>> =
            flowOf(
                rows.filter { category ->
                    (kind == null || category.kind == kind) &&
                        (includeArchived || !category.archived)
                },
            )

        override suspend fun get(id: String): Category? = rows.firstOrNull { it.id == id }
        override suspend fun upsert(category: Category) = unsupported()
        override suspend fun archive(id: String) = unsupported()
        override suspend fun reorder(ids: List<String>) = unsupported()
    }

    private class FakeCategoryRuleRepository(
        initial: List<CategoryRule> = emptyList(),
    ) : CategoryRuleRepository {
        val learnedRules = initial.toMutableList()

        override fun observeAll(): Flow<List<CategoryRule>> = flowOf(learnedRules.toList())
        override suspend fun findMatching(merchantNormalized: String): List<CategoryRule> = emptyList()
        override suspend fun upsert(rule: CategoryRule) {
            learnedRules.removeAll { it.id == rule.id }
            learnedRules += rule
        }
        override suspend fun delete(id: String) {
            learnedRules.removeAll { it.id == id }
        }
        override suspend fun learnFromCorrection(
            merchantNormalized: String,
            categoryId: String,
            patternType: PatternType,
        ): CategoryRule {
            val rule = CategoryRule(
                id = "learned-$merchantNormalized-$categoryId",
                pattern = merchantNormalized,
                patternType = patternType,
                categoryId = categoryId,
                priority = 200,
                learnedFromUser = true,
                createdAt = FixedInstant,
            )
            learnedRules += rule
            return rule
        }
    }

    private class FakeSmsAuditRepository(
        private val rows: List<SmsAuditEntry> = emptyList(),
    ) : SmsAuditRepository {
        override fun observeAll(): Flow<List<SmsAuditEntry>> = flowOf(rows)
        override fun observeByStatus(status: SmsParseStatus): Flow<List<SmsAuditEntry>> =
            flowOf(rows.filter { it.status == status })

        override suspend fun record(event: RawIngestEvent): String = unsupported()

        override suspend fun updateParseResult(
            auditId: String,
            status: SmsParseStatus,
            parsedTransactionId: String?,
            error: String?,
        ) = unsupported()
    }

    private companion object {
        val FixedInstant: Instant = Instant.parse("2026-02-14T12:00:00Z")
        val FixedClock = object : Clock {
            override fun now(): Instant = FixedInstant
        }

        fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")

        fun learnedRule(
            id: String,
            pattern: String,
            categoryId: String,
            patternType: PatternType,
            learnedFromUser: Boolean = true,
            priority: Int = 200,
        ): CategoryRule = CategoryRule(
            id = id,
            pattern = pattern,
            patternType = patternType,
            categoryId = categoryId,
            priority = priority,
            learnedFromUser = learnedFromUser,
            createdAt = FixedInstant,
        )
    }
}
