package com.athar.core.data.ledger.admission

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Fail-closed replay/conflict oracles against the real schema10 production builder. */
@RunWith(AndroidJUnit4::class)
class AdmissionConflictNativeTest {
    @Test
    fun conflictingArchivedVariantCannotRedefineTheAcceptedReplayBaseline() = runBlocking {
        withAdmissionNativeDb("archive-poison") {
            // Choose max/min digest from a bounded deterministic set: this is not
            // an accidental hash-order test or a probabilistic search fixture.
            val candidates = (1..16).map { nativeGraph(amount = "${200 + it}.00") }
                .sortedBy { nativePrepared(it).snapshotDigest }
            val baseline = candidates.last()
            val changed = candidates.first()
            assertThat(nativePrepared(changed).snapshotDigest < nativePrepared(baseline).snapshotDigest).isTrue()
            assertThat(changed.bookedAmount.amount.compareTo(baseline.bookedAmount.amount)).isNotEqualTo(0)
            assertThat(admit(baseline)).isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
            val before = financialState()
            requireConflict(admit(changed), AdmissionConflictReason.ECONOMICS_CHANGED)
            // Exercise both results before asserting so both failures are observable.
            val originalReplay = admit(baseline)
            val changedAgain = admit(changed)
            assertThat(originalReplay).isInstanceOf(UnallocatedAdmissionOutcome.Replay::class.java)
            requireConflict(changedAgain, AdmissionConflictReason.ECONOMICS_CHANGED)
            assertThat(financialState()).isEqualTo(before)
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-a")).hasSize(2)
        }
    }

