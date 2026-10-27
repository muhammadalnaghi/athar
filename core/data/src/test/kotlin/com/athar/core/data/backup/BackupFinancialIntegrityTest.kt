package com.athar.core.data.backup

import com.athar.core.data.ledger.admission.AdmissionDigests
import com.athar.core.data.ledger.admission.AdmissionPreparation
import com.athar.core.data.ledger.backup.CanonicalBackupAssociation
import com.athar.core.data.ledger.backup.CanonicalBackupComponent
import com.athar.core.data.ledger.backup.CanonicalBackupConflict
import com.athar.core.data.ledger.backup.CanonicalBackupEvent
import com.athar.core.data.ledger.backup.CanonicalBackupLeg
import com.athar.core.data.ledger.backup.CanonicalBackupObservation
import com.athar.core.data.ledger.backup.CanonicalBackupSection
import com.athar.core.data.ledger.backup.CanonicalBackupSnapshotVariant
import com.athar.core.data.ledger.backup.canonicalBackupFixture
import com.athar.core.data.ledger.backup.toSection
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
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
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.common.money.Money
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pure JVM regression for the WM028-P semantic-integrity gate: a genuinely
 * prepared (production `AdmissionPreparation`, never a forged certificate)
 * UNALLOCATED event must pass incoming v4 validation unchanged, while any
 * tampered denormalized field, broken accepted-snapshot binding or routed
 * authority contradicting its own certificate refuses with its exact code.
 * A representable historical graph (opaque snapshot outside the certified
 * codec scope) roundtrips as backup representation — runtime admission is
 * untouched and no authority is invented. Backup envelope is fully valid in
 * every case — only semantics differ.
 */
class BackupFinancialIntegrityTest {

    // ------------------------------------------------------------ fixtures

    private fun unallocatedGraph(eventId: String, sourceId: String, amount: String = "200.00", currency: String = "SAR") =
        FinancialGraphDraft(
            eventId = eventId,
            observation = FinancialObservation(
                stableId = StableObservationId(sourceId),
                posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
                economicKind = EconomicKind.PURCHASE,
                direction = MovementDirection.OUT,
                original = SupportedMoney(Money.of(amount, currency), AmountSupport.EXPLICIT_BOOKED),
                settlement = null,
                routing = RoutingCoverage.Unresolved,
                eventDate = null,
                legacy = null,
                userDeclaration = null,
            ),
            sourceNamespace = NAMESPACE,
            components = listOf(
                com.athar.core.domain.model.financialgraph.ComponentSpec(
                    componentRef = "principal",
                    type = com.athar.core.domain.model.financialgraph.ComponentType.PRINCIPAL,
                    amount = Money.of(amount, currency),
                    support = AmountSupport.EXPLICIT_TRANSACTION,
                    evidenceRef = "synthetic:principal:amount",
                ),
            ),
        )

    /** Production preparation + the exact backup rows `CanonicalBackupRowMapper` writes. */
    private fun admittedEvent(
        eventId: String = "event-a",
        sourceId: String = "source-a",
        amount: String = "200.00",
        currency: String = "SAR",
        bytes: ByteArray = byteArrayOf(0, 13, 10, 127, -128, -1),
    ): CanonicalBackupSection {
        val graph = when (val outcome = FinancialGraphFactory.build(unallocatedGraph(eventId, sourceId, amount, currency))) {
            is FinancialGraphOutcome.Created -> outcome.graph
            else -> fail("fixture graph must be created, got $outcome")
        }
        val prepared = when (val preparation = AdmissionPreparation.prepare(graph, bytes, "synthetic:original:evidence")) {
            is AdmissionPreparation.Preparation.Ready -> preparation.prepared
            is AdmissionPreparation.Preparation.Unprepared -> fail("fixture admission must prepare, got ${preparation.rejection}")
        }
        val event = prepared.toEventEntity()
        return CanonicalBackupSection(
            events = listOf(
                CanonicalBackupEvent(
                    eventId = event.eventId,
                    economicIdentityIssuer = event.economicIdentityIssuer,
                    economicIdentityReference = event.economicIdentityReference,
                    snapshotFormatVersion = event.snapshotFormatVersion,
                    policyRevision = event.policyRevision,
                    graphRevision = event.graphRevision,
                    snapshotJson = event.snapshotJson,
                    bookedAmount = event.bookedAmount,
                    bookedCurrency = event.bookedCurrency,
                    bookedBasis = event.bookedBasis,
                    kind = event.kind,
                    kindResolved = event.kindResolved,
                    incomeNature = event.incomeNature,
                    recordingOutcome = event.recordingOutcome,
                    eventDate = event.eventDate,
                    routing = event.routing,
                    composition = event.composition,
                ),
            ),
            observations = listOf(
                CanonicalBackupObservation(NAMESPACE, sourceId, prepared.evidenceRef, prepared.payloadDigest),
            ),
            associations = listOf(CanonicalBackupAssociation(NAMESPACE, sourceId, prepared.eventId)),
            components = prepared.componentEntities().map {
                CanonicalBackupComponent(it.eventId, it.componentRef, it.type, it.amount, it.currency, it.feeRelation, it.feeEvidenceRef)
            },
            legs = emptyList(),
            revisions = emptyList(),
            snapshots = listOf(
                CanonicalBackupSnapshotVariant(
                    NAMESPACE, sourceId, prepared.payloadDigest, prepared.snapshotDigest,
                    Base64.getEncoder().encodeToString(prepared.originalBytes), prepared.snapshotJson,
                ),
            ),
            conflicts = emptyList(),
        )
    }

