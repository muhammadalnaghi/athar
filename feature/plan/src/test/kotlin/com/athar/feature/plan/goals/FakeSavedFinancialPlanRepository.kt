package com.athar.feature.plan.goals

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

/**
 * TEST-ONLY in-memory stand-in for the frozen `SavedFinancialPlanRepository` boundary.
 * It mirrors the contract's refusal semantics — create-only saves, exact-revision
 * updates and deletes, conflict exceptions instead of silent overwrites — so feature
 * tests exercise the same guards the encrypted Room implementation enforces. This fake
 * must never leak into production code; the store lane's real implementation is the
 * integration dependency.
 */
class FakeSavedFinancialPlanRepository : SavedFinancialPlanRepository {

    private val nextRevision = AtomicLong(0)
    private val docs = MutableStateFlow<List<SavedFinancialPlan>>(emptyList())

    /** Read-only inspection of exactly what is stored right now. */
    val stored: List<SavedFinancialPlan> get() = docs.value

    /** Simulates an external writer bumping one document (conflict drills). */
    fun bumpRevisionExternally(id: String) {
        docs.value = docs.value.map { if (it.id == id) it.copy(revision = it.revision + 100) else it }
    }

    /**
     * Simulates the race where storage changed between the caller's visible snapshot
     * and its repository call: the NEXT delete refuses once, then behaves normally.
     */
    var refuseNextDelete = false

    override fun observe(kind: SavedFinancialPlanKind): Flow<List<SavedFinancialPlan>> =
        docs.map { list -> list.filter { it.kind == kind } }

    /** Optional deterministic read barrier; mutation/refusal semantics stay unchanged. */
    var getGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Preexisting archival fixture, distinct from create-only save assigning a revision. */
    fun seedExisting(plan: SavedFinancialPlan) {
        check(docs.value.none { it.id == plan.id })
        docs.value = docs.value + plan
        nextRevision.updateAndGet { maxOf(it, plan.revision) }
    }

    override suspend fun get(id: String): SavedFinancialPlan? {
        getGate?.await()
        return docs.value.firstOrNull { it.id == id }
    }

    var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var saveRequests = 0
        private set

    override suspend fun save(plan: SavedFinancialPlan, expectedRevision: Long?): SavedFinancialPlan {
        saveRequests += 1
        saveGate?.await()
        val current = docs.value.firstOrNull { it.id == plan.id }
        if (expectedRevision == null) {
            if (current != null) throw SavedFinancialPlanConflictException() // create-only
            val saved = plan.copy(revision = nextRevision.incrementAndGet())
            docs.value = docs.value + saved
            return saved
        }
        if (current == null || current.revision != expectedRevision) {
            throw SavedFinancialPlanConflictException()
        }
        val saved = plan.copy(revision = current.revision + 1)
        docs.value = docs.value.map { if (it.id == saved.id) saved else it }
        return saved
    }

    override suspend fun delete(id: String, expectedRevision: Long) {
        val current = docs.value.firstOrNull { it.id == id }
        if (refuseNextDelete) {
            refuseNextDelete = false
            throw SavedFinancialPlanConflictException()
        }
        if (current == null || current.revision != expectedRevision) {
            throw SavedFinancialPlanConflictException()
        }
        docs.value = docs.value.filterNot { it.id == id }
    }
}
