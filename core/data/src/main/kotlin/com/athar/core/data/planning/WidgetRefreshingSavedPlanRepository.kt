package com.athar.core.data.planning

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.core.domain.repo.WidgetRefresher
import javax.inject.Inject
import javax.inject.Singleton

/** Preserves the delegate's CAS result; never re-reads or changes financial input documents. */
@Singleton
internal class WidgetRefreshingSavedPlanRepository internal constructor(
    private val delegate: SavedFinancialPlanRepository,
    private val widgets: WidgetRefresher,
) : SavedFinancialPlanRepository by delegate {
    @Inject constructor(service: SavedFinancialPlanRepositoryImpl, widgets: WidgetRefresher) :
        this(service as SavedFinancialPlanRepository, widgets)

    override suspend fun save(plan: SavedFinancialPlan, expectedRevision: Long?): SavedFinancialPlan {
        val saved = delegate.save(plan, expectedRevision)
        widgets.requestRefresh()
        return saved
    }

    override suspend fun delete(id: String, expectedRevision: Long) {
        delegate.delete(id, expectedRevision)
        widgets.requestRefresh()
    }
}