    private fun v4(
        section: CanonicalBackupSection,
        accountIds: List<String> = listOf("acc-1"),
    ) = BackupSnapshot(
        exportedAt = Instant.parse("2026-06-13T00:00:00Z"),
        accounts = accountIds.map { id -> BackupAccount(
            id = id, name = "Synthetic", type = "CASH", currency = "SAR",
            smsSenders = "[]", active = true, createdAt = Instant.parse("2026-06-13T00:00:00Z"),
        )},
        categories = emptyList(),
        transactions = emptyList(),
        categoryRules = emptyList(),
        wishlist = emptyList(),
        investmentPools = emptyList(),
        investmentContributions = emptyList(),
        smsAudit = emptyList(),
        auxiliaryTables = BackupAuxiliarySection(
            activityLog = emptyList(), userTemplates = emptyList(), recurringRules = emptyList(),
            savedPlans = emptyList(),
        ),
        canonicalLedger = section,
    )

    private fun refuses(section: CanonicalBackupSection, code: BackupValidationCode) {
        val exception = assertThrows<BackupValidationException> {
            BackupRestoreValidator.validateIncoming(v4(section))
        }
        assertThat(exception.code).isEqualTo(code)
    }

    private fun CanonicalBackupSection.withEvent(transform: (CanonicalBackupEvent) -> CanonicalBackupEvent) =
        copy(events = events.map(transform))

    // ---------------------------------------------------------------- tests

    @Test
    fun `genuine admitted UNALLOCATED event passes incoming v4 validation unchanged`() {
        val section = admittedEvent()
        val event = section.events.single()
        // Sanity: the fixture really carries the accepted money spellings.
        assertThat(event.routing).isEqualTo("UNALLOCATED")
        assertThat(event.bookedAmount).isEqualTo("200.00")
        assertThat(event.bookedCurrency).isEqualTo("SAR")
        BackupRestoreValidator.validateIncoming(v4(section))
    }

    @Test
    fun `empty canonical section passes vacuously`() {
        BackupRestoreValidator.validateIncoming(v4(admittedEvent().copy(
            events = emptyList(), observations = emptyList(), associations = emptyList(),
            components = emptyList(), snapshots = emptyList(),
        )))
    }

    @Test
    fun `tampered denormalized booked amount refuses before replacement`() {
        refuses(admittedEvent().withEvent { it.copy(bookedAmount = "9999.00") }, BackupValidationCode.CANONICAL_DENORMALIZATION_MISMATCH)
    }

    @Test
    fun `tampered recording outcome refuses`() {
        refuses(admittedEvent().withEvent { it.copy(recordingOutcome = "TAMPERED_OUTCOME") }, BackupValidationCode.CANONICAL_DENORMALIZATION_MISMATCH)
    }

    @Test
    fun `tampered kind refuses`() {
        refuses(admittedEvent().withEvent { it.copy(kind = "OWN_TRANSFER") }, BackupValidationCode.CANONICAL_DENORMALIZATION_MISMATCH)
    }

    @Test
    fun `injected component row refuses`() {
        val section = admittedEvent()
        val forged = CanonicalBackupComponent(
            section.events.single().eventId, "fee-ghost", "FEE", "1E+3", "KWD", "UNKNOWN", "fee-1",
        )
        refuses(section.copy(components = section.components + forged), BackupValidationCode.CANONICAL_DENORMALIZATION_MISMATCH)
    }

    @Test
    fun `swapped snapshot json loses the accepted binding and refuses`() {
        val other = admittedEvent(eventId = "event-b", sourceId = "source-b", amount = "310.00")
        refuses(
            admittedEvent().withEvent { it.copy(snapshotJson = other.events.single().snapshotJson) },
            BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN,
        )
    }

    @Test
    fun `accepted variant absent refuses and diagnostic conflict never substitutes`() {
        val section = admittedEvent()
        val event = section.events.single()
        val diagnostic = CanonicalBackupSnapshotVariant(
            NAMESPACE, "source-a", AdmissionDigests.sha256Hex("diagnostic"), AdmissionDigests.sha256Hex("diagnostic"),
            Base64.getEncoder().encodeToString("diagnostic".toByteArray()), event.snapshotJson,
        )
        val conflict = CanonicalBackupConflict(
            conflictId = "diag-conflict", sourceNamespace = NAMESPACE, sourceId = "source-a",
            sourcePayloadDigest = diagnostic.sourcePayloadDigest, snapshotDigest = diagnostic.snapshotDigest,
            existingEventId = event.eventId, reasonCode = "ECONOMICS_CHANGED", detailJson = null,
        )
        refuses(
            section.copy(snapshots = listOf(diagnostic), conflicts = listOf(conflict)),
            BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN,
        )
    }

