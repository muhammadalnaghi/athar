package com.athar.core.data.ledger.admission

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.data.ledger.codec.GraphSnapshotCodec
import com.athar.core.domain.model.financialgraph.CategoryAssignment
import com.athar.core.domain.model.financialgraph.CategoryBasis
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/** Real production Room transaction tests; no app/SQLCipher/producer acceptance. */
@RunWith(AndroidJUnit4::class)
class AdmissionAtomicNativeTest {
    @Test
    fun firstUncategorizedAdmissionsKeepExactMoneyBytesNullDatesAndLegacyRows() = runBlocking {
        withAdmissionNativeDb("exact-money") {
            seedLegacySentinel()
            val legacy = state(listOf("account", "transactions"))
            val amounts = listOf("100000001.01" to "SAR", "99.99" to "USD", "1000" to "JPY", "10.123" to "KWD")
            for ((index, pair) in amounts.withIndex()) {
                val (amount, currency) = pair
                val graph = nativeGraph("source-$currency", "event-$currency", amount, currency)
                assertThat(graph.category).isNull()
                val result = admit(graph)
                assertThat(result).isEqualTo(UnallocatedAdmissionOutcome.Admitted("event-$currency", (index + 1).toLong()))
                val event = ledger.getEvent("event-$currency")!!
                assertThat(event.bookedAmount).isEqualTo(amount)
                assertThat(event.bookedCurrency).isEqualTo(currency)
                assertThat(event.eventDate).isNull()
                assertThat(event.snapshotJson).isEqualTo(GraphSnapshotCodec.encode(graph))
                assertThat(ledger.listComponentsForEvent(event.eventId).single().amount).isEqualTo(amount)
                assertThat(ledger.listLegsForEvent(event.eventId)).isEmpty()
                val archived = evidence.listSnapshots(NATIVE_NAMESPACE, "source-$currency").single()
                assertThat(archived.sourcePayload.toList()).isEqualTo(nativeBytes().toList())
                assertThat(archived.sourcePayloadDigest).isEqualTo(AdmissionDigests.sha256Hex(nativeBytes()))
                assertThat(archived.snapshotDigest).isEqualTo(AdmissionDigests.sha256Hex(event.snapshotJson))
                assertThat(ledger.getObservation(NATIVE_NAMESPACE, "source-$currency")!!.evidenceRef).isEqualTo(NATIVE_EVIDENCE)
            }
            assertThat(rows("canonical_ledger_event")).hasSize(amounts.size)
            assertThat(rows("canonical_ledger_conflict")).isEmpty()
            assertThat(state(listOf("account", "transactions"))).isEqualTo(legacy)
        }
    }

    @Test
    fun exactConcurrentReplayPostsOnceAndAdvancesRevisionOnce() = runBlocking {
        withAdmissionNativeDb("concurrent") {
            val graph = nativeGraph()
            val outcomes = coroutineScope { (1..12).map { async(Dispatchers.IO) { admit(graph) } }.awaitAll() }
            assertThat(outcomes.filterIsInstance<UnallocatedAdmissionOutcome.Admitted>()).hasSize(1)
            assertThat(outcomes.filterIsInstance<UnallocatedAdmissionOutcome.Replay>()).hasSize(11)
            assertThat(admit(graph)).isEqualTo(UnallocatedAdmissionOutcome.Replay("event-a", false))
            for (table in listOf("canonical_ledger_event", "canonical_ledger_component", "canonical_ledger_event_source",
                "canonical_ledger_source_observation", "canonical_ledger_observation_snapshot")) assertThat(rows(table)).hasSize(1)
            assertThat(ledger.getRevision("singleton")!!.revision).isEqualTo(1L)
        }
    }

