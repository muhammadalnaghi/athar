package com.athar.core.data.backup

import com.athar.core.data.ledger.backup.CanonicalBackupAssociation
import com.athar.core.data.ledger.backup.CanonicalBackupRevision
import com.athar.core.data.ledger.backup.CanonicalBackupSection
import com.athar.core.data.ledger.backup.canonicalBackupFixture
import com.athar.core.data.ledger.backup.toRows
import com.athar.core.data.ledger.backup.toSection
import com.athar.core.data.ledger.db.CanonicalLedgerRevisionEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Backup v4 envelope + validation tests (pure JVM, no Room, no crypto).
 * Authored for `testDebugUnitTest`; not executed in this delivery lane.
 */
class BackupV4EnvelopeValidationTest {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------ fixtures

    private fun account(id: String = "a-1") = BackupAccount(
        id = id, name = "Cash", type = "CASH", currency = "SAR", smsSenders = "",
        active = true, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun category(id: String = "c-1") = BackupCategory(
        id = id, name = "Food", nameAr = "طعام", kind = "EXPENSE",
        icon = null, monthlyTargetMinor = null, currency = "SAR", archived = false, sortOrder = 0,
    )

    private fun transaction(id: String = "t-1", accountId: String = "a-1") = BackupTransaction(
        id = id, accountId = accountId, type = "EXPENSE", amountMinor = 12_00, currency = "SAR",
        date = LocalDate.parse("2026-06-13"), occurredAt = null, merchant = "Store",
        merchantNormalized = "store", categoryId = null, notes = null, source = "MANUAL",
        sourceRefId = null, status = "CONFIRMED", confidence = null,
        createdAt = Instant.parse("2026-06-13T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-13T00:00:00Z"),
    )

    private fun rule(id: String = "r-1", categoryId: String = "c-1") = BackupCategoryRule(
        id = id, pattern = "store", patternType = "CONTAINS", categoryId = categoryId,
        priority = 1, learnedFromUser = false, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun pool(id: String = "p-1") = BackupInvestmentPool(id, "Pool", "MONTHLY", 0L, "SAR")

    private fun contribution(id: String = "k-1", poolId: String = "p-1") =
        BackupInvestmentContribution(id, poolId, "Owner", 5_00, "SAR")

    private fun receipt(id: String = "rc-1", transactionId: String = "t-1") = BackupReceiptAttachment(
        id = id, transactionId = transactionId, mimeType = "image/png", originalName = null,
        sizeBytes = 4L, payloadBase64 = "AAAA", createdAt = Instant.parse("2026-06-13T00:00:00Z"),
    )

    private fun legacySnapshot(
        version: Int = BackupSnapshot.CURRENT_VERSION,
        accounts: List<BackupAccount> = listOf(account()),
        canonicalLedger: CanonicalBackupSection? = validSection(),
    ) = BackupSnapshot(
        version = version,
        exportedAt = Instant.parse("2026-06-14T00:00:00Z"),
        accounts = accounts,
        categories = listOf(category()),
        transactions = listOf(transaction(accountId = accounts.firstOrNull()?.id ?: "a-1")),
        categoryRules = listOf(rule()),
        wishlist = emptyList(),
        investmentPools = listOf(pool()),
        investmentContributions = listOf(contribution()),
        smsAudit = emptyList(),
        receiptAttachments = listOf(receipt()),
        canonicalLedger = canonicalLedger,
        auxiliaryTables = if (version == BackupSnapshot.CURRENT_VERSION) {
            BackupAuxiliarySection(emptyList(), emptyList(), emptyList(), savedPlans = emptyList())
        } else null,
    )

    private fun emptySection(
        revisions: List<CanonicalBackupRevision> = listOf(CanonicalBackupRevision("singleton", 0)),
    ) = CanonicalBackupSection(
        events = emptyList(), observations = emptyList(), associations = emptyList(),
        components = emptyList(), legs = emptyList(), revisions = revisions,
        snapshots = emptyList(), conflicts = emptyList(),
    )

    /** All-empty but structurally valid. */
    private fun validSection() = emptySection()

    private fun fixtureSection(): CanonicalBackupSection = canonicalBackupFixture().toSection()

    private fun localSingleton(value: Long) = listOf(CanonicalBackupRevision("singleton", value))

    // This canonical-focused fixture has no auxiliary live state. Production has no default.
    private fun validate(
        snapshot: BackupSnapshot,
        localRevisions: List<CanonicalBackupRevision>,
        liveCanonicalDataRowCount: Long,
    ): BackupRestorePlan = BackupRestoreValidator.validate(
        snapshot, localRevisions, liveCanonicalDataRowCount, liveAuxiliaryDataPresent = false,
    )

    // ------------------------------------------------- explicit version gate

    @Test
    fun `missing version field fails closed`() {
        val error = assertThrows<BackupValidationException> {
            BackupRestoreValidator.resolveVersion("""{"exportedAt":"2026-06-14T00:00:00Z"}""")
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.MISSING_VERSION)
    }

    @Test
    fun `nonnumeric version fails closed`() {
        val error = assertThrows<BackupValidationException> {
            BackupRestoreValidator.resolveVersion("""{"version":"4","exportedAt":"2026-06-14T00:00:00Z"}""")
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.UNSUPPORTED_VERSION)
    }

    @Test
    fun `fractional version fails closed`() {
        val error = assertThrows<BackupValidationException> {
            BackupRestoreValidator.resolveVersion("""{"version":4.5,"exportedAt":"2026-06-14T00:00:00Z"}""")
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.UNSUPPORTED_VERSION)
    }

    @Test
    fun `versions outside the supported range fail closed`() {
        listOf(0L, 5L, -1L, Long.MAX_VALUE).forEach { version ->
            val error = assertThrows<BackupValidationException> {
                BackupRestoreValidator.resolveVersion("""{"version":$version,"exportedAt":"2026-06-14T00:00:00Z"}""")
            }
            assertThat(error.code).isEqualTo(BackupValidationCode.UNSUPPORTED_VERSION)
        }
    }

    @Test
    fun `supported versions resolve explicitly`() {
        listOf(1L, 2L, 3L, 4L).forEach { version ->
            val resolved = BackupRestoreValidator.resolveVersion(
                """{"version":$version,"exportedAt":"2026-06-14T00:00:00Z"}""",
            )
            assertThat(resolved).isEqualTo(version.toInt())
        }
    }

    @Test
    fun `all noninteger version shapes are typed unsupported errors`() {
        listOf("null", "true", "[]", "{}", "4.0", "4e0", "9223372036854775808").forEach { token ->
            try {
                val accepted = BackupRestoreValidator.resolveVersion("{\"version\":$token}")
                throw AssertionError("Noninteger version token $token was accepted as $accepted")
            } catch (expected: BackupValidationException) {
                assertThat(expected.code).isEqualTo(BackupValidationCode.UNSUPPORTED_VERSION)
            }
        }
    }

    // ---------------------------------------------- explicit compatibility

    @Test
    fun `v4 snapshot without a canonical ledger is rejected`() {
        val error = assertThrows<BackupValidationException> {
            validate(
                snapshot = legacySnapshot(canonicalLedger = null),
                localRevisions = emptyList(),
                liveCanonicalDataRowCount = 0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.V4_REQUIRES_CANONICAL_LEDGER)
    }

    @Test
    fun `legacy snapshot cannot silently carry a canonical ledger`() {
        val error = assertThrows<BackupValidationException> {
            validate(
                snapshot = legacySnapshot(version = 3, canonicalLedger = validSection()),
                localRevisions = emptyList(),
                liveCanonicalDataRowCount = 0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.LEGACY_CANNOT_CARRY_CANONICAL_LEDGER)
    }

    @Test
    fun `legacy restore is refused while the database holds canonical rows`() {
        val error = assertThrows<BackupValidationException> {
            validate(
                snapshot = legacySnapshot(version = 3, canonicalLedger = null),
                localRevisions = localSingleton(4L),
                liveCanonicalDataRowCount = 2L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_LEDGER_PRESENT)
    }

    @Test
    fun `legacy restore into a canonical-free database yields a legacy-only plan`() {
        val plan = validate(
            snapshot = legacySnapshot(version = 3, canonicalLedger = null),
            localRevisions = localSingleton(4L),
            liveCanonicalDataRowCount = 0L,
        )
        assertThat(plan.canonicalReplaced).isFalse()
        assertThat(plan.revisionRows).isEmpty()
        assertThat(plan.canonicalRows.events).isEmpty()
        assertThat(plan.canonicalRows.conflicts).isEmpty()
    }

    @Test
    fun `v4 encode always embeds the canonical section`() {
        val encoded = json.encodeToString(BackupSnapshot.serializer(), legacySnapshot())
        assertThat(encoded).contains(""""version":4""")
        assertThat(encoded).contains(""""canonicalLedger"""")
    }

    // ------------------------------------------ canonical resource validation

    @Test
    fun `association referencing a missing event is rejected`() {
        val section = validSection().copy(
            associations = listOf(CanonicalBackupAssociation("sms", "s-1", "ghost")),
        )
        val error = assertThrows<BackupValidationException> {
            validate(legacySnapshot(canonicalLedger = section), emptyList(), 0L)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_REFERENCE_MISSING)
    }

    @Test
    fun `association referencing a missing observation is rejected`() {
        val fixture = canonicalBackupFixture()
        val section = fixture.toSection().let { rows ->
            rows.copy(associations = rows.associations.map { it.copy(sourceId = "missing-observation") })
        }
        val error = assertThrows<BackupValidationException> {
            validate(
                legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = section),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_REFERENCE_MISSING)
    }

    @Test
    fun `leg referencing an account absent from the snapshot accounts is rejected`() {
        val section = fixtureSection()
        val error = assertThrows<BackupValidationException> {
            validate(legacySnapshot(canonicalLedger = section), emptyList(), 0L)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_REFERENCE_MISSING)
        assertThat(error.message).contains("account-a")
    }

    @Test
    fun `leg referencing a missing component is rejected`() {
        val section = fixtureSection().copy(components = emptyList())
        val error = assertThrows<BackupValidationException> {
            validate(
                legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = section),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_REFERENCE_MISSING)
    }

    @Test
    fun `snapshot variant referencing a missing observation is rejected`() {
        val section = fixtureSection().let { rows ->
            rows.copy(snapshots = rows.snapshots.map { it.copy(sourceId = "missing-observation") })
        }
        val error = assertThrows<BackupValidationException> {
            validate(
                legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = section),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_REFERENCE_MISSING)
    }

    @Test
    fun `conflict referencing a missing existing event is rejected`() {
        val section = fixtureSection().let { rows ->
            rows.copy(conflicts = rows.conflicts.map { it.copy(existingEventId = "missing-existing-event") })
        }
        val error = assertThrows<BackupValidationException> {
            validate(
                legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = section),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_REFERENCE_MISSING)
    }

    @Test
    fun `duplicate canonical keys are rejected`() {
        val duplicated = canonicalBackupFixture()
        val section = duplicated.copy(events = duplicated.events + duplicated.events.single()).toSection()
        val error = assertThrows<BackupValidationException> {
            validate(
                legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = section),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.DUPLICATE_BACKUP_KEY)
    }

    @Test
    fun `complete mixed legacy and canonical snapshot validates with a full replace plan`() {
        val rows = canonicalBackupFixture().copy(
            revisions = listOf(CanonicalLedgerRevisionEntity("singleton", 7L)),
        )
        val plan = validate(
            snapshot = legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = rows.toSection()),
            localRevisions = localSingleton(1L),
            liveCanonicalDataRowCount = 6L,
        )
        assertThat(plan.canonicalReplaced).isTrue()
        assertThat(plan.canonicalRows.events.single().eventId).isEqualTo("event-a")
        assertThat(plan.canonicalRows.conflicts.single().conflictId).isEqualTo("conflict-a")
        // Live epoch advances, while the imported transport metadata stays exact.
        assertThat(plan.revisionRows.single().revision).isEqualTo(8L)
        assertThat(plan.snapshot.canonicalLedger!!.revisions.single().revision).isEqualTo(7L)
    }

    // ------------------------------------------------ legacy resource checks

    @Test
    fun `transaction referencing a missing account is rejected`() {
        val snapshot = legacySnapshot().copy(transactions = listOf(transaction(accountId = "ghost")))
        val error = assertThrows<BackupValidationException> {
            validate(snapshot, emptyList(), 0L)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.LEGACY_REFERENCE_MISSING)
    }

    @Test
    fun `rule referencing a missing category is rejected`() {
        val snapshot = legacySnapshot().copy(categoryRules = listOf(rule(categoryId = "ghost")))
        val error = assertThrows<BackupValidationException> {
            validate(snapshot, emptyList(), 0L)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.LEGACY_REFERENCE_MISSING)
    }

    @Test
    fun `contribution referencing a missing pool is rejected`() {
        val snapshot = legacySnapshot().copy(investmentContributions = listOf(contribution(poolId = "ghost")))
        val error = assertThrows<BackupValidationException> {
            validate(snapshot, emptyList(), 0L)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.LEGACY_REFERENCE_MISSING)
    }

    @Test
    fun `receipt referencing a missing transaction is rejected`() {
        val snapshot = legacySnapshot().copy(receiptAttachments = listOf(receipt(transactionId = "ghost")))
        val error = assertThrows<BackupValidationException> {
            validate(snapshot, emptyList(), 0L)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.LEGACY_REFERENCE_MISSING)
    }

    @Test
    fun `duplicate legacy account ids are rejected`() {
        val snapshot = legacySnapshot().copy(accounts = listOf(account(), account()))
        val error = assertThrows<BackupValidationException> {
            validate(snapshot, emptyList(), 0L)
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.DUPLICATE_BACKUP_KEY)
    }

    // ---------------------------------------------------- revision monotonicity

    @Test
    fun `restored revision never rolls backward`() {
        val plan = validate(
            snapshot = legacySnapshot(canonicalLedger = emptySection(revisions = localSingleton(3L))),
            localRevisions = localSingleton(5L),
            liveCanonicalDataRowCount = 0L,
        )
        assertThat(plan.revisionRows.single { it.id == "singleton" }.revision).isEqualTo(6L)
    }

    @Test
    fun `restored revision advances when the backup is newer`() {
        val plan = validate(
            snapshot = legacySnapshot(canonicalLedger = emptySection(revisions = localSingleton(7L))),
            localRevisions = localSingleton(2L),
            liveCanonicalDataRowCount = 0L,
        )
        assertThat(plan.revisionRows.single { it.id == "singleton" }.revision).isEqualTo(8L)
    }

    @Test
    fun `local-only revision counters survive a restore`() {
        val local = listOf(CanonicalBackupRevision("singleton", 5L), CanonicalBackupRevision("extra", 9L))
        val plan = validate(
            snapshot = legacySnapshot(canonicalLedger = emptySection(revisions = localSingleton(3L))),
            localRevisions = local,
            liveCanonicalDataRowCount = 0L,
        )
        assertThat(plan.revisionRows.map { it.id to it.revision })
            .containsExactly("extra" to 9L, "singleton" to 6L)
    }

    @Test
    fun `negative revision fails closed`() {
        val error = assertThrows<BackupValidationException> {
            validate(
                snapshot = legacySnapshot(
                    canonicalLedger = emptySection(revisions = listOf(CanonicalBackupRevision("singleton", -1L))),
                ),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
    }

    @Test
    fun `canonical rows without the singleton counter fail closed`() {
        val fixture = canonicalBackupFixture()
        val section = fixture.copy(revisions = emptyList()).toSection()
        val error = assertThrows<BackupValidationException> {
            validate(
                legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = section),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
    }

    @Test
    fun `duplicate revision ids fail closed`() {
        val error = assertThrows<BackupValidationException> {
            validate(
                snapshot = legacySnapshot(
                    canonicalLedger = emptySection(
                        revisions = listOf(
                            CanonicalBackupRevision("singleton", 1L),
                            CanonicalBackupRevision("singleton", 2L),
                        ),
                    ),
                ),
                emptyList(),
                0L,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.DUPLICATE_BACKUP_KEY)
    }

    // ------------------------------------------------------------ exactness

    @Test
    fun `v4 round trip preserves exact money spellings literal currencies and opaque json`() {
        val rows = canonicalBackupFixture()
        val snapshot = legacySnapshot(accounts = listOf(account("account-a")), canonicalLedger = rows.toSection())
        val decoded = json.decodeFromString(
            BackupSnapshot.serializer(),
            json.encodeToString(BackupSnapshot.serializer(), snapshot),
        )
        val decodedRows = decoded.canonicalLedger!!.toRows()

        assertThat(decoded.version).isEqualTo(4)
        assertThat(decodedRows.events).isEqualTo(rows.events)
        assertThat(decodedRows.observations).isEqualTo(rows.observations)
        assertThat(decodedRows.associations).isEqualTo(rows.associations)
        assertThat(decodedRows.components).isEqualTo(rows.components)
        assertThat(decodedRows.legs).isEqualTo(rows.legs)
        assertThat(decodedRows.conflicts).isEqualTo(rows.conflicts)

        // Byte arrays need element-wise equality; the opaque payloads stay untouched.
        assertThat(decodedRows.snapshots).hasSize(rows.snapshots.size)
        rows.snapshots.zip(decodedRows.snapshots).forEach { (original, restored) ->
            assertThat(restored.sourceNamespace).isEqualTo(original.sourceNamespace)
            assertThat(restored.sourceId).isEqualTo(original.sourceId)
            assertThat(restored.sourcePayloadDigest).isEqualTo(original.sourcePayloadDigest)
            assertThat(restored.snapshotDigest).isEqualTo(original.snapshotDigest)
            assertThat(restored.snapshotJson).isEqualTo(original.snapshotJson)
            assertThat(restored.sourcePayload.toList()).isEqualTo(original.sourcePayload.toList())
        }

        // Exact money spellings and literal currencies, never reinterpreted.
        assertThat(decodedRows.events.single().bookedAmount).isEqualTo("001.2300")
        assertThat(decodedRows.events.single().bookedCurrency).isEqualTo("SAR")
        assertThat(decodedRows.components.single().amount).isEqualTo("1E+3")
        assertThat(decodedRows.components.single().currency).isEqualTo("KWD")
        assertThat(decodedRows.legs.single().delta).isEqualTo("-0001.2300")
        assertThat(decodedRows.events.single().snapshotJson).isEqualTo(" \r\n{\"opaque\":\"001.2300\"}\t ")
        assertThat(decodedRows.conflicts.single().detailJson).isEqualTo(" {\"detail\":\"kept\"}\r\n ")
    }
}