    @Test
    fun `corrupted archive payload bytes refuse`() {
        val section = admittedEvent()
        val tamperedVariant = section.snapshots.single()
            .copy(payloadBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        refuses(section.copy(snapshots = listOf(tamperedVariant)), BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN)
    }

    @Test
    fun `observation without payload digest refuses`() {
        val section = admittedEvent()
        val unbound = section.observations.single().copy(payloadDigest = null)
        refuses(section.copy(observations = listOf(unbound)), BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN)
    }

    @Test
    fun `unbound event without any accepted source refuses`() {
        refuses(admittedEvent().copy(associations = emptyList()), BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN)
    }

    @Test
    fun `routed routing field is unsupported authority and refuses`() {
        refuses(admittedEvent().withEvent { it.copy(routing = "ROUTED") }, BackupValidationCode.CANONICAL_AUTHORITY_UNSUPPORTED)
    }

    @Test
    fun `injected leg routes the event and refuses`() {
        val section = admittedEvent()
        val leg = CanonicalBackupLeg(
            section.events.single().eventId, "bank-leg", "acc-1", "-200.00", "SAR", null,
        )
        refuses(section.copy(legs = listOf(leg)), BackupValidationCode.CANONICAL_AUTHORITY_UNSUPPORTED)
    }

    @Test
    fun `accepted archive cannot be rebound to a different outer source identity`() {
        for (changeNamespace in listOf(false, true)) {
            val section = admittedEvent()
            val namespace = if (changeNamespace) "other-namespace" else NAMESPACE
            val sourceId = if (changeNamespace) "source-a" else "other-source"
            refuses(section.copy(
                observations = section.observations.map { it.copy(sourceNamespace = namespace, sourceId = sourceId) },
                associations = section.associations.map { it.copy(sourceNamespace = namespace, sourceId = sourceId) },
                snapshots = section.snapshots.map { it.copy(sourceNamespace = namespace, sourceId = sourceId) },
            ), BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN)
        }
    }

    @Test
    fun `accepted original payload at the admission limit remains valid`() {
        val bytes = ByteArray(com.athar.core.data.ledger.admission.AdmissionBounds.MAX_ORIGINAL_BYTES) { 7 }
        BackupRestoreValidator.validateIncoming(v4(admittedEvent(bytes = bytes)))
    }

    @Test
    fun `rehashing an oversized accepted payload cannot bypass admission bounds`() {
        val section = admittedEvent()
        val bytes = ByteArray(com.athar.core.data.ledger.admission.AdmissionBounds.MAX_ORIGINAL_BYTES + 1) { 7 }
        val digest = AdmissionDigests.sha256Hex(bytes)
        refuses(section.copy(
            observations = section.observations.map { it.copy(payloadDigest = digest) },
            snapshots = section.snapshots.map { it.copy(
                sourcePayloadDigest = digest, payloadBase64 = Base64.getEncoder().encodeToString(bytes),
            ) },
        ), BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN)
    }

    @Test
    fun `literal booked currency cannot diverge from its accepted snapshot`() {
        refuses(admittedEvent().withEvent { it.copy(bookedCurrency = "KWD") },
            BackupValidationCode.CANONICAL_DENORMALIZATION_MISMATCH)
    }

    // ------------------------------------------------- representational scope

    @Test
    fun `historical legged graph with an opaque accepted snapshot roundtrips`() {
        // The shared transport fixture: UNALLOCATED event row + one bank leg +
        // opaque snapshotJson/digests — outside the certified codec scope.
        // Backup representation, not runtime admission: nothing is certified
        // and no authority is invented, so the graph must roundtrip.
        BackupRestoreValidator.validateIncoming(
            v4(canonicalBackupFixture().toSection(), accountIds = listOf("account-a")),
        )
    }

    @Test
    fun `historical routed row with an opaque accepted snapshot roundtrips`() {
        val rows = canonicalBackupFixture()
        val section = rows.copy(events = rows.events.map { it.copy(routing = "ROUTED") }).toSection()
        BackupRestoreValidator.validateIncoming(v4(section, accountIds = listOf("account-a")))
    }

    @Test
    fun `historical event without any accepted source binding still refuses`() {
        val section = canonicalBackupFixture().toSection().copy(associations = emptyList())
        val error = assertThrows<BackupValidationException> {
            BackupRestoreValidator.validateIncoming(v4(section, accountIds = listOf("account-a")))
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN)
    }

    private companion object {
        const val NAMESPACE = "synthetic-admission"
    }
}