    @Test
    fun classificationProposedIdAndNumericScaleChangesArchiveWithoutPostingOrRewriting() = runBlocking {
        withAdmissionNativeDb("metadata") {
            assertThat(admit()).isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
            val before = financialState()
            val variants = listOf(
                nativeGraph(eventId = "proposed-new", category = CategoryAssignment("not-a-db-category", CategoryBasis.USER_DECLARED)),
                nativeGraph(eventId = "proposed-blank", category = CategoryAssignment("   ", CategoryBasis.RULE_DERIVED)),
                nativeGraph(amount = "200.0"),
            )
            for (graph in variants) {
                val outcome = admit(graph)
                assertThat(outcome).isInstanceOf(UnallocatedAdmissionOutcome.Replay::class.java)
                assertThat((outcome as UnallocatedAdmissionOutcome.Replay).existingEventId).isEqualTo("event-a")
            }
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-a")).hasSize(variants.size + 1)
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun independentLookalikesStaySeparateWithoutProvenIdentity() = runBlocking {
        withAdmissionNativeDb("lookalikes") {
            assertThat(admit()).isEqualTo(UnallocatedAdmissionOutcome.Admitted("event-a", 1L))
            assertThat(admit(nativeGraph("source-b", "event-b"))).isEqualTo(UnallocatedAdmissionOutcome.Admitted("event-b", 2L))
            assertThat(rows("canonical_ledger_event")).hasSize(2)
            assertThat(rows("canonical_ledger_conflict")).isEmpty()
        }
    }

    @Test
    fun missingAndExhaustedRevisionRefuseWithoutReseedingOrPartialWrites() = runBlocking {
        for (mode in listOf("missing", "exhausted")) withAdmissionNativeDb("revision-$mode") {
            raw.execSQL(if (mode == "missing") "DELETE FROM canonical_ledger_revision" else
                "UPDATE canonical_ledger_revision SET revision = ${Long.MAX_VALUE}")
            val before = state()
            val result = admit()
            assertThat(result).isInstanceOf(UnallocatedAdmissionOutcome.StorageFailed::class.java)
            assertThat((result as UnallocatedAdmissionOutcome.StorageFailed).code)
                .isEqualTo(if (mode == "missing") "REVISION_MISSING" else "REVISION_EXHAUSTED")
            assertThat(state()).isEqualTo(before)
        }
    }

    @Test
    fun midComponentInsertFailureRollsBackAllWritesAndKeepsLegacySentinel() = runBlocking {
        withAdmissionNativeDb("rollback") {
            seedLegacySentinel()
            assertThat(admit()).isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
            val before = state()
            raw.execSQL("""
                CREATE TRIGGER synthetic_admission_failure BEFORE INSERT ON canonical_ledger_component
                WHEN NEW.eventId = 'event-b' BEGIN SELECT RAISE(ABORT, 'synthetic-write-failure'); END
            """.trimIndent())
            val result = admit(nativeGraph("source-b", "event-b"))
            assertThat(result).isInstanceOf(UnallocatedAdmissionOutcome.StorageFailed::class.java)
            assertThat(state()).isEqualTo(before)
        }
    }

    @Test
    fun changedOriginalPayloadIsAnIdempotentDurableConflictNotNewMoney() = runBlocking {
        withAdmissionNativeDb("payload-change") {
            admit()
            val before = financialState()
            val changed = byteArrayOf(77, 0, -1)
            val first = requireConflict(admit(bytes = changed), AdmissionConflictReason.SOURCE_PAYLOAD_CHANGED)
            val repeated = requireConflict(admit(bytes = changed), AdmissionConflictReason.SOURCE_PAYLOAD_CHANGED)
            assertThat(first.alreadyRecorded).isFalse()
            assertThat(repeated.alreadyRecorded).isTrue()
            assertThat(repeated.conflictId).isEqualTo(first.conflictId)
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-a")).hasSize(2)
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun repositoryCanBeCalledFromAndroidMainWithoutMainThreadDatabaseAccess() = runBlocking {
        withAdmissionNativeDb("main-safe") {
            assertThat(ledger.getRevision("singleton")!!.revision).isEqualTo(0L)
            val result = withContext(Dispatchers.Main) {
                assertThat(Looper.myLooper()).isEqualTo(Looper.getMainLooper())
                admit()
            }
            assertThat(result).isEqualTo(UnallocatedAdmissionOutcome.Admitted("event-a", 1L))
        }
    }
}
