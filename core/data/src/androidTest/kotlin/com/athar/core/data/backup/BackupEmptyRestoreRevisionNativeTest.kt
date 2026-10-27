package com.athar.core.data.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.common.crypto.AtharCrypto
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.data.db.entity.InvestmentContributionEntity
import com.athar.core.data.db.entity.InvestmentPoolEntity
import com.athar.core.data.db.entity.SmsMessageEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.TransactionReceiptEntity
import com.athar.core.data.db.entity.WishlistEntity
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerConflictEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventSourceEntity
import com.athar.core.data.ledger.db.CanonicalLedgerLegEntity
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity
import com.athar.core.data.ledger.db.CanonicalObservationSnapshotEntity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * Real-Room regression for the v4 EMPTY canonical section over the epoch
 * contract: production builder (`atharDatabaseBuilder`, migrations +
 * revision-seed onCreate), unique synthetic per-test database files,
 * synthetic rows only — never real financial records. Actual production
 * [BackupService.import] with [AtharCrypto]-encrypted payloads.
 *
 * Oracles (WM028-P):
 *  1. A valid v4 payload whose canonicalLedger is all-empty, carrying NO
 *     revision rows, must fully replace populated old canonical AND legacy
 *     state and strictly advance the live `singleton` epoch 5 → 6
 *     (max(current, incoming-absent 0) + 1; the singleton is always affected
 *     by a v4 whole replace, even when both revision lists are empty).
 *  2. The same empty restore against a live `singleton` pinned at
 *     [Long.MAX_VALUE] must refuse with [BackupValidationCode.INVALID_REVISION]
 *     during plan creation, before ANY row or blob changes — proven by exact
 *     field and byte-content evidence, not counts.
 *
 * Authored for device execution; not run in this lane.
 */
@RunWith(AndroidJUnit4::class)
class BackupEmptyRestoreRevisionNativeTest {

    private val passphrase = "synthetic-passphrase-028P-empty".toCharArray()

    private val at = Instant.parse("2026-06-13T00:00:00Z")

