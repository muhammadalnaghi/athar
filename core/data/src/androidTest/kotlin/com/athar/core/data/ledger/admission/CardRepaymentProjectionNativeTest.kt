package com.athar.core.data.ledger.admission

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.common.money.Money
import com.athar.core.data.ledger.api.CanonicalPurchaseRecorder
import com.athar.core.data.ledger.api.NewPurchaseProjection
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WM063: synthetic device proof that recordCardRepayment admits one explicit
 * card repayment, confirms exactly its fresh PENDING TRANSFER row (never
 * spend/income) and that replays preserve the user's later choice. Executed by
 * the parent; this worker runs no devices.
 */
@RunWith(AndroidJUnit4::class)
class CardRepaymentProjectionNativeTest {
    private val date = LocalDate.parse("2026-02-02")
    private val instant = Instant.fromEpochMilliseconds(1)

    private fun graph() = (FinancialGraphFactory.build(
        FinancialGraphDraft(
            eventId = "repayment-event",
            observation = FinancialObservation(
                stableId = StableObservationId("source-r"),
                posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
                economicKind = EconomicKind.CARD_REPAYMENT,
                direction = MovementDirection.OUT,
                original = SupportedMoney(Money.of("750.00"), AmountSupport.EXPLICIT_BOOKED),
                settlement = null,
                routing = RoutingCoverage.Unresolved,
                eventDate = date,
                legacy = null,
                userDeclaration = null,
            ),
            sourceNamespace = "sms",
            components = listOf(
                ComponentSpec(
                    componentRef = "principal",
                    type = ComponentType.PRINCIPAL,
                    amount = Money.of("750.00"),
                    support = AmountSupport.EXPLICIT_TRANSACTION,
                    evidenceRef = "repayment:principal",
                ),
            ),
        ),
    ) as FinancialGraphOutcome.Created).graph

    private suspend fun AdmissionNativeDb.seed() = database.transactionDao().get("native-legacy")!!.copy(
        id = "fresh-repayment",
        source = "SMS",
        sourceRefId = "source-r",
        type = "TRANSFER",
        amountMinor = 75000,
        status = "PENDING",
        date = date,
        createdAt = instant,
        updatedAt = instant,
    ).also { database.transactionDao().upsert(it) }

    @Test
    fun repaymentConfirmsItsTransferRowWithoutSpendOrIncomeAndReplayKeepsUserChoice() = runBlocking {
        withAdmissionNativeDb("repayment-once") {
            seedLegacySentinel(); val row = seed(); val recorder = CanonicalPurchaseRecorder(database)
            val projection = NewPurchaseProjection(row.id, instant, instant, recorder.beginSession())
            assertThat(recorder.recordCardRepayment(graph(), nativeBytes(), "public:repayment", projection))
                .isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
            val posted = database.transactionDao().get(row.id)!!
            assertThat(posted.status).isEqualTo("CONFIRMED")
            assertThat(posted.type).isEqualTo("TRANSFER")
            assertThat(posted.categoryId).isNull()
            raw.query("SELECT COUNT(*) FROM transactions WHERE status='CONFIRMED' AND type IN ('EXPENSE','INCOME')").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getLong(0)).isEqualTo(0L)
            }
            database.transactionDao().setStatus(row.id, "DISMISSED", Instant.fromEpochMilliseconds(2))
            val before = state()
            assertThat(recorder.recordCardRepayment(graph(), nativeBytes(), "public:repayment", projection))
                .isInstanceOf(UnallocatedAdmissionOutcome.Replay::class.java)
            assertThat(state()).isEqualTo(before)
        }
    }

    @Test
    fun purchaseApiRefusesRepaymentAndGuardsRefuseEditedRows() = runBlocking {
        withAdmissionNativeDb("repayment-guards") {
            seedLegacySentinel(); val row = seed(); val recorder = CanonicalPurchaseRecorder(database)
            val before = state()
            assertThat(
                recorder.record(graph(), nativeBytes(), "public:repayment",
                    NewPurchaseProjection(row.id, instant, instant, recorder.beginSession())),
            ).isInstanceOf(UnallocatedAdmissionOutcome.Rejected::class.java)
            assertThat(state()).isEqualTo(before)
            val variants = listOf(
                row.copy(type = "EXPENSE"),
                row.copy(sourceRefId = "other-source"),
                row.copy(amountMinor = 74999),
                row.copy(currency = "USD"),
                row.copy(categorySource = "USER"),
                row.copy(status = "DISMISSED"),
                row.copy(status = "CONFIRMED"),
                row.copy(updatedAt = Instant.fromEpochMilliseconds(2)),
            )
            for (variant in variants) {
                database.transactionDao().upsert(variant); val guard = state()
                assertThat(
                    recorder.recordCardRepayment(graph(), nativeBytes(), "public:repayment",
                        NewPurchaseProjection(row.id, instant, instant, recorder.beginSession())),
                ).isInstanceOf(UnallocatedAdmissionOutcome.Rejected::class.java)
                assertThat(state()).isEqualTo(guard)
            }
        }
    }
}
