package com.athar.core.data.ledger.admission

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.common.money.Money
import com.athar.core.data.backup.BackupService
import com.athar.core.data.ledger.api.CanonicalPurchaseRecorder
import com.athar.core.data.ledger.api.NewPurchaseProjection
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financial.AmountSupport
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PurchaseProjectionNativeTest {
    private val date = LocalDate.parse("2026-01-01")
    private val instant = Instant.fromEpochMilliseconds(1)
    private fun graph() = (FinancialGraphFactory.build(FinancialGraphDraft(
        eventId = "purchase-event", observation = nativeGraph(date = date).observation,
        sourceNamespace = "sms", components = listOf(ComponentSpec("principal", ComponentType.PRINCIPAL,
            Money.of("200.00"), support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = "public:amount")),
    )) as FinancialGraphOutcome.Created).graph

    private suspend fun AdmissionNativeDb.seed() = database.transactionDao().get("native-legacy")!!.copy(
        id = "fresh-purchase", source = "SMS", sourceRefId = "source-a", amountMinor = 20000,
        status = "PENDING", date = date, createdAt = instant, updatedAt = instant,
    ).also { database.transactionDao().upsert(it) }

    @Test fun uncategorizedPurchaseCountsOnceAndReplayPreservesDismissal() = runBlocking {
        withAdmissionNativeDb("projection-once") {
            seedLegacySentinel(); val row = seed(); val recorder = CanonicalPurchaseRecorder(database)
            val projection = NewPurchaseProjection(row.id, instant, instant, recorder.beginSession())
            assertThat(recorder.record(graph(), nativeBytes(), "public:evidence", projection))
                .isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
            val posted = database.transactionDao().get(row.id)!!
            assertThat(posted.status).isEqualTo("CONFIRMED"); assertThat(posted.categoryId).isNull()
            database.transactionDao().setStatus(row.id, "DISMISSED", Instant.fromEpochMilliseconds(2))
            val before = state()
            assertThat(recorder.record(graph(), nativeBytes(), "public:evidence", projection))
                .isInstanceOf(UnallocatedAdmissionOutcome.Replay::class.java)
            assertThat(state()).isEqualTo(before)
        }
    }

    @Test fun sourceEconomicsAndUserEditedRowsCannotBePromoted() = runBlocking {
        withAdmissionNativeDb("projection-guards") {
            seedLegacySentinel(); val row = seed(); val recorder = CanonicalPurchaseRecorder(database)
            val variants = listOf(row.copy(source = "MANUAL"), row.copy(sourceRefId = "other-source"),
                row.copy(amountMinor = 19999), row.copy(currency = "USD"), row.copy(type = "INCOME"),
                row.copy(date = LocalDate.parse("2026-01-02")), row.copy(categorySource = "USER"),
                row.copy(status = "DISMISSED"), row.copy(status = "CONFIRMED"),
                row.copy(updatedAt = Instant.fromEpochMilliseconds(2)))
            for (variant in variants) {
                database.transactionDao().upsert(variant); val before = state()
                val result = recorder.record(graph(), nativeBytes(), "public:evidence",
                    NewPurchaseProjection(row.id, instant, instant, recorder.beginSession()))
                assertThat(result).isInstanceOf(UnallocatedAdmissionOutcome.Rejected::class.java)
                assertThat(state()).isEqualTo(before)
            }
        }
    }

    @Test fun missingRowAndWrongCreationTokenRefuseWithoutCanonicalWrites() = runBlocking {
        withAdmissionNativeDb("projection-missing") {
            seedLegacySentinel(); val row = seed(); val recorder = CanonicalPurchaseRecorder(database)
            for (request in listOf(NewPurchaseProjection("absent", instant, instant, recorder.beginSession()),
                NewPurchaseProjection(row.id, Instant.fromEpochMilliseconds(9), instant, recorder.beginSession()))) {
                val before = state()
                assertThat(recorder.record(graph(), nativeBytes(), "public:evidence", request))
                    .isInstanceOf(UnallocatedAdmissionOutcome.Rejected::class.java)
                assertThat(state()).isEqualTo(before)
            }
        }
    }

    @Test fun projectionWriteFailureRollsBackCanonicalAndVisibleRowsTogether() = runBlocking {
        withAdmissionNativeDb("projection-rollback") {
            seedLegacySentinel(); val row = seed(); val recorder = CanonicalPurchaseRecorder(database)
            raw.execSQL("CREATE TRIGGER public_projection_failure BEFORE UPDATE OF status ON transactions WHEN NEW.id = 'fresh-purchase' BEGIN SELECT RAISE(ABORT, 'public-failure'); END")
            val before = state()
            assertThat(recorder.record(graph(), nativeBytes(), "public:evidence",
                NewPurchaseProjection(row.id, instant, instant, recorder.beginSession())))
                .isInstanceOf(UnallocatedAdmissionOutcome.StorageFailed::class.java)
            assertThat(state()).isEqualTo(before)
        }
    }

    @Test fun actualWholeBackupRestoreInvalidatesAlreadyCapturedSession() = runBlocking {
        withAdmissionNativeDb("projection-restore") {
            seedLegacySentinel(); val row = seed(); val recorder = CanonicalPurchaseRecorder(database)
            val stale = NewPurchaseProjection(row.id, instant, instant, recorder.beginSession())
            val backup = BackupService(database, Clock.System)
            val pass = CharArray(32) { ('a'.code + (it % 26)).toChar() }
            try {
                val output = ByteArrayOutputStream(); backup.export(output, pass)
                backup.import(output.toByteArray(), pass)
                val before = state()
                assertThat(recorder.record(graph(), nativeBytes(), "public:evidence", stale))
                    .isInstanceOf(UnallocatedAdmissionOutcome.Rejected::class.java)
                assertThat(state()).isEqualTo(before)
                assertThat(recorder.record(graph(), nativeBytes(), "public:evidence",
                    NewPurchaseProjection(row.id, instant, instant, recorder.beginSession())))
                    .isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
            } finally { pass.fill(0.toChar()) }
        }
    }
}
