package com.athar.core.data.backup

import com.athar.core.data.db.AtharDatabase

/** Exact raw-field evidence for all 21 tables, including the live revision. */
internal object BackupNativeStateEvidence {
    private fun <T> evidence(rows: List<T>, fields: (T) -> List<Any?>): List<List<Any?>> =
        rows.map(fields).sortedBy { row -> row[0]?.toString().orEmpty() }

    suspend fun capture(db: AtharDatabase): Map<String, List<List<Any?>>> {
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

}
