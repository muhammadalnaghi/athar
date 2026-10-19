package com.athar.core.data.support

import com.athar.core.data.db.entity.SmsMessageEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.domain.model.SmsParseStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class SupportDiagnosticsExporterTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun `payload omits raw sender body merchant and amount text`() {
        val rawSender = "+966501234567"
        val rawBody = "Debit Card POS Purchase at Jarir SAR 1,234.56 card 1234 OTP 9999"
        val payload = SupportDiagnosticsReportBuilder.build(
            rows = listOf(
                sms(
                    sender = rawSender,
                    body = rawBody,
                    status = SmsParseStatus.FAILED.name,
                    error = "amount unparseable: 1,234.56 · tried=alrajhi_pos,universal_amount",
                ),
            ),
            totalRows = 1,
            statusCounts = mapOf(SmsParseStatus.FAILED.name to 1),
            generatedAt = "2026-06-13T00:00:00Z",
        )

        val encoded = json.encodeToString(payload)

        assertThat(encoded).doesNotContain(rawSender)
        assertThat(encoded).doesNotContain(rawBody)
        assertThat(encoded).doesNotContain("Jarir")
        assertThat(encoded).doesNotContain("1,234.56")
        assertThat(encoded).doesNotContain("OTP 9999")
        assertThat(encoded).doesNotContain("card 1234")
        assertThat(payload.recentAuditSamples.single().bodyShapeSha256).hasLength(12)
        assertThat(payload.recentAuditSamples.single().redactedError).isEqualTo("amount unparseable: [number]")
    }

    @Test
    fun `summary uses database totals instead of recent sample size`() {
        val payload = SupportDiagnosticsReportBuilder.build(
            rows = listOf(
                sms(status = SmsParseStatus.PARSED.name),
                sms(id = "2", status = SmsParseStatus.FAILED.name),
            ),
            totalRows = 1_200,
            statusCounts = mapOf(
                SmsParseStatus.PARSED.name to 700,
                SmsParseStatus.FAILED.name to 300,
                SmsParseStatus.IGNORED.name to 190,
                "NEW" to 10,
            ),
            generatedAt = "2026-06-13T00:00:00Z",
        )

        assertThat(payload.summary.totalAuditRows).isEqualTo(1_200)
        assertThat(payload.summary.includedRecentRows).isEqualTo(2)
        assertThat(payload.summary.truncated).isTrue()
        assertThat(payload.summary.parsed).isEqualTo(700)
        assertThat(payload.summary.failed).isEqualTo(300)
        assertThat(payload.summary.ignored).isEqualTo(190)
        assertThat(payload.summary.newRows).isEqualTo(10)
    }

    @Test
    fun `payload summarizes category backlog coverage without raw merchant or amount text`() {
        val rawMerchant = "Jarir Electronics"
        val secondRawMerchant = "Local Cafe"
        val payload = SupportDiagnosticsReportBuilder.build(
            rows = emptyList(),
            totalRows = 0,
            statusCounts = emptyMap(),
            generatedAt = "2026-06-13T00:00:00Z",
            transactions = listOf(
                tx(id = "1", merchant = rawMerchant, status = "PENDING", categoryId = null, amountMinor = 123_456),
                tx(id = "2", merchant = rawMerchant, status = "DISMISSED", categoryId = null, amountMinor = 999_999),
                tx(id = "3", merchant = "Internal Transfer", status = "PENDING", type = "TRANSFER", categoryId = null),
                tx(id = "4", merchant = "Starbucks", status = "CONFIRMED", categoryId = "cat-coffee"),
                tx(id = "5", merchant = secondRawMerchant, status = "CONFIRMED", categoryId = null),
            ),
        )

        val encoded = json.encodeToString(payload)

        assertThat(encoded).doesNotContain(rawMerchant)
        assertThat(encoded).doesNotContain(rawMerchant.lowercase())
        assertThat(encoded).doesNotContain(secondRawMerchant)
        assertThat(encoded).doesNotContain(secondRawMerchant.lowercase())
        assertThat(encoded).doesNotContain("123456")
        assertThat(encoded).doesNotContain("999999")
        assertThat(payload.privacy.rawMerchants).isEqualTo("omitted")
        assertThat(payload.privacy.transactionRows).isEqualTo("omitted")
        assertThat(payload.transactionSummary.totalTransactions).isEqualTo(5)
        assertThat(payload.transactionSummary.categorizedTransactions).isEqualTo(1)
        assertThat(payload.transactionSummary.categoryBacklogTransactions).isEqualTo(3)
        assertThat(payload.transactionSummary.pendingCategoryBacklog).isEqualTo(1)
        assertThat(payload.transactionSummary.dismissedCategoryBacklog).isEqualTo(1)
        assertThat(payload.transactionSummary.confirmedWithoutCategory).isEqualTo(1)
        assertThat(payload.transactionSummary.transferRowsExcluded).isEqualTo(1)

        assertThat(payload.categoryCoverage.categoryEligibleTransactions).isEqualTo(4)
        assertThat(payload.categoryCoverage.categorizedCategoryEligibleTransactions).isEqualTo(1)
        assertThat(payload.categoryCoverage.categoryBacklogTransactions).isEqualTo(3)
        assertThat(payload.categoryCoverage.categorizedCoveragePermille).isEqualTo(250)
        assertThat(payload.categoryCoverage.backlogCoveragePermille).isEqualTo(750)
        assertThat(payload.categoryCoverage.topUncategorizedGroupCount).isEqualTo(2)
        assertThat(payload.categoryCoverage.topUncategorizedSampleCount).isEqualTo(3)
        assertThat(payload.categoryCoverage.topUncategorizedGroupCoveragePermille).isEqualTo(1_000)
        assertThat(payload.categoryCoverage.otherBacklogTransactionCount).isEqualTo(0)
        assertThat(payload.categoryCoverage.largestUncategorizedGroupSampleCount).isEqualTo(2)
        assertThat(payload.categoryCoverage.largestUncategorizedGroupCoveragePermille).isEqualTo(666)
        assertThat(payload.categoryBacklogRecommendation.primaryAction).isEqualTo("manual_cleanup")
        assertThat(payload.categoryBacklogRecommendation.recommendedActions).containsExactly("manual_cleanup")
        assertThat(payload.categoryBacklogRecommendation.reasonCodes).containsExactly("small_category_backlog")

        assertThat(payload.uncategorizedMerchantGroups).hasSize(2)
        val group = payload.uncategorizedMerchantGroups.first()
        assertThat(group.merchantHash).hasLength(12)
        assertThat(group.merchantLengthBucket).isEqualTo("9-32")
        assertThat(group.merchantScript).isEqualTo("latin")
        assertThat(group.sampleCount).isEqualTo(2)
        assertThat(group.shareOfBacklogPermille).isEqualTo(666)
        assertThat(group.cumulativeShareOfBacklogPermille).isEqualTo(666)
        assertThat(group.pending).isEqualTo(1)
        assertThat(group.dismissed).isEqualTo(1)
        assertThat(group.currencyCounts.map { it.label to it.count }).containsExactly("SAR" to 2)
        assertThat(group.confidenceBuckets.map { it.label to it.count }).containsExactly("0.70-0.84" to 2)

        val secondGroup = payload.uncategorizedMerchantGroups.last()
        assertThat(secondGroup.sampleCount).isEqualTo(1)
        assertThat(secondGroup.shareOfBacklogPermille).isEqualTo(333)
        assertThat(secondGroup.cumulativeShareOfBacklogPermille).isEqualTo(1_000)
    }

    @Test
    fun `category backlog recommendation reports none when every eligible row is categorized`() {
        val payload = SupportDiagnosticsReportBuilder.build(
            rows = emptyList(),
            totalRows = 0,
            statusCounts = emptyMap(),
            generatedAt = "2026-06-13T00:00:00Z",
            transactions = listOf(
                tx(id = "1", merchant = "Starbucks", status = "CONFIRMED", categoryId = "cat-coffee"),
                tx(id = "2", merchant = "Internal Transfer", status = "PENDING", type = "TRANSFER", categoryId = null),
            ),
        )

        assertThat(payload.categoryBacklogRecommendation.primaryAction).isEqualTo("none")
        assertThat(payload.categoryBacklogRecommendation.recommendedActions).containsExactly("none")
        assertThat(payload.categoryBacklogRecommendation.reasonCodes).containsExactly("no_category_backlog")
    }

    @Test
    fun `category backlog recommendation prefers repeated backlog cleanup for repeated merchant groups`() {
        val payload = SupportDiagnosticsReportBuilder.build(
            rows = emptyList(),
            totalRows = 0,
            statusCounts = emptyMap(),
            generatedAt = "2026-06-13T00:00:00Z",
            transactions = listOf(
                tx(id = "1", merchant = "Repeated Cafe", status = "PENDING", categoryId = null),
                tx(id = "2", merchant = "Repeated Cafe", status = "PENDING", categoryId = null),
                tx(id = "3", merchant = "Repeated Cafe", status = "DISMISSED", categoryId = null),
            ),
        )

        assertThat(payload.categoryBacklogRecommendation.primaryAction).isEqualTo("history_repeated_backlog")
        assertThat(payload.categoryBacklogRecommendation.recommendedActions)
            .containsExactly("history_repeated_backlog")
        assertThat(payload.categoryBacklogRecommendation.reasonCodes)
            .containsExactly("largest_repeated_group_at_least_3")
    }

    @Test
    fun `generic repeated merchants stay out of support repeated backlog recommendations`() {
        val payload = SupportDiagnosticsReportBuilder.build(
            rows = emptyList(),
            totalRows = 0,
            statusCounts = emptyMap(),
            generatedAt = "2026-06-13T00:00:00Z",
            transactions = listOf(
                tx(id = "1", merchant = "Payment", status = "PENDING", categoryId = null),
                tx(id = "2", merchant = "Payment", status = "PENDING", categoryId = null),
                tx(id = "3", merchant = "Payment", status = "DISMISSED", categoryId = null),
                tx(id = "4", merchant = "كاش", status = "PENDING", categoryId = null),
                tx(id = "5", merchant = "كاش", status = "PENDING", categoryId = null),
                tx(id = "6", merchant = "كاش", status = "DISMISSED", categoryId = null),
            ),
        )

        assertThat(payload.categoryCoverage.categoryBacklogTransactions).isEqualTo(6)
        assertThat(payload.categoryCoverage.topUncategorizedGroupCount).isEqualTo(0)
        assertThat(payload.categoryCoverage.topUncategorizedSampleCount).isEqualTo(0)
        assertThat(payload.categoryCoverage.otherBacklogTransactionCount).isEqualTo(6)
        assertThat(payload.categoryCoverage.largestUncategorizedGroupSampleCount).isEqualTo(0)
        assertThat(payload.categoryBacklogRecommendation.primaryAction).isEqualTo("manual_cleanup")
        assertThat(payload.categoryBacklogRecommendation.recommendedActions).containsExactly("manual_cleanup")
        assertThat(payload.categoryBacklogRecommendation.reasonCodes).containsExactly("small_category_backlog")
        assertThat(payload.uncategorizedMerchantGroups).isEmpty()
    }

    @Test
    fun `category backlog recommendation uses bulk export for large long tail backlog`() {
        val transactions = (1..25).map { index ->
            tx(id = index.toString(), merchant = "Long Tail Merchant $index", status = "PENDING", categoryId = null)
        }

        val payload = SupportDiagnosticsReportBuilder.build(
            rows = emptyList(),
            totalRows = 0,
            statusCounts = emptyMap(),
            generatedAt = "2026-06-13T00:00:00Z",
            transactions = transactions,
        )

        assertThat(payload.categoryBacklogRecommendation.primaryAction).isEqualTo("bulk_categorize_export")
        assertThat(payload.categoryBacklogRecommendation.recommendedActions)
            .containsExactly("bulk_categorize_export")
        assertThat(payload.categoryBacklogRecommendation.reasonCodes)
            .containsExactly("category_backlog_at_least_25")
    }

    @Test
    fun `category backlog recommendation combines repeated and bulk export actions`() {
        val repeatedRows = (1..3).map { index ->
            tx(id = "r$index", merchant = "Repeated Cafe", status = "PENDING", categoryId = null)
        }
        val longTailRows = (1..30).map { index ->
            tx(id = "l$index", merchant = "Long Tail Merchant $index", status = "PENDING", categoryId = null)
        }

        val payload = SupportDiagnosticsReportBuilder.build(
            rows = emptyList(),
            totalRows = 0,
            statusCounts = emptyMap(),
            generatedAt = "2026-06-13T00:00:00Z",
            transactions = repeatedRows + longTailRows,
        )

        assertThat(payload.categoryBacklogRecommendation.primaryAction).isEqualTo("history_repeated_backlog")
        assertThat(payload.categoryBacklogRecommendation.recommendedActions)
            .containsExactly("history_repeated_backlog", "bulk_categorize_export")
            .inOrder()
        assertThat(payload.categoryBacklogRecommendation.reasonCodes).containsExactly(
            "largest_repeated_group_at_least_3",
            "category_backlog_at_least_25",
            "includes_long_tail_backlog",
        ).inOrder()
    }

    @Test
    fun `failed rows group by redacted parser reason and keep template ids`() {
        val payload = SupportDiagnosticsReportBuilder.build(
            rows = listOf(
                sms(
                    id = "1",
                    status = SmsParseStatus.FAILED.name,
                    error = "amount unparseable: ١٢٣٤.٥٦ · tried=d360_online_purchase,universal_amount",
                ),
                sms(
                    id = "2",
                    status = SmsParseStatus.FAILED.name,
                    error = "amount unparseable: 999.00 · tried=d360_online_purchase",
                ),
            ),
            totalRows = 2,
            statusCounts = mapOf(SmsParseStatus.FAILED.name to 2),
            generatedAt = "2026-06-13T00:00:00Z",
        )

        val group = payload.errorGroups.single()
        assertThat(group.category).isEqualTo("amount_parse")
        assertThat(group.redactedError).isEqualTo("amount unparseable: [number]")
        assertThat(group.count).isEqualTo(2)
        assertThat(group.templateAttempts).containsExactly("d360_online_purchase", "universal_amount")
        assertThat(group.templateAttemptCount).isEqualTo(2)
    }

    private fun sms(
        id: String = "1",
        sender: String = "AlRajhiBank",
        body: String = "Purchase SAR 10.00",
        status: String = SmsParseStatus.PARSED.name,
        error: String? = null,
    ): SmsMessageEntity = SmsMessageEntity(
        id = id,
        sender = sender,
        body = body,
        receivedAt = Instant.parse("2026-06-13T12:00:00Z"),
        parsedTransactionId = if (status == SmsParseStatus.PARSED.name) "tx-$id" else null,
        parseStatus = status,
        parseError = error,
    )

    private fun tx(
        id: String,
        merchant: String,
        status: String,
        type: String = "EXPENSE",
        categoryId: String? = null,
        amountMinor: Long = 1_000,
    ): TransactionEntity = TransactionEntity(
        id = id,
        accountId = "acc-1",
        type = type,
        amountMinor = amountMinor,
        currency = "SAR",
        date = LocalDate(2026, 6, 13),
        occurredAt = Instant.parse("2026-06-13T12:00:00Z"),
        merchant = merchant,
        merchantNormalized = merchant.lowercase().trim(),
        categoryId = categoryId,
        notes = "Private note $merchant $amountMinor",
        source = "SMS",
        sourceRefId = "raw-$id",
        status = status,
        confidence = 0.8f,
        createdAt = Instant.parse("2026-06-13T12:00:00Z"),
        updatedAt = Instant.parse("2026-06-13T12:00:00Z"),
        categorySource = null,
    )
}