    private fun newDb(tag: String): AtharDatabase =
        atharDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            "synthetic-backup-empty-028p-$tag-${UUID.randomUUID()}.db",
        ).allowMainThreadQueries().build()

    private fun service(db: AtharDatabase) = BackupService(db, Clock.System)

    // ------------------------------------------------------------- seeding

    private suspend fun seedLegacy(db: AtharDatabase) {
        db.wishlistDao().upsert(
            WishlistEntity("wish-1", "Synthetic goal", 500L, 123L, "SAR", 12, 2026, 6, "preserve exactly"),
        )
        db.accountDao().upsert(
            AccountEntity(
                id = "acc-1", name = "Cash", type = "CASH", currency = "SAR",
                openingBalanceMinor = 100_00, openingBalanceCurrency = "SAR",
                smsSenders = "", notes = null, sortOrder = 0, active = true,
                archivedAt = null, createdAt = at, updatedAt = at,
            ),
        )
        db.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = "cat-1", name = "Food", nameAr = "طعام", kind = "EXPENSE",
                    icon = null, monthlyTargetMinor = null, currency = "SAR",
                    archived = false, sortOrder = 0,
                ),
            ),
        )
        db.categoryRuleDao().upsert(
            CategoryRuleEntity(
                id = "rule-1", pattern = "store", patternType = "CONTAINS",
                categoryId = "cat-1", priority = 1, learnedFromUser = false, createdAt = at,
            ),
        )
        db.transactionDao().upsert(
            TransactionEntity(
                id = "tx-1", accountId = "acc-1", type = "EXPENSE",
                amountMinor = 12_00, currency = "SAR", date = LocalDate.parse("2026-06-13"),
                occurredAt = null, merchant = "Store", merchantNormalized = "store",
                categoryId = "cat-1", notes = null, source = "MANUAL", sourceRefId = null,
                status = "CONFIRMED", confidence = null, createdAt = at, updatedAt = at,
                categorySource = "USER",
            ),
        )
        db.investmentDao().upsertPool(
            InvestmentPoolEntity(id = "pool-1", name = "Pool", period = "MONTHLY", totalReturnMinor = 0L, currency = "SAR"),
        )
        db.investmentDao().upsertContribution(
            InvestmentContributionEntity(id = "con-1", poolId = "pool-1", ownerName = "Owner", amountMinor = 5_00, currency = "SAR"),
        )
        db.smsMessageDao().upsert(
            SmsMessageEntity(id = "sms-1", sender = "BANK", body = "synthetic", receivedAt = at, parsedTransactionId = null, parseStatus = "UNPARSED", parseError = null),
        )
        db.transactionReceiptDao().upsert(
            TransactionReceiptEntity(id = "rc-1", transactionId = "tx-1", mimeType = "image/png", originalName = null, sizeBytes = 4L, payload = byteArrayOf(1, 2, 3, 4), createdAt = at),
        )
    }

    private suspend fun seedCanonical(db: AtharDatabase) {
        val dao = db.canonicalLedgerDao()
        dao.insertEvent(
            CanonicalLedgerEventEntity(
                eventId = "n-event-1", economicIdentityIssuer = "issuer", economicIdentityReference = "ref-1",
                snapshotFormatVersion = 1L, policyRevision = "p-1", graphRevision = "g-1",
                snapshotJson = " \r\n{\"opaque\":\"0001.2300\"}\t ", bookedAmount = "0001.2300",
                bookedCurrency = "SAR", bookedBasis = "ORIGINAL", kind = "FUTURE_KIND",
                kindResolved = false, incomeNature = "UNCLASSIFIED",
                recordingOutcome = "CAPTURED_UNRESOLVED_KIND", eventDate = "2026-01-02",
                routing = "UNALLOCATED", composition = "FUTURE_COMPOSITION",
            ),
        )
        dao.insertObservation(
            CanonicalLedgerSourceObservationEntity(sourceNamespace = "synthetic-sms", sourceId = "src-1", evidenceRef = "ev-1", payloadDigest = "pd-1"),
        )
        dao.insertEventSource(
            CanonicalLedgerEventSourceEntity(sourceNamespace = "synthetic-sms", sourceId = "src-1", eventId = "n-event-1"),
        )
        dao.insertComponent(
            CanonicalLedgerComponentEntity(eventId = "n-event-1", componentRef = "cmp-1", type = "FUTURE_COMPONENT", amount = "1E+3", currency = "KWD", feeRelation = "UNKNOWN", feeEvidenceRef = "fee-1"),
        )
        dao.insertLeg(
            CanonicalLedgerLegEntity(eventId = "n-event-1", legRef = "leg-1", accountRef = "acc-1", delta = "-0001.2300", currency = "KWD", componentRef = "cmp-1"),
        )
        dao.insertSnapshotVariant(
            CanonicalObservationSnapshotEntity(
                sourceNamespace = "synthetic-sms", sourceId = "src-1",
                sourcePayloadDigest = "pd-1", snapshotDigest = "sd-1",
                sourcePayload = byteArrayOf(9, 8, 7), snapshotJson = "{\"variant\":true}",
            ),
        )
        dao.insertConflict(
            CanonicalLedgerConflictEntity(
                conflictId = "n-conflict-1", sourceNamespace = "synthetic-sms", sourceId = "src-1",
                sourcePayloadDigest = "pd-1", snapshotDigest = "sd-1", existingEventId = "n-event-1",
                reasonCode = "FUTURE_REASON", detailJson = " {\"detail\":\"kept\"}\r\n ",
            ),
        )
    }

    // ------------------------------------------------------- empty payload

    /** Valid v4 envelope whose canonical section is all-empty with NO revision rows. */
    private fun emptyV4Json(): String =
        """
        {"version":4,"exportedAt":"2026-01-01T00:00:00Z","accounts":[],"categories":[],
         "transactions":[],"categoryRules":[],"wishlist":[],"investmentPools":[],
         "investmentContributions":[],"smsAudit":[],"receiptAttachments":[],
         "auxiliaryTables":{"activityLog":[],"userTemplates":[],"recurringRules":[],"savedPlans":[]},
         "canonicalLedger":{"events":[],"observations":[],"associations":[],
          "components":[],"legs":[],"revisions":[],"snapshots":[],"conflicts":[]}}
        """.trimIndent()

    private fun encryptedBackup(plainJson: String): ByteArray {
        val gzipped = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(plainJson.toByteArray(Charsets.UTF_8)) }
            baos.toByteArray()
        }
        return AtharCrypto.encrypt(gzipped, passphrase)
    }

    // --------------------------------------------- exact content evidence
    // Data-class list equality is a false negative for ByteArray-bearing
    // entities (array identity, not content), and row counts alone cannot
    // prove preservation. Every row is reduced to an ordered tuple of ALL its
    // fields — raw ids, opaque strings, timestamps, and blob bytes via
    // List<Byte> structural equality — sorted by primary key.

    private fun <T> evidence(rows: List<T>, fields: (T) -> List<Any?>): List<List<Any?>> =
        rows.map(fields).sortedBy { row -> row[0]?.toString().orEmpty() }

    private suspend fun fullStateEvidence(db: AtharDatabase): Map<String, List<List<Any?>>> {
        val dao = db.canonicalLedgerDao()
        return mapOf(
            "accounts" to evidence(db.accountDao().all()) {
                listOf(it.id, it.name, it.type, it.currency, it.openingBalanceMinor, it.openingBalanceCurrency, it.smsSenders, it.notes, it.sortOrder, it.active, it.archivedAt, it.createdAt, it.updatedAt)
            },
            "categories" to evidence(db.categoryDao().all()) {
                listOf(it.id, it.name, it.nameAr, it.kind, it.icon, it.monthlyTargetMinor, it.currency, it.archived, it.sortOrder)
            },
            "transactions" to evidence(db.transactionDao().all()) {
                listOf(it.id, it.accountId, it.type, it.amountMinor, it.currency, it.date, it.occurredAt, it.merchant, it.merchantNormalized, it.categoryId, it.notes, it.source, it.sourceRefId, it.status, it.confidence, it.createdAt, it.updatedAt, it.categorySource)
            },
            "wishlist" to evidence(db.wishlistDao().all()) {
                listOf(it.id, it.name, it.costMinor, it.currentSavedMinor, it.currency, it.desiredMonths, it.startYear, it.startMonth, it.notes)
            },
            "categoryRules" to evidence(db.categoryRuleDao().all()) {
                listOf(it.id, it.pattern, it.patternType, it.categoryId, it.priority, it.learnedFromUser, it.createdAt)
            },
            "investmentPools" to evidence(db.investmentDao().allPools()) {
                listOf(it.id, it.name, it.period, it.totalReturnMinor, it.currency)
            },
            "investmentContributions" to evidence(db.investmentDao().allContributions()) {
                listOf(it.id, it.poolId, it.ownerName, it.amountMinor, it.currency)
            },
            "smsAudit" to evidence(db.smsMessageDao().all()) {
                listOf(it.id, it.sender, it.body, it.receivedAt, it.parsedTransactionId, it.parseStatus, it.parseError)
            },
            "receiptAttachments" to evidence(db.transactionReceiptDao().all()) {
                listOf(it.id, it.transactionId, it.mimeType, it.originalName, it.sizeBytes, it.payload.toList(), it.createdAt)
            },
            "canonicalEvents" to evidence(dao.captureEvents()) {
                listOf(it.eventId, it.economicIdentityIssuer, it.economicIdentityReference, it.snapshotFormatVersion, it.policyRevision, it.graphRevision, it.snapshotJson, it.bookedAmount, it.bookedCurrency, it.bookedBasis, it.kind, it.kindResolved, it.incomeNature, it.recordingOutcome, it.eventDate, it.routing, it.composition)
            },
            "canonicalObservations" to evidence(dao.captureObservations()) {
                listOf(it.sourceNamespace, it.sourceId, it.evidenceRef, it.payloadDigest)
            },
            "canonicalAssociations" to evidence(dao.captureAssociations()) {
                listOf(it.sourceNamespace, it.sourceId, it.eventId)
            },
            "canonicalComponents" to evidence(dao.captureComponents()) {
                listOf(it.eventId, it.componentRef, it.type, it.amount, it.currency, it.feeRelation, it.feeEvidenceRef)
            },
            "canonicalLegs" to evidence(dao.captureLegs()) {
                listOf(it.eventId, it.legRef, it.accountRef, it.delta, it.currency, it.componentRef)
            },
            "canonicalSnapshots" to evidence(dao.captureSnapshotVariants()) {
                listOf(it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest, it.sourcePayload.toList(), it.snapshotJson)
            },
            "canonicalConflicts" to evidence(dao.captureConflicts()) {
                listOf(it.conflictId, it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest, it.existingEventId, it.reasonCode, it.detailJson)
            },
            "canonicalRevisions" to evidence(dao.captureRevisions()) {
                listOf(it.id, it.revision)
            },
        )
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun emptyV4RestoreReplacesPopulatedOldStateAndStrictlyAdvancesSingletonEpoch() = runBlocking<Unit> {
        val db = newDb("empty-restore")
        try {
            seedLegacy(db)
            seedCanonical(db)
            db.openHelper.writableDatabase.execSQL(
                "UPDATE canonical_ledger_revision SET revision = 5 WHERE id = 'singleton'",
            )
            // The pre-state must be genuinely populated (with blob-bearing rows),
            // so a vacuous no-op restore cannot satisfy the oracle.
            val dao = db.canonicalLedgerDao()
            assertThat(db.accountDao().all()).hasSize(1)
            assertThat(db.transactionReceiptDao().all()).hasSize(1)
            assertThat(dao.captureEvents()).hasSize(1)
            assertThat(dao.captureSnapshotVariants()).hasSize(1)
            assertThat(dao.getRevision("singleton")!!.revision).isEqualTo(5L)
            val bytes = encryptedBackup(emptyV4Json())

            service(db).import(bytes, passphrase)

            // Whole replace: every legacy and canonical DATA table ends empty,
            // whatever its previous content — restore is a replace, not a merge.
            fullStateEvidence(db).forEach { (table, rows) ->
                if (table == "canonicalRevisions") {
                    // Exactly one revision row survives: the strictly advanced
                    // live singleton. No stale or foreign metadata rows return.
                    assertWithMessage(table).that(rows)
                        .containsExactly(listOf<Any?>("singleton", 6L))
                } else {
                    assertWithMessage(table).that(rows).isEmpty()
                }
            }
            // The live epoch strictly advanced past BOTH the live value (5) and
            // the absent incoming value (0): 5 → 6, never a preserved 5.
            assertThat(dao.getRevision("singleton")!!.revision).isEqualTo(6L)
            assertThat(dao.captureRevisions().map { it.id to it.revision })
                .containsExactly("singleton" to 6L)
        } finally {
            val name = db.openHelper.databaseName
            db.close()
            if (name != null) {
                check(name.startsWith("synthetic-backup-empty-028p-"))
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                check(context.deleteDatabase(name)) { "Owned synthetic database was not removed" }
            }
        }
    }

    @Test
    fun emptyV4RestoreWithLiveMaxEpochRefusesBeforeAnyRowOrBlobChanges() = runBlocking<Unit> {
        val db = newDb("empty-max-refusal")
        try {
            seedLegacy(db)
            seedCanonical(db)
            db.openHelper.writableDatabase.execSQL(
                "UPDATE canonical_ledger_revision SET revision = 9223372036854775807 WHERE id = 'singleton'",
            )
            val before = fullStateEvidence(db)
            val bytes = encryptedBackup(emptyV4Json())

            try {
                service(db).import(bytes, passphrase)
                fail("A live Long.MAX_VALUE epoch cannot strictly advance; even an empty restore must refuse")
            } catch (expected: BackupValidationException) {
                assertThat(expected.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
            }

            // The refusal happened during plan creation, before the first
            // clear: every row of every table — ids, opaque strings, timestamps
            // and both blob payloads — is byte-for-byte unchanged, and the
            // epoch itself is still the pinned Long.MAX_VALUE.
            assertThat(fullStateEvidence(db)).isEqualTo(before)
            assertThat(db.canonicalLedgerDao().getRevision("singleton")!!.revision)
                .isEqualTo(Long.MAX_VALUE)
        } finally {
            val name = db.openHelper.databaseName
            db.close()
            if (name != null) {
                check(name.startsWith("synthetic-backup-empty-028p-"))
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                check(context.deleteDatabase(name)) { "Owned synthetic database was not removed" }
            }
        }
    }
}
