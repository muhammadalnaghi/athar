package com.athar.feature.plan.scenarios

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal fun validDebtDraft() = DebtScenarioDraft(
    firstDueDate = "2026-11-21", horizon = "12", budget = "50", supportedTermsAcknowledged = true,
    loans = listOf(DebtLoanDraft(name = "Home", principal = "100", annualPercent = "0", minimum = "50", fee = "0")),
)

/** Test-only fake of the frozen saved-plans boundary; mirrors the create/update/delete fencing contract. */
internal class FakeSavedDebtPlans : SavedFinancialPlanRepository {
    val stored = LinkedHashMap<String, SavedFinancialPlan>()
    val saveCalls = mutableListOf<Pair<SavedFinancialPlan, Long?>>()
    val deleteCalls = mutableListOf<Pair<String, Long>>()
    var nextRevision = 1L
    var clock = 1_000L

    /** First save after assignment blocks until completed, then the gate is consumed. */
    var gate: CompletableDeferred<Unit>? = null

    private val flow = MutableStateFlow<List<SavedFinancialPlan>>(emptyList())

    override fun observe(kind: SavedFinancialPlanKind): Flow<List<SavedFinancialPlan>> =
        flow.map { plans -> plans.filter { it.kind == kind } }

    /** Delays only retrieval when a test explicitly requests a real late-load race. */
    var getGate: CompletableDeferred<Unit>? = null

    override suspend fun get(id: String): SavedFinancialPlan? {
        getGate?.await()
        return stored[id]
    }

    override suspend fun save(plan: SavedFinancialPlan, expectedRevision: Long?): SavedFinancialPlan {
        saveCalls += plan to expectedRevision
        gate?.let { gate = null; it.await() }
        val current = stored[plan.id]
        val updated = when {
            expectedRevision == null -> {
                check(current == null) { "create-only save on an existing id" }
                plan.copy(revision = nextRevision++, createdAtEpochMillis = clock, updatedAtEpochMillis = clock)
            }
            current == null || current.revision != expectedRevision -> throw SavedFinancialPlanConflictException()
            else -> plan.copy(revision = nextRevision++, createdAtEpochMillis = current.createdAtEpochMillis, updatedAtEpochMillis = ++clock)
        }
        clock++
        stored[plan.id] = updated
        flow.value = stored.values.toList()
        return updated
    }

    override suspend fun delete(id: String, expectedRevision: Long) {
        deleteCalls += id to expectedRevision
        val current = stored[id]
        if (current == null || current.revision != expectedRevision) throw SavedFinancialPlanConflictException()
        stored.remove(id)
        flow.value = stored.values.toList()
    }

    fun seed(plan: SavedFinancialPlan) {
        stored[plan.id] = plan
        flow.value = stored.values.toList()
    }
}