    @Test
    fun newSourceEventIdCollisionRetainsIncomingObservationArchiveAndConflict() = runBlocking {
        withAdmissionNativeDb("event-collision") {
            admit()
            val before = financialState()
            val incoming = nativeGraph("source-b", "event-a")
            val conflict = requireConflict(admit(incoming), AdmissionConflictReason.EVENT_ID_COLLISION)
            assertThat(conflict.existingEventId).isEqualTo("event-a")
            assertThat(ledger.getObservation(NATIVE_NAMESPACE, "source-b")!!.payloadDigest)
                .isEqualTo(AdmissionDigests.sha256Hex(nativeBytes()))
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-b").single().sourcePayload.toList())
                .isEqualTo(nativeBytes().toList())
            assertThat(ledger.getEventSource(NATIVE_NAMESPACE, "source-b")).isNull()
            assertThat(requireConflict(admit(incoming), AdmissionConflictReason.EVENT_ID_COLLISION).alreadyRecorded).isTrue()
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun issuerIdentityCollisionIsDiagnosticNotSecondSourceAttachmentOrMoney() = runBlocking {
        withAdmissionNativeDb("issuer-collision") {
            val identity = EconomicIdentity.IssuerReference("synthetic-bank", "movement-1")
            admit(nativeGraph(identity = identity))
            val before = financialState()
            val incoming = nativeGraph("source-b", "event-b", identity = identity)
            requireConflict(admit(incoming), AdmissionConflictReason.ECONOMIC_IDENTITY_COLLISION)
            assertThat(ledger.getObservation(NATIVE_NAMESPACE, "source-b")).isNotNull()
            assertThat(evidence.listConflicts(NATIVE_NAMESPACE, "source-b")).hasSize(1)
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-b")).hasSize(1)
            assertThat(ledger.getEventSource(NATIVE_NAMESPACE, "source-b")).isNull()
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun feeEvidenceReuseDoesNotProveTwoDifferentSourcesAreOneEvent() = runBlocking {
        withAdmissionNativeDb("fee-collision") {
            admit(nativeGraph(feeEvidence = "synthetic:fee:shared"))
            val before = financialState()
            val incoming = nativeGraph("source-b", "event-b", feeEvidence = "synthetic:fee:shared")
            requireConflict(admit(incoming), AdmissionConflictReason.FEE_EVIDENCE_COLLISION)
            assertThat(ledger.getObservation(NATIVE_NAMESPACE, "source-b")).isNotNull()
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-b")).hasSize(1)
            assertThat(ledger.getEventSource(NATIVE_NAMESPACE, "source-b")).isNull()
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun changedEvidenceReferencesStayRecoverableWithoutOverwritingOriginal() = runBlocking {
        withAdmissionNativeDb("reference-change") {
            admit()
            val before = financialState()
            val ids = mutableSetOf<String>()
            for (ref in listOf("synthetic:incoming:one", "synthetic:incoming:two")) {
                val first = requireConflict(admit(ref = ref), AdmissionConflictReason.EVIDENCE_REF_MISMATCH)
                val repeated = requireConflict(admit(ref = ref), AdmissionConflictReason.EVIDENCE_REF_MISMATCH)
                assertThat(first.alreadyRecorded).isFalse()
                assertThat(repeated.alreadyRecorded).isTrue()
                assertThat(repeated.conflictId).isEqualTo(first.conflictId)
                assertThat(evidence.getConflict(first.conflictId)!!.detailJson).contains(ref)
                ids += first.conflictId
            }
            assertThat(ids).hasSize(2)
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-a")).hasSize(1)
            assertThat(ledger.getObservation(NATIVE_NAMESPACE, "source-a")!!.evidenceRef).isEqualTo(NATIVE_EVIDENCE)
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun missingAcceptedArchiveOrPayloadDigestNeverRepairsHistoricalSourceDigest() = runBlocking {
        for (mode in listOf("archive", "digest")) withAdmissionNativeDb("missing-$mode") {
            admit()
            if (mode == "archive") raw.execSQL("DELETE FROM canonical_ledger_observation_snapshot")
            else raw.execSQL("UPDATE canonical_ledger_source_observation SET payloadDigest = NULL")
            val observation = ledger.getObservation(NATIVE_NAMESPACE, "source-a")!!
            val before = financialState()
            requireConflict(admit(), AdmissionConflictReason.MISSING_ACCEPTED_ARCHIVE)
            assertThat(ledger.getObservation(NATIVE_NAMESPACE, "source-a")).isEqualTo(observation)
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun invalidHistoricalArchiveStaysUntouchedWhileIncomingVariantIsRetained() = runBlocking {
        withAdmissionNativeDb("invalid-archive") {
            admit()
            raw.execSQL("UPDATE canonical_ledger_observation_snapshot SET sourcePayload = X'7F'")
            val before = financialState()
            val incomingBytes = byteArrayOf(9, 0, -1)
            requireConflict(admit(bytes = incomingBytes), AdmissionConflictReason.INVALID_ACCEPTED_ARCHIVE)
            val variants = evidence.listSnapshots(NATIVE_NAMESPACE, "source-a")
            assertThat(variants).hasSize(2)
            assertThat(variants.single { it.sourcePayloadDigest == AdmissionDigests.sha256Hex(nativeBytes()) }.sourcePayload.toList())
                .isEqualTo(listOf<Byte>(127))
            assertThat(variants.single { it.sourcePayloadDigest == AdmissionDigests.sha256Hex(incomingBytes) }.sourcePayload.toList())
                .isEqualTo(incomingBytes.toList())
            assertThat(financialState()).isEqualTo(before)
        }
    }

    @Test
    fun corruptExistingVariantKeyNeverPretendsItRetainedTheIncomingOriginalBytes() = runBlocking {
        withAdmissionNativeDb("corrupt-key") {
            admit()
            raw.execSQL("UPDATE canonical_ledger_observation_snapshot SET sourcePayload = X'7F'")
            val before = state()
            val result = admit()
            assertThat(result).isInstanceOf(UnallocatedAdmissionOutcome.StorageFailed::class.java)
            assertThat((result as UnallocatedAdmissionOutcome.StorageFailed).reason)
                .isEqualTo(AdmissionStorageFailure.ARCHIVE_VARIANT_CORRUPT)
            assertThat(state()).isEqualTo(before)
        }
    }

    @Test
    fun unboundExistingObservationIsAnIdempotentUnboundConflict() = runBlocking {
        withAdmissionNativeDb("unbound") {
            ledger.insertObservation(CanonicalLedgerSourceObservationEntity(
                NATIVE_NAMESPACE, "source-a", NATIVE_EVIDENCE, AdmissionDigests.sha256Hex(nativeBytes()),
            ))
            val first = requireConflict(admit(), AdmissionConflictReason.SOURCE_UNBOUND)
            val repeated = requireConflict(admit(), AdmissionConflictReason.SOURCE_UNBOUND)
            assertThat(repeated.conflictId).isEqualTo(first.conflictId)
            assertThat(repeated.alreadyRecorded).isTrue()
            assertThat(evidence.listSnapshots(NATIVE_NAMESPACE, "source-a")).hasSize(1)
            assertThat(rows("canonical_ledger_event")).isEmpty()
            assertThat(ledger.getRevision("singleton")!!.revision).isEqualTo(0L)
        }
    }

    @Test
    fun acceptedEventSnapshotCannotBeReplacedByAnArbitraryArchivedAnalysis() = runBlocking {
        withAdmissionNativeDb("header-binding") {
            admit()
            raw.execSQL("UPDATE canonical_ledger_event SET snapshotJson = '{}' WHERE eventId = 'event-a'")
            val before = financialState()
            val outcome = admit()
            assertThat(outcome).isInstanceOf(UnallocatedAdmissionOutcome.Conflicted::class.java)
            assertThat((outcome as UnallocatedAdmissionOutcome.Conflicted).conflict.reason)
                .isAnyOf(AdmissionConflictReason.MISSING_ACCEPTED_ARCHIVE, AdmissionConflictReason.INVALID_ACCEPTED_ARCHIVE)
            assertThat(financialState()).isEqualTo(before)
        }
    }
}
