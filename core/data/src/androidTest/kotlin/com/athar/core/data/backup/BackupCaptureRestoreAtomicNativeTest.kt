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
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerConflictEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventSourceEntity
import com.athar.core.data.ledger.db.CanonicalLedgerLegEntity
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity
import com.athar.core.data.ledger.db.CanonicalObservationSnapshotEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Real-Room instrumentation for the backup v4 envelope: production builder
 * (`atharDatabaseBuilder`, migrations + revision-seed onCreate), unique
 * synthetic per-test database files, synthetic rows only — never real
 * financial records. Authored for device execution; not run in this lane.
 */
@RunWith(AndroidJUnit4::class)
class BackupCaptureRestoreAtomicNativeTest {

    private val passphrase = "synthetic-passphrase-028P".toCharArray()

    private fun newDb(tag: String): AtharDatabase =
        atharDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            "synthetic-backup-028p-$tag-${UUID.randomUUID()}.db",
        ).allowMainThreadQueries().build()

    private fun service(db: AtharDatabase) = BackupService(db, Clock.System)

    private val at = Instant.parse("2026-06-13T00:00:00Z")

    // ------------------------------------------------------------- seeding

    private suspend fun seedLegacy(db: AtharDatabase, suffix: String = "1") {
        db.accountDao().upsert(
            AccountEntity(
                id = "acc-$suffix", name = "Cash", type = "CASH", currency = "SAR",
                openingBalanceMinor = 100_00, openingBalanceCurrency = "SAR",
                smsSenders = "", notes = null, sortOrder = 0, active = true,
                archivedAt = null, createdAt = at, updatedAt = at,
            ),
        )
        db.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = "cat-$suffix", name = "Food", nameAr = "طعام", kind = "EXPENSE",
                    icon = null, monthlyTargetMinor = null, currency = "SAR",
                    archived = false, sortOrder = 0,
                ),
            ),
        )
        db.categoryRuleDao().upsert(
            CategoryRuleEntity(
                id = "rule-$suffix", pattern = "store", patternType = "CONTAINS",
                categoryId = "cat-$suffix", priority = 1, learnedFromUser = false, createdAt = at,
            ),
        )
        db.transactionDao().upsert(
            TransactionEntity(
                id = "tx-$suffix", accountId = "acc-$suffix", type = "EXPENSE",
                amountMinor = 12_00, currency = "SAR", date = LocalDate.parse("2026-06-13"),
                occurredAt = null, merchant = "Store", merchantNormalized = "store",
                categoryId = "cat-$suffix", notes = null, source = "MANUAL", sourceRefId = null,
                status = "CONFIRMED", confidence = null, createdAt = at, updatedAt = at,
                categorySource = "USER",
            ),
        )
        db.investmentDao().upsertPool(
            InvestmentPoolEntity(id = "pool-$suffix", name = "Pool", period = "MONTHLY", totalReturnMinor = 0L, currency = "SAR"),
        )
        db.investmentDao().upsertContribution(
            InvestmentContributionEntity(id = "con-$suffix", poolId = "pool-$suffix", ownerName = "Owner", amountMinor = 5_00, currency = "SAR"),
        )
        db.smsMessageDao().upsert(
            SmsMessageEntity(id = "sms-$suffix", sender = "BANK", body = "synthetic", receivedAt = at, parsedTransactionId = null, parseStatus = "UNPARSED", parseError = null),
        )
        db.transactionReceiptDao().upsert(
            TransactionReceiptEntity(id = "rc-$suffix", transactionId = "tx-$suffix", mimeType = "image/png", originalName = null, sizeBytes = 4L, payload = byteArrayOf(1, 2, 3, 4), createdAt = at),
        )
    }

    private suspend fun seedCanonical(db: AtharDatabase, accountRef: String = "acc-1") {
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
            CanonicalLedgerLegEntity(eventId = "n-event-1", legRef = "leg-1", accountRef = accountRef, delta = "-0001.2300", currency = "KWD", componentRef = "cmp-1"),
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

    // Exact preservation evidence, not counts: every row of every cleared-or-
    // touched table reduced to an ordered tuple of ALL its fields — raw ids,
    // opaque strings, timestamps, and blob bytes via List<Byte> structural
    // equality (data-class list equality is a false negative for
    // ByteArray-bearing entities, since arrays compare by reference).
    private fun <T> evidence(rows: List<T>, fields: (T) -> List<Any?>): List<List<Any?>> =
        rows.map(fields).sortedBy { row -> row[0]?.toString().orEmpty() }

    private suspend fun fullStateEvidence(db: AtharDatabase): Map<String, List<List<Any?>>> {
        val canonical = db.canonicalLedgerDao()
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
            "canonicalEvents" to evidence(canonical.captureEvents()) {
                listOf(it.eventId, it.economicIdentityIssuer, it.economicIdentityReference, it.snapshotFormatVersion, it.policyRevision, it.graphRevision, it.snapshotJson, it.bookedAmount, it.bookedCurrency, it.bookedBasis, it.kind, it.kindResolved, it.incomeNature, it.recordingOutcome, it.eventDate, it.routing, it.composition)
            },
            "canonicalObservations" to evidence(canonical.captureObservations()) {
                listOf(it.sourceNamespace, it.sourceId, it.evidenceRef, it.payloadDigest)
            },
            "canonicalAssociations" to evidence(canonical.captureAssociations()) {
                listOf(it.sourceNamespace, it.sourceId, it.eventId)
            },
            "canonicalComponents" to evidence(canonical.captureComponents()) {
                listOf(it.eventId, it.componentRef, it.type, it.amount, it.currency, it.feeRelation, it.feeEvidenceRef)
            },
            "canonicalLegs" to evidence(canonical.captureLegs()) {
                listOf(it.eventId, it.legRef, it.accountRef, it.delta, it.currency, it.componentRef)
            },
            "canonicalSnapshots" to evidence(canonical.captureSnapshotVariants()) {
                listOf(it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest, it.sourcePayload.toList(), it.snapshotJson)
            },
            "canonicalConflicts" to evidence(canonical.captureConflicts()) {
                listOf(it.conflictId, it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest, it.existingEventId, it.reasonCode, it.detailJson)
            },
            "canonicalRevisions" to evidence(canonical.captureRevisions()) {
                listOf(it.id, it.revision)
            },
        ) + BackupAuxiliaryNativeFixtures.evidence(db)
    }

    // Byte-array-bearing entities must be compared by field and byte content,
    // never by reference-based data-class equality.
    private fun canonicalSnapshotEvidence(rows: List<CanonicalObservationSnapshotEntity>): List<List<Any?>> =
        evidence(rows) {
            listOf(it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest, it.sourcePayload.toList(), it.snapshotJson)
        }

    private fun receiptEvidence(rows: List<TransactionReceiptEntity>): List<List<Any?>> =
        evidence(rows) {
            listOf(it.id, it.transactionId, it.mimeType, it.originalName, it.sizeBytes, it.payload.toList(), it.createdAt)
        }

    private fun encryptedBackup(plainJson: String): ByteArray {
        val gzipped = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(plainJson.toByteArray(Charsets.UTF_8)) }
            baos.toByteArray()
        }
        return AtharCrypto.encrypt(gzipped, passphrase)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun captureThenRestoreIntoSecondDatabaseReplacesEverythingAndPreservesRowsExactly() = runBlocking {
        val source = newDb("source")
        seedLegacy(source)
        seedCanonical(source)
        BackupAuxiliaryNativeFixtures.seed(source, "source", "acc-1", "cat-1")

        val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

        val target = newDb("target")
        // Conflicting pre-existing state proves the restore is a whole replace,
        // not a merge: different legacy ids plus a stale canonical event.
        seedLegacy(target, suffix = "9")
        BackupAuxiliaryNativeFixtures.seed(target, "target", "acc-9", "cat-9")
        target.canonicalLedgerDao().insertEvent(
            CanonicalLedgerEventEntity(
                eventId = "stale-event", economicIdentityIssuer = null, economicIdentityReference = null,
                snapshotFormatVersion = 1L, policyRevision = "p-0", graphRevision = "g-0",
                snapshotJson = "{}", bookedAmount = "0.00", bookedCurrency = "USD",
                bookedBasis = "ORIGINAL", kind = "K", kindResolved = true, incomeNature = "N",
                recordingOutcome = "O", eventDate = null, routing = "R", composition = "C",
            ),
        )

        service(target).import(bytes, passphrase)

        val s = source.canonicalLedgerDao()
        val t = target.canonicalLedgerDao()
        assertThat(t.captureEvents()).isEqualTo(s.captureEvents())
        assertThat(t.captureObservations()).isEqualTo(s.captureObservations())
        assertThat(t.captureAssociations()).isEqualTo(s.captureAssociations())
        assertThat(t.captureComponents()).isEqualTo(s.captureComponents())
        assertThat(t.captureLegs()).isEqualTo(s.captureLegs())
        // Snapshot variants carry a ByteArray payload: compared by field and
        // byte content (reference-based data-class equality is a false
        // negative across two databases).
        assertThat(canonicalSnapshotEvidence(t.captureSnapshotVariants()))
            .isEqualTo(canonicalSnapshotEvidence(s.captureSnapshotVariants()))
        assertThat(t.captureConflicts()).isEqualTo(s.captureConflicts())
        assertThat(t.captureEvents().none { it.eventId == "stale-event" }).isTrue()

        // Exact money spellings and literal currencies survive the round trip.
        assertThat(t.captureEvents().single().bookedAmount).isEqualTo("0001.2300")
        assertThat(t.captureComponents().single().amount).isEqualTo("1E+3")
        assertThat(t.captureComponents().single().currency).isEqualTo("KWD")
        assertThat(t.captureLegs().single().delta).isEqualTo("-0001.2300")

        assertThat(BackupAuxiliaryNativeFixtures.evidence(target))
            .isEqualTo(BackupAuxiliaryNativeFixtures.evidence(source))
        assertThat(target.wishlistDao().all()).isEqualTo(source.wishlistDao().all())
        assertThat(target.accountDao().all()).isEqualTo(source.accountDao().all())
        assertThat(target.categoryDao().all()).isEqualTo(source.categoryDao().all())
        assertThat(target.transactionDao().all()).isEqualTo(source.transactionDao().all())
        assertThat(target.categoryRuleDao().all()).isEqualTo(source.categoryRuleDao().all())
        assertThat(target.investmentDao().allPools()).isEqualTo(source.investmentDao().allPools())
        assertThat(target.investmentDao().allContributions()).isEqualTo(source.investmentDao().allContributions())
        assertThat(target.smsMessageDao().all()).isEqualTo(source.smsMessageDao().all())
        // Receipt attachments carry a ByteArray payload: compared by field and
        // byte content, never by reference-based data-class equality.
        assertThat(receiptEvidence(target.transactionReceiptDao().all()))
            .isEqualTo(receiptEvidence(source.transactionReceiptDao().all()))

        source.close()
        target.close()
    }

    @Test
    fun legacyRestoreRefusedWhileCanonicalRowsExistLeavesEveryRowUntouched() = runBlocking {
        val db = newDb("mixing")
        seedLegacy(db)
        seedCanonical(db)
        val before = fullStateEvidence(db)

        val v3Json =
            """
            {"version":3,"exportedAt":"2026-01-01T00:00:00Z","accounts":[],"categories":[],
             "transactions":[],"categoryRules":[],"wishlist":[],"investmentPools":[],
             "investmentContributions":[],"smsAudit":[]}
            """.trimIndent()

        try {
            service(db).import(encryptedBackup(v3Json), passphrase)
            fail("Legacy restore must be refused while canonical rows exist")
        } catch (expected: BackupValidationException) {
            assertThat(expected.code).isEqualTo(BackupValidationCode.CANONICAL_LEDGER_PRESENT)
        }

        assertThat(fullStateEvidence(db)).isEqualTo(before)
        db.close()
    }

    @Test
    fun restoredRevisionCounterNeverRollsBackwardOrReusesStaleValue() = runBlocking {
        val source = newDb("rev-source")
        seedLegacy(source)
        seedCanonical(source)
        source.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 900 WHERE id = 'singleton'",
        )
        val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

        // Local counter lower than the incoming one strictly advances one past
        // BOTH values (max(current, incoming) + 1): the incoming epoch 900 is
        // never reused verbatim, so no graph state can replay it.
        val target = newDb("rev-target")
        target.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 50 WHERE id = 'singleton'",
        )
        service(target).import(bytes, passphrase)
        assertThat(target.canonicalLedgerDao().getRevision("singleton")!!.revision).isEqualTo(901L)

        // Local counter higher than the incoming one never rolls backward and
        // still strictly advances past the live epoch (1200 → 1201).
        val target2 = newDb("rev-target2")
        target2.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 1200 WHERE id = 'singleton'",
        )
        service(target2).import(bytes, passphrase)
        assertThat(target2.canonicalLedgerDao().getRevision("singleton")!!.revision).isEqualTo(1201L)

        source.close()
        target.close()
        target2.close()
    }

    @Test
    fun invalidV4PayloadFailsBeforeAnyWriteAndLeavesDatabaseUntouched() = runBlocking {
        val db = newDb("invalid")
        seedLegacy(db)
        seedCanonical(db)
        val before = fullStateEvidence(db)

        val brokenV4 =
            """
            {"version":4,"exportedAt":"2026-01-01T00:00:00Z","accounts":[],"categories":[],
             "transactions":[],"categoryRules":[],"wishlist":[],"investmentPools":[],
             "investmentContributions":[],"smsAudit":[],
             "canonicalLedger":{"events":[],"observations":[],
              "associations":[{"sourceNamespace":"sms","sourceId":"s1","eventId":"ghost"}],
              "components":[],"legs":[],"revisions":[],"snapshots":[],"conflicts":[]}}
            """.trimIndent()

        try {
            service(db).import(encryptedBackup(brokenV4), passphrase)
            fail("Dangling canonical reference must be rejected before any write")
        } catch (expected: BackupValidationException) {
            assertThat(expected.code).isEqualTo(BackupValidationCode.CANONICAL_REFERENCE_MISSING)
        }

        assertThat(fullStateEvidence(db)).isEqualTo(before)
        db.close()
    }

    // -------------------------------------------------- FK-populated regression

    /**
     * Seeds a stale canonical graph whose leg attributes to a same-event
     * component — the exact FK-populated shape (canonical_ledger_leg →
     * canonical_ledger_component, NO ACTION composite FK) that made
     * components-before-legs clearing fail with `FOREIGN KEY constraint
     * failed`. `accountRef` must be an account already seeded in [db].
     */
    private suspend fun seedStaleAttributedGraph(db: AtharDatabase, suffix: String) {
        val dao = db.canonicalLedgerDao()
        dao.insertEvent(
            CanonicalLedgerEventEntity(
                eventId = "t-event-$suffix", economicIdentityIssuer = null, economicIdentityReference = null,
                snapshotFormatVersion = 1L, policyRevision = "t-p-$suffix", graphRevision = "t-g-$suffix",
                snapshotJson = "{}", bookedAmount = "0.00", bookedCurrency = "SAR",
                bookedBasis = "ORIGINAL", kind = "K", kindResolved = true, incomeNature = "N",
                recordingOutcome = "O", eventDate = null, routing = "R", composition = "C",
            ),
        )
        dao.insertComponent(
            CanonicalLedgerComponentEntity(
                eventId = "t-event-$suffix", componentRef = "t-cmp-$suffix", type = "FUTURE_COMPONENT",
                amount = "1E+3", currency = "KWD", feeRelation = "UNKNOWN", feeEvidenceRef = null,
            ),
        )
        dao.insertLeg(
            CanonicalLedgerLegEntity(
                eventId = "t-event-$suffix", legRef = "t-leg-$suffix", accountRef = "acc-$suffix",
                delta = "-0001.2300", currency = "KWD", componentRef = "t-cmp-$suffix",
            ),
        )
    }

    @Test
    fun restoreOverFKPopulatedTargetClearsAttributedLegsBeforeComponents() = runBlocking {
        val source = newDb("fk-source")
        seedLegacy(source)
        seedCanonical(source)
        val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

        // The target already holds a canonical graph with an attributed leg, so
        // the replace must clear legs before their components. Under the former
        // components-first order this import died on `FOREIGN KEY constraint
        // failed` before any legacy row moved; child-first clearing must pass.
        val target = newDb("fk-target")
        seedLegacy(target, suffix = "9")
        seedStaleAttributedGraph(target, suffix = "9")
        val localRevisionBefore = target.canonicalLedgerDao().getRevision("singleton")?.revision ?: 0L

        service(target).import(bytes, passphrase)

        val s = source.canonicalLedgerDao()
        val t = target.canonicalLedgerDao()
        // The stale attributed graph is fully replaced, never merged.
        assertThat(t.captureEvents().map { it.eventId })
            .containsExactlyElementsIn(s.captureEvents().map { it.eventId })
        assertThat(t.captureComponents().map { it.componentRef })
            .containsExactlyElementsIn(s.captureComponents().map { it.componentRef })
        assertThat(t.captureLegs().map { it.legRef })
            .containsExactlyElementsIn(s.captureLegs().map { it.legRef })
        assertThat(t.captureEvents().none { it.eventId == "t-event-9" }).isTrue()
        assertThat(t.captureLegs().none { it.legRef == "t-leg-9" }).isTrue()
        // The restored leg keeps its component attribution.
        assertThat(t.captureLegs().single().componentRef).isEqualTo("cmp-1")
        // Restore never rolls the local revision counter backward.
        assertThat(t.getRevision("singleton")!!.revision).isAtLeast(localRevisionBefore)

        source.close()
        target.close()
    }

    // --------------------------------- B3 atomic eligibility / strict advance

    @Test
    fun restoreOverPopulatedTargetStrictlyAdvancesPastBothCounters() = runBlocking {
        val source = newDb("strict-source")
        seedLegacy(source)
        seedCanonical(source)
        source.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 900 WHERE id = 'singleton'",
        )
        val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

        // Live epoch (1200) higher than the incoming one (900): the former
        // max-only policy rewrote 1200, reusing the live epoch for a different
        // graph. The strict policy must land exactly one past both values
        // while still fully replacing the populated stale graph.
        val target = newDb("strict-target")
        seedLegacy(target, suffix = "9")
        seedStaleAttributedGraph(target, suffix = "9")
        target.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 1200 WHERE id = 'singleton'",
        )

        service(target).import(bytes, passphrase)

        val t = target.canonicalLedgerDao()
        assertThat(t.getRevision("singleton")!!.revision).isEqualTo(1201L)
        assertThat(t.captureLegs().none { it.legRef == "t-leg-9" }).isTrue()

        source.close()
        target.close()
    }

    @Test
    fun maxRevisionOverflowFailsBeforeAnyClearAndPreservesOldState() = runBlocking {
        // Incoming carries Long.MAX_VALUE (captured from a source pinned at
        // the ceiling): a strict advance cannot exist, so the restore must
        // abort during plan creation, before the first clear, leaving the old
        // graph, legacy rows and child data byte-identical.
        val source = newDb("max-source")
        seedLegacy(source)
        seedCanonical(source)
        source.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 9223372036854775807 WHERE id = 'singleton'",
        )
        val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

        val target = newDb("max-target")
        seedLegacy(target, suffix = "9")
        seedStaleAttributedGraph(target, suffix = "9")
        val before = fullStateEvidence(target)

        try {
            service(target).import(bytes, passphrase)
            fail("A Long.MAX_VALUE epoch cannot strictly advance; restore must refuse")
        } catch (expected: BackupValidationException) {
            assertThat(expected.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
        }
        assertThat(fullStateEvidence(target)).isEqualTo(before)

        // A live epoch pinned at the ceiling with a small incoming payload is
        // the same overflow and must fail just as closed.
        val smallSource = newDb("max-small-source")
        seedLegacy(smallSource)
        seedCanonical(smallSource)
        val smallBytes = ByteArrayOutputStream().also { service(smallSource).export(it, passphrase) }.toByteArray()

        val target2 = newDb("max-target2")
        seedLegacy(target2, suffix = "9")
        seedCanonical(target2, accountRef = "acc-9")
        target2.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 9223372036854775807 WHERE id = 'singleton'",
        )
        val before2 = fullStateEvidence(target2)

        try {
            service(target2).import(smallBytes, passphrase)
            fail("A live Long.MAX_VALUE epoch cannot strictly advance; restore must refuse")
        } catch (expected: BackupValidationException) {
            assertThat(expected.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
        }
        assertThat(fullStateEvidence(target2)).isEqualTo(before2)

        source.close()
        smallSource.close()
        target.close()
        target2.close()
    }

    @Test
    fun concurrentAdmissionDuringOpenRestoreTransactionIsNotLostOrReused() = runBlocking {
        val source = newDb("race-source")
        seedLegacy(source)
        seedCanonical(source)
        source.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 900 WHERE id = 'singleton'",
        )
        val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

        val target = newDb("race-target")
        seedLegacy(target, suffix = "9")
        seedStaleAttributedGraph(target, suffix = "9")
        target.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 50 WHERE id = 'singleton'",
        )

        // Narrow scope: this proves only that a revision admission queued while
        // the restore holds its write transaction (the probe has already passed
        // the live reads and the epoch decision, INSIDE the transaction)
        // serializes on top of the restored epoch instead of being erased. It
        // does NOT exercise the full admission repository path, and it cannot
        // distinguish stale reads taken BEFORE the transaction opened; broader
        // concurrency acceptance is out of scope for this oracle.
        val planVisible = CountDownLatch(1)
        val releaseRestore = CountDownLatch(1)
        val restoreService = service(target)
        restoreService.restoreTransactionProbe = {
            planVisible.countDown()
            check(releaseRestore.await(10, TimeUnit.SECONDS)) { "Restore barrier timed out" }
        }

        val importJob = async(Dispatchers.IO) { restoreService.import(bytes, passphrase) }
        var admissionResult: Int? = null
        try {
            assertThat(planVisible.await(10, TimeUnit.SECONDS)).isTrue()
            val admission = async(Dispatchers.IO) {
                target.canonicalLedgerDao().incrementRevision("singleton")
            }
            releaseRestore.countDown()
            // Bounded waits throughout: a wedged restore or admission fails the
            // test instead of hanging the whole instrumented run.
            admissionResult = withTimeout(10_000) { admission.await() }
            withTimeout(10_000) { importJob.await() }
        } finally {
            // Idempotent: guarantees the restore is never left parked on the
            // probe when any await times out or throws.
            releaseRestore.countDown()
            runCatching { importJob.cancel() }
        }

        // Restore advanced 50 past incoming 900 to 901; the queued admission
        // landed on top, so the final epoch is 902 and the admission provably
        // applied (rowcount 1), not overwritten by the restored row.
        assertThat(admissionResult).isEqualTo(1)
        assertThat(target.canonicalLedgerDao().getRevision("singleton")!!.revision)
            .isEqualTo(902L)
        assertThat(target.canonicalLedgerDao().captureLegs().none { it.legRef == "t-leg-9" }).isTrue()

        source.close()
        target.close()
    }
}
