package com.athar.core.data.ledger.backup

import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventSourceEntity
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerLegEntity
import com.athar.core.data.ledger.db.CanonicalLedgerRevisionEntity
import com.athar.core.data.ledger.db.CanonicalObservationSnapshotEntity
import com.athar.core.data.ledger.db.CanonicalLedgerConflictEntity

internal fun canonicalBackupFixture(): CanonicalBackupRows = CanonicalBackupRows(
    events = listOf(
        CanonicalLedgerEventEntity(
            eventId = "event-a",
            economicIdentityIssuer = "issuer-kept",
            economicIdentityReference = "reference-kept",
            snapshotFormatVersion = 1L,
            policyRevision = "policy-kept",
            graphRevision = "graph-kept",
            snapshotJson = " \r\n{\"opaque\":\"001.2300\"}\t ",
            bookedAmount = "001.2300",
            bookedCurrency = "SAR",
            bookedBasis = "ORIGINAL",
            kind = "FUTURE_KIND",
            kindResolved = false,
            incomeNature = "UNCLASSIFIED",
            recordingOutcome = "CAPTURED_UNRESOLVED_KIND",
            eventDate = "2026-01-02",
            routing = "UNALLOCATED",
            composition = "FUTURE_COMPOSITION",
        ),
    ),
    observations = listOf(
        CanonicalLedgerSourceObservationEntity(
            sourceNamespace = "synthetic-sms",
            sourceId = "source-a",
            evidenceRef = "evidence-kept",
            payloadDigest = "PayloadDigest_KeptCase",
        ),
    ),
    associations = listOf(
        CanonicalLedgerEventSourceEntity(
            sourceNamespace = "synthetic-sms",
            sourceId = "source-a",
            eventId = "event-a",
        ),
    ),
    components = listOf(
        CanonicalLedgerComponentEntity(
            eventId = "event-a",
            componentRef = "component-a",
            type = "FUTURE_COMPONENT",
            amount = "1E+3",
            currency = "KWD",
            feeRelation = "UNKNOWN",
            feeEvidenceRef = "fee-kept",
        ),
    ),
    legs = listOf(
        CanonicalLedgerLegEntity(
            eventId = "event-a",
            legRef = "leg-a",
            accountRef = "account-a",
            delta = "-0001.2300",
            currency = "KWD",
            componentRef = "component-a",
        ),
    ),
    revisions = listOf(
        CanonicalLedgerRevisionEntity(
            id = "singleton",
            revision = Long.MAX_VALUE,
        ),
    ),
    snapshots = listOf(
        CanonicalObservationSnapshotEntity(
            sourceNamespace = "synthetic-sms",
            sourceId = "source-a",
            sourcePayloadDigest = "PayloadDigest_KeptCase",
            snapshotDigest = "SnapshotDigest_KeptCase",
            sourcePayload = (0..255).map { it.toByte() }.toByteArray(),
            snapshotJson = " \r\n{\"opaque\":\"001.2300\"}\t ",
        ),
    ),
    conflicts = listOf(
        CanonicalLedgerConflictEntity(
            conflictId = "conflict-a",
            sourceNamespace = "synthetic-sms",
            sourceId = "source-a",
            sourcePayloadDigest = "PayloadDigest_KeptCase",
            snapshotDigest = "SnapshotDigest_KeptCase",
            existingEventId = "event-a",
            reasonCode = "FUTURE_REASON",
            detailJson = " {\"detail\":\"kept\"}\r\n ",
        ),
    ),
)
