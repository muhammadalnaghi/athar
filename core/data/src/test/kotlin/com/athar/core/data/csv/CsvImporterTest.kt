package com.athar.core.data.csv

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CsvImportColumnMapping
import com.athar.core.domain.repo.CsvImportPreviewResult
import com.athar.core.domain.repo.CsvImportResult
import com.athar.core.domain.repo.CsvImportRowDecision
import com.athar.core.domain.repo.CsvImportRowEdit
import com.athar.core.domain.repo.TransactionRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CsvImporterTest {

    @Test
    fun `preview reports rows and detected columns without committing`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementCsv.toByteArray()))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(2)
        assertThat(preview.skipped).isEqualTo(1)
        assertThat(preview.columns.date).isEqualTo("Date")
        assertThat(preview.columns.merchant).isEqualTo("Description")
        assertThat(preview.columns.debit).isEqualTo("Debit")
        assertThat(preview.columns.credit).isEqualTo("Credit")
        assertThat(preview.sampleRows.map { it.rowNumber }).containsExactly(2, 4).inOrder()
        assertThat(preview.sampleRows.first().merchant).isEqualTo("Starbucks")
        assertThat(preview.sampleRows.first().type).isEqualTo(TxType.EXPENSE)
        assertThat(preview.sampleRows.first().category).isEqualTo("Coffee")
        assertThat(preview.currencySummaries.map { it.currency }).containsExactly("SAR")
        assertThat(preview.currencySummaries.single().rows).isEqualTo(2)
        assertThat(preview.currencySummaries.single().expenseTotal.compareTo(BigDecimal("18.50"))).isEqualTo(0)
        assertThat(preview.currencySummaries.single().incomeTotal.compareTo(BigDecimal("1000.00"))).isEqualTo(0)
        assertThat(preview.skippedRows.single().rowNumber).isEqualTo(3)
        assertThat(transactions.upserts).isEmpty()
    }

    @Test
    fun `import commits the same importable and skipped counts as preview`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.import(ByteArrayInputStream(statementCsv.toByteArray()))

        assertThat(result).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        assertThat(transactions.upserts).hasSize(2)
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Starbucks", "Salary").inOrder()
        assertThat(transactions.upserts.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
    }

    @Test
    fun `row decisions exclude selected rows from preview and import`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val decisions = listOf(CsvImportRowDecision(rowNumber = 2, shouldImport = false))

        val result = importer.preview(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            rowDecisions = decisions,
        )
        val imported = importer.import(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            rowDecisions = decisions,
        )

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(1)
        assertThat(preview.skipped).isEqualTo(2)
        assertThat(preview.sampleRows.map { it.rowNumber }).containsExactly(2, 4).inOrder()
        assertThat(preview.sampleRows.first().included).isFalse()
        assertThat(preview.skippedRows.map { it.reason }).contains("Excluded from import")
        assertThat(preview.currencySummaries.single().expenseTotal.compareTo(BigDecimal.ZERO)).isEqualTo(0)
        assertThat(preview.currencySummaries.single().incomeTotal.compareTo(BigDecimal("1000.00"))).isEqualTo(0)
        assertThat(imported).isEqualTo(CsvImportResult.Done(imported = 1, skipped = 2))
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Salary")
    }

    @Test
    fun `preview summarizes importable rows by currency and type`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementMixedCurrencyCsv.toByteArray()))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(4)
        assertThat(preview.currencySummaries.map { it.currency }).containsExactly("SAR", "USD").inOrder()
        val sar = preview.currencySummaries.first { it.currency == "SAR" }
        assertThat(sar.rows).isEqualTo(2)
        assertThat(sar.expenseTotal.compareTo(BigDecimal("2500.00"))).isEqualTo(0)
        assertThat(sar.transferTotal.compareTo(BigDecimal("500.00"))).isEqualTo(0)
        val usd = preview.currencySummaries.first { it.currency == "USD" }
        assertThat(usd.rows).isEqualTo(2)
        assertThat(usd.expenseTotal.compareTo(BigDecimal("18.50"))).isEqualTo(0)
        assertThat(usd.incomeTotal.compareTo(BigDecimal("1000.00"))).isEqualTo(0)
        assertThat(transactions.upserts).isEmpty()
    }

    @Test
    fun `row edits override preview and imported transaction fields`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val edits = listOf(
            CsvImportRowEdit(
                rowNumber = 2,
                date = "2026-06-05",
                merchant = "Corrected Coffee",
                amount = "20.00",
                currency = "USD",
                type = TxType.TRANSFER,
                category = "",
                notes = "Manual preview fix",
            ),
        )

        val result = importer.preview(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            rowEdits = edits,
        )
        val imported = importer.import(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            rowEdits = edits,
        )

        val preview = (result as CsvImportPreviewResult.Done).preview
        val editedRow = preview.sampleRows.first()
        assertThat(editedRow.edited).isTrue()
        assertThat(editedRow.date).isEqualTo("2026-06-05")
        assertThat(editedRow.merchant).isEqualTo("Corrected Coffee")
        assertThat(editedRow.amount).isEqualTo("20.00")
        assertThat(editedRow.currency).isEqualTo("USD")
        assertThat(editedRow.type).isEqualTo(TxType.TRANSFER)
        assertThat(editedRow.category).isNull()
        assertThat(editedRow.notes).isEqualTo("Manual preview fix")
        assertThat(imported).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        val tx = transactions.upserts.first()
        assertThat(tx.date).isEqualTo(LocalDate(2026, 6, 5))
        assertThat(tx.merchant).isEqualTo("Corrected Coffee")
        assertThat(tx.merchantNormalized).isEqualTo("corrected coffee")
        assertThat(tx.amount.amount.compareTo(BigDecimal("20.00"))).isEqualTo(0)
        assertThat(tx.amount.currency).isEqualTo("USD")
        assertThat(tx.type).isEqualTo(TxType.TRANSFER)
        assertThat(tx.categoryId).isNull()
        assertThat(tx.notes).isEqualTo("Manual preview fix")
    }

    @Test
    fun `invalid row edits skip the edited row conservatively`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.preview(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            rowEdits = listOf(CsvImportRowEdit(rowNumber = 2, amount = "not-money")),
        )
        val imported = importer.import(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            rowEdits = listOf(CsvImportRowEdit(rowNumber = 2, amount = "not-money")),
        )

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(1)
        assertThat(preview.skipped).isEqualTo(2)
        assertThat(preview.skippedRows.map { it.reason }).contains("Invalid edited amount")
        assertThat(imported).isEqualTo(CsvImportResult.Done(imported = 1, skipped = 2))
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Salary")
    }

    @Test
    fun `import assigns statement rows to selected account`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        importer.import(ByteArrayInputStream(statementCsv.toByteArray()), accountId = "acc-checking")

        assertThat(transactions.upserts).hasSize(2)
        assertThat(transactions.upserts.map { it.accountId }).containsExactly("acc-checking", "acc-checking")
    }

    @Test
    fun `row account edits override preview and imported transaction accounts`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )
        val edits = listOf(CsvImportRowEdit(rowNumber = 2, accountId = "acc-checking"))

        val result = importer.preview(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            accountId = "acc-cash",
            rowEdits = edits,
        )
        val imported = importer.import(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            accountId = "acc-cash",
            rowEdits = edits,
        )

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.sampleRows.map { it.accountId }).containsExactly("acc-checking", "acc-cash").inOrder()
        assertThat(preview.sampleRows.first().edited).isTrue()
        assertThat(imported).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        assertThat(transactions.upserts.map { it.accountId }).containsExactly("acc-checking", "acc-cash").inOrder()
    }

    @Test
    fun `row account edits use the edited account for duplicate detection`() = runTest {
        val transactions = FakeTransactionRepository(
            initialRows = listOf(existingImportedStarbucks(accountId = "acc-checking")),
        )
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.preview(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            accountId = "acc-cash",
            rowEdits = listOf(CsvImportRowEdit(rowNumber = 2, accountId = "acc-checking")),
        )
        val imported = importer.import(
            input = ByteArrayInputStream(statementCsv.toByteArray()),
            accountId = "acc-cash",
            rowEdits = listOf(CsvImportRowEdit(rowNumber = 2, accountId = "acc-checking")),
        )

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(1)
        assertThat(preview.skipped).isEqualTo(2)
        assertThat(preview.skippedRows.map { it.reason }).contains("Already imported")
        assertThat(imported).isEqualTo(CsvImportResult.Done(imported = 1, skipped = 2))
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Salary")
        assertThat(transactions.upserts.single().accountId).isEqualTo("acc-cash")
    }

    @Test
    fun `preview supports semicolon delimited statement csv`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementSemicolonCsv.toByteArray()))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(2)
        assertThat(preview.skipped).isEqualTo(1)
        assertThat(preview.columns.date).isEqualTo("Date")
        assertThat(preview.columns.merchant).isEqualTo("Description")
        assertThat(preview.columns.debit).isEqualTo("Debit")
        assertThat(preview.sampleRows.first().amount).isEqualTo("18.50")
        assertThat(preview.sampleRows.first().currency).isEqualTo("EUR")
        assertThat(preview.sampleRows.first().category).isEqualTo("Coffee")
        assertThat(preview.skippedRows.single().rowNumber).isEqualTo(3)
        assertThat(transactions.upserts).isEmpty()
    }

    @Test
    fun `import supports tab delimited statement csv`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val result = importer.import(ByteArrayInputStream(statementTabCsv.toByteArray()))

        assertThat(result).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 0))
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Coffee Shop", "Salary").inOrder()
        assertThat(transactions.upserts.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
        assertThat(transactions.upserts.map { it.amount.currency }).containsExactly("GBP", "GBP").inOrder()
    }

    @Test
    fun `preview supports positive amount with debit credit indicator column`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementIndicatorCsv.toByteArray()))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(2)
        assertThat(preview.skipped).isEqualTo(0)
        assertThat(preview.columns.amount).isEqualTo("Amount")
        assertThat(preview.columns.type).isEqualTo("D/C")
        assertThat(preview.sampleRows.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
        assertThat(preview.sampleRows.map { it.amount }).containsExactly("42.00", "15.25").inOrder()
    }

    @Test
    fun `preview requests mapping for unknown csv headers and imports with manual mapping`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val first = importer.preview(ByteArrayInputStream(statementUnknownHeadersCsv.toByteArray()))

        val required = first as CsvImportPreviewResult.MappingRequired
        assertThat(required.columns).containsExactly("Booked", "Counterparty text", "Out", "In", "ISO", "Bucket")
        assertThat(required.reason).contains("Map date")

        val mapping = CsvImportColumnMapping(
            date = "Booked",
            merchant = "Counterparty text",
            debit = "Out",
            credit = "In",
            currency = "ISO",
            category = "Bucket",
        )
        val preview = importer.preview(
            input = ByteArrayInputStream(statementUnknownHeadersCsv.toByteArray()),
            mapping = mapping,
        ) as CsvImportPreviewResult.Done
        val imported = importer.import(
            input = ByteArrayInputStream(statementUnknownHeadersCsv.toByteArray()),
            mapping = mapping,
        )

        assertThat(preview.preview.importable).isEqualTo(2)
        assertThat(preview.preview.availableColumns).containsExactly(
            "Booked",
            "Counterparty text",
            "Out",
            "In",
            "ISO",
            "Bucket",
        ).inOrder()
        assertThat(preview.preview.columns.date).isEqualTo("Booked")
        assertThat(preview.preview.columns.merchant).isEqualTo("Counterparty text")
        assertThat(preview.preview.columns.debit).isEqualTo("Out")
        assertThat(preview.preview.columns.credit).isEqualTo("In")
        assertThat(preview.preview.sampleRows.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
        assertThat(imported).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 0))
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Unknown Coffee", "Payroll").inOrder()
        assertThat(transactions.upserts.map { it.amount.currency }).containsExactly("USD", "USD").inOrder()
    }

    @Test
    fun `preview reports ofx transactions without committing`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementOfx.toByteArray()))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(2)
        assertThat(preview.skipped).isEqualTo(1)
        assertThat(preview.columns.date).isEqualTo("OFX DTPOSTED")
        assertThat(preview.columns.amount).isEqualTo("OFX TRNAMT")
        assertThat(preview.sampleRows.map { it.rowNumber }).containsExactly(1, 2).inOrder()
        assertThat(preview.sampleRows.first().date).isEqualTo("2026-06-01")
        assertThat(preview.sampleRows.first().merchant).isEqualTo("Starbucks")
        assertThat(preview.sampleRows.first().amount).isEqualTo("18.50")
        assertThat(preview.sampleRows.first().currency).isEqualTo("USD")
        assertThat(preview.sampleRows.first().type).isEqualTo(TxType.EXPENSE)
        assertThat(preview.sampleRows[1].type).isEqualTo(TxType.INCOME)
        assertThat(preview.skippedRows.single().rowNumber).isEqualTo(3)
        assertThat(transactions.upserts).isEmpty()
    }

    @Test
    fun `import commits ofx transactions through same preview path`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val result = importer.import(ByteArrayInputStream(statementOfx.toByteArray()))

        assertThat(result).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        assertThat(transactions.upserts).hasSize(2)
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Starbucks", "Acme Payroll").inOrder()
        assertThat(transactions.upserts.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
        assertThat(transactions.upserts.map { it.amount.amount })
            .containsExactly(BigDecimal("18.50"), BigDecimal("1000.00"))
            .inOrder()
        assertThat(transactions.upserts.map { it.amount.currency }).containsExactly("USD", "USD").inOrder()
        val sourceRefs = transactions.upserts.mapNotNull { it.sourceRefId }
        assertThat(sourceRefs).hasSize(2)
        assertThat(sourceRefs).containsNoDuplicates()
        assertThat(sourceRefs.all { it.startsWith("import:ofx:") }).isTrue()
    }

    @Test
    fun `preview reports mt940 transactions without committing`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementMt940.toByteArray()))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(2)
        assertThat(preview.skipped).isEqualTo(1)
        assertThat(preview.columns.date).isEqualTo("MT940 :61: date")
        assertThat(preview.columns.merchant).isEqualTo("MT940 :86:")
        assertThat(preview.columns.amount).isEqualTo("MT940 :61: amount")
        assertThat(preview.sampleRows.map { it.rowNumber }).containsExactly(1, 2).inOrder()
        assertThat(preview.sampleRows.first().date).isEqualTo("2026-06-01")
        assertThat(preview.sampleRows.first().merchant).isEqualTo("Starbucks")
        assertThat(preview.sampleRows.first().amount).isEqualTo("18.50")
        assertThat(preview.sampleRows.first().currency).isEqualTo("USD")
        assertThat(preview.sampleRows.first().type).isEqualTo(TxType.EXPENSE)
        assertThat(preview.sampleRows[1].type).isEqualTo(TxType.INCOME)
        assertThat(preview.skippedRows.single().rowNumber).isEqualTo(3)
        assertThat(transactions.upserts).isEmpty()
    }

    @Test
    fun `import commits mt940 transactions through same preview path`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val result = importer.import(ByteArrayInputStream(statementMt940.toByteArray()))

        assertThat(result).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        assertThat(transactions.upserts).hasSize(2)
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Starbucks", "Acme Payroll").inOrder()
        assertThat(transactions.upserts.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
        assertThat(transactions.upserts.map { it.amount.amount })
            .containsExactly(BigDecimal("18.50"), BigDecimal("1000.00"))
            .inOrder()
        assertThat(transactions.upserts.map { it.amount.currency }).containsExactly("USD", "USD").inOrder()
        val sourceRefs = transactions.upserts.mapNotNull { it.sourceRefId }
        assertThat(sourceRefs).hasSize(2)
        assertThat(sourceRefs).containsNoDuplicates()
        assertThat(sourceRefs.all { it.startsWith("import:mt940:") }).isTrue()
    }

    @Test
    fun `preview supports xlsx statement workbook without committing`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementXlsx))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(2)
        assertThat(preview.skipped).isEqualTo(1)
        assertThat(preview.columns.date).isEqualTo("XLSX Date")
        assertThat(preview.columns.merchant).isEqualTo("XLSX Description")
        assertThat(preview.columns.debit).isEqualTo("XLSX Debit")
        assertThat(preview.columns.credit).isEqualTo("XLSX Credit")
        assertThat(preview.sampleRows.map { it.rowNumber }).containsExactly(1, 3).inOrder()
        assertThat(preview.sampleRows.first().merchant).isEqualTo("Starbucks")
        assertThat(preview.sampleRows.first().type).isEqualTo(TxType.EXPENSE)
        assertThat(preview.sampleRows.first().category).isEqualTo("Coffee")
        assertThat(preview.currencySummaries.single().currency).isEqualTo("SAR")
        assertThat(preview.currencySummaries.single().expenseTotal.compareTo(BigDecimal("18.50"))).isEqualTo(0)
        assertThat(preview.currencySummaries.single().incomeTotal.compareTo(BigDecimal("1000.00"))).isEqualTo(0)
        assertThat(preview.skippedRows.single().rowNumber).isEqualTo(2)
        assertThat(transactions.upserts).isEmpty()
    }

    @Test
    fun `import commits xlsx transactions through duplicate-safe source refs`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(emptyList()),
            clock = FixedClock,
        )

        val first = importer.import(ByteArrayInputStream(statementXlsx), accountId = "acc-checking")
        val secondPreview = importer.preview(ByteArrayInputStream(statementXlsx), accountId = "acc-checking")

        assertThat(first).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        assertThat(transactions.upserts).hasSize(2)
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Starbucks", "Salary").inOrder()
        assertThat(transactions.upserts.mapNotNull { it.sourceRefId }.all { it.startsWith("import:xlsx:") }).isTrue()
        val preview = (secondPreview as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(0)
        assertThat(preview.skippedRows.map { it.reason })
            .containsExactly("Already imported", "Unparseable XLSX row", "Already imported")
            .inOrder()
    }

    @Test
    fun `xlsx tmoap-style expenses and income sheets preserve positive amount direction`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(tmoapStyleXlsx))

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(2)
        assertThat(preview.sampleRows.map { it.merchant }).containsExactly("Starbucks", "Salary").inOrder()
        assertThat(preview.sampleRows.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
        assertThat(preview.sampleRows.map { it.amount }).containsExactly("18.50", "1000.00").inOrder()
    }

    @Test
    fun `xlsx unknown headers request mapping and then import with manual mapping`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val first = importer.preview(ByteArrayInputStream(statementUnknownHeadersXlsx))

        val required = first as CsvImportPreviewResult.MappingRequired
        assertThat(required.columns).containsExactly("Booked", "Counterparty text", "Out", "In", "ISO", "Bucket")

        val mapping = CsvImportColumnMapping(
            date = "Booked",
            merchant = "Counterparty text",
            debit = "Out",
            credit = "In",
            currency = "ISO",
            category = "Bucket",
        )
        val preview = importer.preview(
            input = ByteArrayInputStream(statementUnknownHeadersXlsx),
            mapping = mapping,
        ) as CsvImportPreviewResult.Done
        val imported = importer.import(
            input = ByteArrayInputStream(statementUnknownHeadersXlsx),
            mapping = mapping,
        )

        assertThat(preview.preview.importable).isEqualTo(2)
        assertThat(preview.preview.availableColumns).containsExactly(
            "Booked",
            "Counterparty text",
            "Out",
            "In",
            "ISO",
            "Bucket",
        ).inOrder()
        assertThat(preview.preview.sampleRows.map { it.type }).containsExactly(TxType.EXPENSE, TxType.INCOME).inOrder()
        assertThat(imported).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 0))
        assertThat(transactions.upserts.map { it.merchant }).containsExactly("Unknown Coffee", "Payroll").inOrder()
        assertThat(transactions.upserts.map { it.amount.currency }).containsExactly("USD", "USD").inOrder()
    }

    @Test
    fun `statement import skips rows already imported for same account`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val first = importer.import(ByteArrayInputStream(statementCsv.toByteArray()), accountId = "acc-checking")
        val secondPreview = importer.preview(ByteArrayInputStream(statementCsv.toByteArray()), accountId = "acc-checking")
        val secondImport = importer.import(ByteArrayInputStream(statementCsv.toByteArray()), accountId = "acc-checking")

        assertThat(first).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        val preview = (secondPreview as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(0)
        assertThat(preview.skipped).isEqualTo(3)
        assertThat(preview.skippedRows.map { it.rowNumber }).containsExactly(2, 3, 4).inOrder()
        assertThat(preview.skippedRows.map { it.reason })
            .containsExactly("Already imported", "Unparseable row", "Already imported")
            .inOrder()
        assertThat(secondImport).isEqualTo(CsvImportResult.Done(imported = 0, skipped = 3))
        assertThat(transactions.upserts).hasSize(2)
    }

    @Test
    fun `statement import skips legacy imported csv rows by content`() = runTest {
        val transactions = FakeTransactionRepository(
            initialRows = listOf(
                importedTx(
                    accountId = "acc-checking",
                    merchant = "Starbucks",
                    amount = "18.50",
                    type = TxType.EXPENSE,
                    date = LocalDate(2026, 6, 1),
                    sourceRefId = "csv-row-2",
                ),
                importedTx(
                    accountId = "acc-checking",
                    merchant = "Salary",
                    amount = "1000.00",
                    type = TxType.INCOME,
                    date = LocalDate(2026, 6, 2),
                    sourceRefId = "csv-row-4",
                ),
            ),
        )
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        val result = importer.preview(ByteArrayInputStream(statementCsv.toByteArray()), accountId = "acc-checking")

        val preview = (result as CsvImportPreviewResult.Done).preview
        assertThat(preview.importable).isEqualTo(0)
        assertThat(preview.skippedRows.map { it.reason })
            .containsExactly("Already imported", "Unparseable row", "Already imported")
            .inOrder()
    }

    @Test
    fun `statement import resolves tmoap category labels by transaction type`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(tmoapCategories()),
            clock = FixedClock,
        )

        val result = importer.import(ByteArrayInputStream(tmoapCategoryCsv.toByteArray()))

        assertThat(result).isEqualTo(CsvImportResult.Done(imported = 11, skipped = 0))
        assertThat(transactions.upserts.map { it.categoryId }).containsExactly(
            "cat-condo-fees",
            "cat-work-expense",
            "cat-public-transport",
            "cat-wife-allowance",
            "cat-salary",
            "cat-side-income",
            "cat-tax-refund",
            "cat-reimbursements",
            "cat-bonus",
            "cat-other-income",
            "cat-other-expense",
        ).inOrder()
    }

    @Test
    fun `statement import source refs are account scoped`() = runTest {
        val transactions = FakeTransactionRepository()
        val importer = CsvImporter(
            transactions = transactions,
            categories = FakeCategoryRepository(listOf(coffeeCategory())),
            clock = FixedClock,
        )

        importer.import(ByteArrayInputStream(statementCsv.toByteArray()), accountId = "acc-checking")
        val checkingRefs = transactions.upserts.map { it.sourceRefId }

        val second = importer.import(ByteArrayInputStream(statementCsv.toByteArray()), accountId = "acc-savings")
        val savingsRefs = transactions.upserts.drop(2).map { it.sourceRefId }

        assertThat(second).isEqualTo(CsvImportResult.Done(imported = 2, skipped = 1))
        assertThat(transactions.upserts).hasSize(4)
        assertThat(checkingRefs.intersect(savingsRefs.toSet())).isEmpty()
        assertThat((checkingRefs + savingsRefs).filterNotNull().all { it.startsWith("import:csv:") }).isTrue()
    }

    private class FakeTransactionRepository(
        private val initialRows: List<Transaction> = emptyList(),
    ) : TransactionRepository {
        val upserts = mutableListOf<Transaction>()

        override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observePending(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<Transaction>> = flowOf(initialRows + upserts)
        override suspend fun get(id: String): Transaction? = null
        override suspend fun upsertIngest(transaction: Transaction): String =
            error("Ingestion is outside this UI or import test fixture")

        override suspend fun upsert(transaction: Transaction) {
            upserts += transaction
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

    private fun existingImportedStarbucks(accountId: String): Transaction = Transaction(
        id = "existing-starbucks-$accountId",
        accountId = accountId,
        type = TxType.EXPENSE,
        amount = Money.of(BigDecimal("18.50"), "SAR"),
        date = LocalDate(2026, 6, 1),
        occurredAt = null,
        merchant = "Starbucks",
        merchantNormalized = "starbucks",
        categoryId = null,
        notes = null,
        source = IngestSource.IMPORT,
        sourceRefId = "import:csv:existing-starbucks-$accountId",
        status = TxStatus.CONFIRMED,
        confidence = 1.0f,
        createdAt = FixedInstant,
        updatedAt = FixedInstant,
    )

    private class FakeCategoryRepository(private val rows: List<Category>) : CategoryRepository {
        override fun observeAll(kind: CategoryKind?, includeArchived: Boolean): Flow<List<Category>> = flowOf(rows)
        override suspend fun get(id: String): Category? = rows.firstOrNull { it.id == id }
        override suspend fun upsert(category: Category) = unsupported()
        override suspend fun archive(id: String) = unsupported()
        override suspend fun reorder(ids: List<String>) = unsupported()
    }

    private companion object {
        val FixedInstant: Instant = Instant.parse("2026-06-13T12:00:00Z")
        val FixedClock = object : Clock {
            override fun now(): Instant = FixedInstant
        }

        val statementCsv = """
            Date,Description,Debit,Credit,Currency,Category
            2026-06-01,Starbucks,18.50,,SAR,Coffee
            not-a-date,Broken row,9.00,,SAR,Coffee
            2026-06-02,Salary,,1000.00,SAR,
        """.trimIndent()

        val statementSemicolonCsv = """
            Date;Description;Debit;Credit;Currency;Category
            2026-06-01;Starbucks;18,50;;EUR;Coffee
            not-a-date;Broken row;9,00;;EUR;Coffee
            2026-06-02;Salary;;1000,00;EUR;
        """.trimIndent()

        val statementTabCsv = listOf(
            "Date\tNarrative\tAmount\tCurrency",
            "2026-06-01\tCoffee Shop\t-12.25\tGBP",
            "2026-06-02\tSalary\t1000.00\tGBP",
        ).joinToString("\n")

        val statementIndicatorCsv = """
            Booking Date,Narrative,Amount,D/C,Currency
            2026-06-01,Train ticket,42.00,D,GBP
            2026-06-02,Refund,15.25,C,GBP
        """.trimIndent()

        val statementMixedCurrencyCsv = """
            Date,Description,Amount,Currency,Type
            2026-06-01,Coffee,-18.50,USD,Expense
            2026-06-02,Salary,1000.00,USD,Income
            2026-06-03,Rent,-2500.00,SAR,Expense
            2026-06-04,Savings move,500.00,SAR,Transfer
        """.trimIndent()

        val statementUnknownHeadersCsv = """
            Booked,Counterparty text,Out,In,ISO,Bucket
            2026-06-01,Unknown Coffee,18.50,,USD,Coffee
            2026-06-02,Payroll,,1000.00,USD,
        """.trimIndent()

        val tmoapCategoryCsv = """
            Date,Description,Amount,Type,Category
            2026-06-01,Condo board,-500.00,Expense,Condo fees
            2026-06-02,Work parking,-30.00,Expense,Work
            2026-06-03,Bus fare,-3.00,Expense,Public transportation
            2026-06-04,Allowance,-100.00,Expense,Wife
            2026-06-05,Payroll,1000.00,Income,Job
            2026-06-06,Freelance,200.00,Income,Side project
            2026-06-07,Tax agency,150.00,Income,Tax refund
            2026-06-08,Employer,40.00,Income,Expense reimbursement
            2026-06-09,Employer,500.00,Income,Bonus
            2026-06-10,Misc income,25.00,Income,Other
            2026-06-11,Misc expense,-10.00,Expense,Other
        """.trimIndent()

        val statementOfx = """
            OFXHEADER:100
            DATA:OFXSGML
            <OFX>
            <BANKMSGSRSV1>
            <STMTTRNRS>
            <STMTRS>
            <CURDEF>USD
            <BANKTRANLIST>
            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20260601120000[-5:EST]
            <TRNAMT>-18.50
            <FITID>fit-1
            <NAME>Starbucks
            <MEMO>Card purchase
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>CREDIT
            <DTPOSTED>20260602120000
            <TRNAMT>1000.00
            <FITID>fit-2
            <NAME>Acme Payroll
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>DEBIT
            <TRNAMT>-9.00
            <NAME>Broken Row
            </STMTTRN>
            </BANKTRANLIST>
            </STMTRS>
            </STMTTRNRS>
            </BANKMSGSRSV1>
            </OFX>
        """.trimIndent()

        val statementMt940 = """
            :20:STARTUMSE
            :25:123456789
            :28C:00001/001
            :60F:C260531USD1000,00
            :61:2606010601D18,50NMSCNONREF//mt1
            :86:Starbucks
            :61:2606020602C1000,00NTRFNONREF//mt2
            :86:Acme Payroll
            :61:2606030603D9,00NMSCNONREF//mt3
            :62F:C260602USD1981,50
            -}
        """.trimIndent()

        val statementXlsx = xlsxWorkbook(
            XlsxSheet(
                name = "Checking",
                rows = listOf(
                    listOf("Date", "Description", "Debit", "Credit", "Currency", "Category"),
                    listOf("2026-06-01", "Starbucks", "18.50", "", "SAR", "Coffee"),
                    listOf("not-a-date", "Broken row", "9.00", "", "SAR", "Coffee"),
                    listOf("2026-06-02", "Salary", "", "1000.00", "SAR", ""),
                ),
            ),
        )

        val tmoapStyleXlsx = xlsxWorkbook(
            XlsxSheet(
                name = "Expenses",
                rows = listOf(
                    listOf("Date", "Vendor", "Amount", "Category", "Notes"),
                    listOf("2026-06-01", "Starbucks", "18.50", "Coffee", "Morning"),
                ),
            ),
            XlsxSheet(
                name = "Income",
                rows = listOf(
                    listOf("Date", "Vendor", "Amount", "Category", "Notes"),
                    listOf("2026-06-02", "Salary", "1000.00", "", "June payroll"),
                ),
            ),
        )

        val statementUnknownHeadersXlsx = xlsxWorkbook(
            XlsxSheet(
                name = "Statement",
                rows = listOf(
                    listOf("Booked", "Counterparty text", "Out", "In", "ISO", "Bucket"),
                    listOf("2026-06-01", "Unknown Coffee", "18.50", "", "USD", "Coffee"),
                    listOf("2026-06-02", "Payroll", "", "1000.00", "USD", ""),
                ),
            ),
        )

        fun coffeeCategory(): Category = Category(
            id = "cat-coffee",
            name = "Coffee",
            nameAr = "قهوة",
            kind = CategoryKind.EXPENSE,
            icon = null,
            monthlyTarget = null,
            archived = false,
            sortOrder = 0,
        )

        fun tmoapCategories(): List<Category> = listOf(
            category("cat-condo-fees", "Condo fees", CategoryKind.EXPENSE),
            category("cat-work-expense", "Work", CategoryKind.EXPENSE),
            category("cat-public-transport", "Public transport", CategoryKind.EXPENSE),
            category("cat-wife-allowance", "Wife allowance", CategoryKind.EXPENSE),
            category("cat-other-expense", "Other", CategoryKind.EXPENSE),
            category("cat-salary", "Salary", CategoryKind.INCOME),
            category("cat-side-income", "Side income", CategoryKind.INCOME),
            category("cat-tax-refund", "Tax refund", CategoryKind.INCOME),
            category("cat-reimbursements", "Expense reimbursement", CategoryKind.INCOME),
            category("cat-bonus", "Bonus", CategoryKind.INCOME),
            category("cat-other-income", "Other income", CategoryKind.INCOME),
        )

        fun category(
            id: String,
            name: String,
            kind: CategoryKind,
        ): Category = Category(
            id = id,
            name = name,
            nameAr = name,
            kind = kind,
            icon = null,
            monthlyTarget = null,
            archived = false,
            sortOrder = 0,
        )

        fun importedTx(
            accountId: String,
            merchant: String,
            amount: String,
            type: TxType,
            date: LocalDate,
            sourceRefId: String,
        ): Transaction = Transaction(
            id = "legacy-$sourceRefId",
            accountId = accountId,
            type = type,
            amount = Money.of(BigDecimal(amount), "SAR"),
            date = date,
            occurredAt = null,
            merchant = merchant,
            merchantNormalized = merchant.lowercase().trim(),
            categoryId = null,
            notes = null,
            source = IngestSource.IMPORT,
            sourceRefId = sourceRefId,
            status = TxStatus.CONFIRMED,
            confidence = 1.0f,
            createdAt = FixedInstant,
            updatedAt = FixedInstant,
        )

        fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")

        private fun xlsxWorkbook(vararg sheets: XlsxSheet): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putText(
                    "[Content_Types].xml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      ${sheets.indices.joinToString("\n") { i ->
                        """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
                    }}
                    </Types>
                    """.trimIndent(),
                )
                zip.putText(
                    "_rels/.rels",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """.trimIndent(),
                )
                zip.putText(
                    "xl/workbook.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        ${sheets.mapIndexed { i, sheet ->
                        """<sheet name="${sheet.name.xmlEscape()}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
                    }.joinToString("\n")}
                      </sheets>
                    </workbook>
                    """.trimIndent(),
                )
                zip.putText(
                    "xl/_rels/workbook.xml.rels",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      ${sheets.indices.joinToString("\n") { i ->
                        """<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>"""
                    }}
                    </Relationships>
                    """.trimIndent(),
                )
                sheets.forEachIndexed { index, sheet ->
                    zip.putText("xl/worksheets/sheet${index + 1}.xml", sheet.toWorksheetXml())
                }
            }
            return out.toByteArray()
        }

        private fun ZipOutputStream.putText(name: String, text: String) {
            putNextEntry(ZipEntry(name))
            write(text.toByteArray(Charsets.UTF_8))
            closeEntry()
        }

        private fun XlsxSheet.toWorksheetXml(): String {
            val body = rows.mapIndexed { rowIndex, row ->
                val cells = row.mapIndexedNotNull { columnIndex, value ->
                    value.takeIf { it.isNotBlank() }?.let {
                        val ref = "${columnName(columnIndex)}${rowIndex + 1}"
                        """<c r="$ref" t="inlineStr"><is><t>${it.xmlEscape()}</t></is></c>"""
                    }
                }.joinToString("")
                """<row r="${rowIndex + 1}">$cells</row>"""
            }.joinToString("\n")
            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    $body
                  </sheetData>
                </worksheet>
            """.trimIndent()
        }

        private fun columnName(index: Int): String {
            var value = index + 1
            val chars = ArrayDeque<Char>()
            while (value > 0) {
                value -= 1
                chars.addFirst(('A'.code + (value % 26)).toChar())
                value /= 26
            }
            return chars.joinToString("")
        }

        private fun String.xmlEscape(): String =
            replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

        private data class XlsxSheet(
            val name: String,
            val rows: List<List<String>>,
        )
    }
}
