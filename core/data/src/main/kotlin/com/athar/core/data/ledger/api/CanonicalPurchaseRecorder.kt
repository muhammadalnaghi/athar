package com.athar.core.data.ledger.api

import android.database.SQLException
import androidx.room.withTransaction
import com.athar.core.common.money.Money
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.ledger.admission.AdmissionBounds
import com.athar.core.data.ledger.admission.AdmissionRejection
import com.athar.core.data.ledger.admission.AdmissionRejectionReason
import com.athar.core.data.ledger.admission.AdmissionStorageFailure
import com.athar.core.data.ledger.admission.UnallocatedAdmissionOutcome
import com.athar.core.data.ledger.admission.UnallocatedAdmissionRepository
import com.athar.core.data.ledger.admission.UnallocatedAdmissionRequest
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The pipeline supplies its freshly INSERTED row, not an arbitrary historical source. */
data class NewPurchaseProjection(
    val transactionId: String,
    val createdAt: Instant,
    val confirmedAt: Instant,
    val session: FinancialRecordingSession,
)

private class CanonicalProjectionSeam(
    val eligible: (FinancialEventGraph) -> Boolean,
    val matchesRow: (FinancialEventGraph, TransactionEntity?, NewPurchaseProjection) -> Boolean,
    val exactlyOneRowDetail: String,
    val rollbackDetail: String,
)

private val PURCHASE_PROJECTION = CanonicalProjectionSeam(
    eligible = ::isPostedPurchase,
    matchesRow = ::matchesNewPurchase,
    exactlyOneRowDetail = "purchase projection did not update exactly one row",
    rollbackDetail = "purchase transaction rolled back",
)

private val CARD_REPAYMENT_PROJECTION = CanonicalProjectionSeam(
    eligible = ::isPostedCardRepayment,
    matchesRow = ::matchesNewCardRepayment,
    exactlyOneRowDetail = "card repayment projection did not update exactly one row",
    rollbackDetail = "card repayment transaction rolled back",
)

/** Canonical event and the ONE existing-query projection commit or roll back together. */
@Singleton
class CanonicalPurchaseRecorder @Inject internal constructor(private val database: AtharDatabase) {
    fun beginSession(): FinancialRecordingSession = FinancialRestoreEpoch.capture()

    suspend fun record(
        graph: FinancialEventGraph,
        originalBytes: ByteArray,
        evidenceRef: String,
        projection: NewPurchaseProjection,
    ): UnallocatedAdmissionOutcome =
        recordCanonical(graph, originalBytes, evidenceRef, projection, PURCHASE_PROJECTION)

    /** Confirms only a fresh matching TRANSFER; never adds operating spend or income. */
    suspend fun recordCardRepayment(
        graph: FinancialEventGraph,
        originalBytes: ByteArray,
        evidenceRef: String,
        projection: NewPurchaseProjection,
    ): UnallocatedAdmissionOutcome =
        recordCanonical(graph, originalBytes, evidenceRef, projection, CARD_REPAYMENT_PROJECTION)

