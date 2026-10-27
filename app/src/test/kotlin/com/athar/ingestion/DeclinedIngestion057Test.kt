package com.athar.ingestion

import com.athar.core.common.money.Money
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.MerchantCatalogSnapshot
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.SmsParseStatus
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.MerchantCatalogRepository
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class DeclinedIngestion057Test {
    private val now = Instant.parse("2026-10-25T12:00:00Z")
    private val transactions = mockk<TransactionRepository>(relaxed = true) {
        coEvery { upsertIngest(any()) } answers { firstArg<Transaction>().id }
    }
    private val audit = mockk<SmsAuditRepository>(relaxed = true) {
        coEvery { record(any()) } returns "audit057"
    }
    private val accounts = mockk<AccountRepository>(relaxed = true)
    private val rules = mockk<CategoryRuleRepository> {
        coEvery { findMatching(any()) } returns emptyList<CategoryRule>()
    }
    private val catalog = mockk<MerchantCatalogRepository> {
        coEvery { snapshot() } returns MerchantCatalogSnapshot(emptyList(), 1L)
    }
    private val recorder = mockk<FinancialRecordingAdapter>(relaxed = true)
    private val prefs = mockk<UserPreferencesRepository> {
        every { ownAccountNumbers() } returns flowOf(emptyList())
    }
    private val incomeSources = mockk<com.athar.core.domain.repo.IncomeSourcePreferencesRepository> {
        every { settings() } returns flowOf(com.athar.core.domain.repo.IncomeSourceSettings())
    }

    private fun pipeline(
        state: PostingState = PostingState.DECLINED,
        kind: TxType = TxType.EXPENSE,
    ): SmsIngestionPipeline = SmsIngestionPipeline(
        parser = SmsParser {
            ParseResult.Success(
                type = kind,
                amount = Money.ofMinor(20_00, "SAR"),
                merchant = "QA057",
                counterparty = null,
                balanceAfter = null,
                occurredAt = null,
                confidence = 0.95f,
                templateId = "test057",
                posting = PostingEvidence(state, PostingSupport.HEURISTIC_ONLY),
            )
        },
        transactions = transactions,
        accounts = accounts,
        resolver = MerchantCategoryResolver(rules, mockk<CategoryRepository>(relaxed = true), catalog),
        audit = audit,
        prefs = prefs,
        incomeSources = incomeSources,
        clock = object : Clock { override fun now(): Instant = this@DeclinedIngestion057Test.now },
        recording = recorder,
    )

    private fun event(body: String) = RawIngestEvent(
        id = "event057", source = IngestSource.SMS, sender = "QA057Bank", body = body,
        receivedAt = now, rawId = "raw057",
    )

    private fun assertNoMoneyWrites() {
        coVerify(exactly = 0) { transactions.upsertIngest(any()) }
        coVerify(exactly = 0) { transactions.upsert(any()) }
        coVerify(exactly = 0) { transactions.delete(any()) }
        coVerify(exactly = 0) { transactions.setStatus(any(), any()) }
        coVerify(exactly = 0) { recorder.recordNewPurchase(any(), any(), any(), any(), any()) }
    }

    private fun assertRetainedPending(kind: TxType = TxType.EXPENSE) {
        val captured = slot<Transaction>()
        coVerify(exactly = 1) { transactions.upsertIngest(capture(captured)) }
        assertThat(captured.captured.status).isEqualTo(TxStatus.PENDING)
        assertThat(captured.captured.type).isEqualTo(kind)
        assertThat(captured.captured.amount).isEqualTo(Money.ofMinor(20_00, "SAR"))
        coVerify(exactly = 1) { audit.updateParseResult("audit057", SmsParseStatus.PARSED, captured.captured.id, null) }
    }

    @Test
    fun `a refused purchase keeps raw audit but creates no review money`() = runTest {
        val source = event("Purchase of SAR 20.00 at QA057 was declined due to insufficient balance.")
        pipeline().process(source)
        coVerify(exactly = 1) { audit.record(source) }
        coVerify(exactly = 1) { audit.updateParseResult("audit057", SmsParseStatus.IGNORED, null, null) }
        coVerify(exactly = 0) { accounts.resolveForIngest(any(), any(), any()) }
        coVerify(exactly = 0) { rules.findMatching(any()) }
        coVerify(exactly = 0) { catalog.snapshot() }
        assertNoMoneyWrites()
    }

    @Test
    fun `an Arabic refused purchase is evidence not a new financial task`() = runTest {
        pipeline().process(event("عملية شراء مرفوضة\nالمبلغ: 20.00 SAR\nلم تتم عملية الشراء"))
        coVerify(exactly = 1) { audit.updateParseResult("audit057", SmsParseStatus.IGNORED, null, null) }
        assertNoMoneyWrites()
    }

    @Test
    fun `an explicit insufficient balance purchase notification creates no money`() = runTest {
        pipeline().process(event("Notification: Declined due to Insufficient balance\nTransaction: شراء عبر الإنترنت\nAmount: 20.00 SAR"))
        coVerify(exactly = 1) { audit.updateParseResult("audit057", SmsParseStatus.IGNORED, null, null) }
        assertNoMoneyWrites()
    }

    @Test
    fun `repeated refused notices never write or repair existing money`() = runTest {
        val source = event("Your payment of SAR 20.00 was not completed.")
        val pipeline = pipeline()
        pipeline.process(source)
        pipeline.process(source.copy(id = "delivery057-2"))
        coVerify(exactly = 2) { audit.updateParseResult("audit057", SmsParseStatus.IGNORED, null, null) }
        assertNoMoneyWrites()
    }

    @Test
    fun `a merchant called DECLINED does not become a nonfinancial notice`() = runTest {
        pipeline().process(event("Purchase of SAR 20.00\nAt: DECLINED COFFEE\nDate: 2026-10-25"))
        assertRetainedPending()
    }

    @Test
    fun `conditional declined-payment advice does not suppress the purchase`() = runTest {
        pipeline().process(event("Purchase of SAR 20.00 at QA057. If payment failed contact your bank."))
        assertRetainedPending()
    }

    @Test
    fun `a fee booked on a declined reason line stays on the financial path`() = runTest {
        pipeline().process(event("Purchase of SAR 20.00\nReason: declined; fee of SAR 1.00 was debited."))
        assertRetainedPending()
    }

    @Test
    fun `a later completed retry cannot disappear beneath a declined heading`() = runTest {
        pipeline().process(event("Purchase of SAR 20.00 was declined.\nStatus: Completed after retry; SAR 20.00 debited."))
        assertRetainedPending()
    }

    @Test
    fun `a booked refund remains financial beneath a declined payment`() = runTest {
        pipeline(kind = TxType.INCOME).process(event("Your payment of SAR 20.00 was declined.\nRefund of SAR 20.00 credited."))
        assertRetainedPending(TxType.INCOME)
    }

    @Test
    fun `contradictory typed authorization evidence is not silently ignored`() = runTest {
        pipeline(state = PostingState.AUTHORIZED).process(event("Purchase of SAR 20.00 was declined."))
        assertRetainedPending()
    }
}
