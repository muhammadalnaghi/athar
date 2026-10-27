package com.athar.feature.plan.scenarios

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Test double of the frozen saved-plan boundary: real optimistic-revision semantics,
 * observable listings, and explicit failure switches. No production code path uses it.
 */
internal class FakeSavedPlans : SavedFinancialPlanRepository {
    val store = LinkedHashMap<String, SavedFinancialPlan>()
    private val published = MutableStateFlow<List<SavedFinancialPlan>>(emptyList())
    val saveCalls = mutableListOf<Pair<String, Long?>>()
    val deleteCalls = mutableListOf<Pair<String, Long>>()
    var failSaves = false
    var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun observe(kind: SavedFinancialPlanKind): Flow<List<SavedFinancialPlan>> =
        published.map { plans -> plans.filter { it.kind == kind } }

    override suspend fun get(id: String): SavedFinancialPlan? = store[id]

    override suspend fun save(plan: SavedFinancialPlan, expectedRevision: Long?): SavedFinancialPlan {
        saveCalls += plan.id to expectedRevision
        saveGate?.let { saveGate = null; it.await() }
        val current = store[plan.id]
        val result = if (expectedRevision == null) {
            check(current == null) { "create-only save hit an existing document" }
            plan.copy(revision = 1L)
        } else {
            if (current == null || current.revision != expectedRevision) throw SavedFinancialPlanConflictException()
            plan.copy(revision = current.revision + 1)
        }
        if (failSaves) throw IllegalStateException("storage unavailable")
        store[result.id] = result
        publish()
        return result
    }

    override suspend fun delete(id: String, expectedRevision: Long) {
        deleteCalls += id to expectedRevision
        val current = store[id] ?: throw SavedFinancialPlanConflictException()
        if (current.revision != expectedRevision) throw SavedFinancialPlanConflictException()
        store.remove(id)
        publish()
    }

    /** Simulates an external writer changing a document behind this feature's back. */
    fun externallyBumpRevision(id: String) {
        val current = requireNotNull(store[id])
        store[id] = current.copy(revision = current.revision + 97)
        publish()
    }

    /** Publishes direct store seeds through the observable listing. */
    fun refresh() = publish()

    private fun publish() {
        published.value = store.values.toList()
    }
}

internal class FixedClock(var millis: Long = 1_792_000_000_000) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}