    private suspend fun recordCanonical(
        graph: FinancialEventGraph,
        originalBytes: ByteArray,
        evidenceRef: String,
        projection: NewPurchaseProjection,
        seam: CanonicalProjectionSeam,
    ): UnallocatedAdmissionOutcome {
        if (originalBytes.size > AdmissionBounds.MAX_ORIGINAL_BYTES) return refused("source bytes exceed bound")
        val bytes = originalBytes.copyOf()
        return try {
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    if (!FinancialRestoreEpoch.isCurrent(projection.session)) return@withTransaction refused("store replaced")
                    if (!seam.eligible(graph)) return@withTransaction refused("unsupported financial evidence")
                    val identity = graph.observationIdentity
                    val prior = database.canonicalLedgerDao().getObservation(identity.namespace, identity.sourceItemId)
                    val row = database.transactionDao().get(projection.transactionId)
                    if (prior == null && !seam.matchesRow(graph, row, projection)) {
                        return@withTransaction refused("source row no longer eligible")
                    }
                    val engine = UnallocatedAdmissionRepository(
                        database, database.canonicalLedgerDao(), database.canonicalEvidenceDao(), Dispatchers.IO,
                    )
                    val result = engine.admit(UnallocatedAdmissionRequest(graph, bytes, evidenceRef))
                    // Replays/conflicts NEVER reapprove a row or overwrite a user's subsequent decision.
                    if (result is UnallocatedAdmissionOutcome.Admitted) {
                        check(FinancialRestoreEpoch.isCurrent(projection.session)) { "store replaced during admission" }
                        val current = checkNotNull(row)
                        check(database.transactionDao().updateExistingForUpsert(
                            current.copy(status = "CONFIRMED", updatedAt = projection.confirmedAt),
                        ) == 1) { seam.exactlyOneRowDetail }
                    }
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLException) {
            UnallocatedAdmissionOutcome.StorageFailed(AdmissionStorageFailure.SQLITE_FAILURE, seam.rollbackDetail)
        } catch (_: IllegalStateException) {
            UnallocatedAdmissionOutcome.StorageFailed(AdmissionStorageFailure.INVARIANT_VIOLATION, seam.rollbackDetail)
        }
    }

    private fun refused(detail: String) = UnallocatedAdmissionOutcome.Rejected(
        AdmissionRejection(AdmissionRejectionReason.UNSUPPORTED_READ_PROJECTION, detail),
    )
}

private fun isPostedExplicitOutPrincipal(graph: FinancialEventGraph): Boolean =
    graph.observation.posting.state == PostingState.POSTED &&
        graph.observation.posting.support == PostingSupport.EXPLICIT_POSTED &&
        graph.observation.direction == MovementDirection.OUT &&
        graph.observation.eventDate != null && graph.observation.legacy == null &&
        graph.observationIdentity.namespace in setOf("sms", "notification") &&
        graph.bookedAmount.amount.signum() > 0 && graph.components.size == 1 &&
        graph.components.single().type == ComponentType.PRINCIPAL && graph.legs.isEmpty()

internal fun isPostedPurchase(graph: FinancialEventGraph): Boolean =
    isPostedExplicitOutPrincipal(graph) && graph.kind == EconomicKind.PURCHASE

internal fun isPostedCardRepayment(graph: FinancialEventGraph): Boolean =
    isPostedExplicitOutPrincipal(graph) && graph.kind == EconomicKind.CARD_REPAYMENT

internal fun matchesNewPurchase(
    graph: FinancialEventGraph,
    row: TransactionEntity?,
    projection: NewPurchaseProjection,
): Boolean = matchesFreshPendingRow(graph, row, projection, isPostedPurchase(graph), "EXPENSE")

internal fun matchesNewCardRepayment(
    graph: FinancialEventGraph,
    row: TransactionEntity?,
    projection: NewPurchaseProjection,
): Boolean = matchesFreshPendingRow(graph, row, projection, isPostedCardRepayment(graph), "TRANSFER")

private fun matchesFreshPendingRow(
    graph: FinancialEventGraph,
    row: TransactionEntity?,
    projection: NewPurchaseProjection,
    eligible: Boolean,
    expectedType: String,
): Boolean {
    if (row == null || !eligible) return false
    val source = if (graph.observationIdentity.namespace == "sms") "SMS" else "NOTIFICATION"
    return row.id == projection.transactionId && row.source == source &&
        row.sourceRefId == graph.observationIdentity.sourceItemId && row.type == expectedType &&
        row.status == "PENDING" && row.categorySource != "USER" && row.updatedAt == row.createdAt &&
        row.createdAt.toEpochMilliseconds() == projection.createdAt.toEpochMilliseconds() &&
        projection.confirmedAt >= row.createdAt && row.date == graph.observation.eventDate &&
        row.currency == graph.bookedAmount.currency &&
        Money.ofMinor(row.amountMinor, row.currency).amount.compareTo(graph.bookedAmount.amount) == 0
}
