package com.athar.core.data.position

import com.athar.core.domain.model.position.DeclaredPositionSnapshot
import com.athar.core.domain.repo.DeclaredPositionRepository
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * WM033 read-side repository over the shared encrypted saved-plan store.
 *
 * Source of truth is `SavedFinancialPlanRepository.observe(CASH)` — the lane
 * whose `balance_anchor` documents the Plan feature's position writer owns.
 * Each emission is mapped one-to-one: every loadable v1 document becomes its
 * own snapshot; values are never combined across documents. Decode and exact
 * aggregation run off Main (`Dispatchers.Default`), so the Flow is main-safe.
 *
 * Output ordering is as-of date, then last-update time, then id — a total,
 * deterministic order. Rows that are foreign, unsupported or malformed are
 * excluded (retained untouched in storage); no write path exists here.
 */
@Singleton
internal class DeclaredPositionRepositoryImpl @Inject constructor(
    private val savedPlans: SavedFinancialPlanRepository,
) : DeclaredPositionRepository {

    override fun observeSnapshots(): Flow<List<DeclaredPositionSnapshot>> =
        savedPlans.observe(SavedFinancialPlanKind.CASH)
            .map { plans ->
                plans.mapNotNull(DeclaredPositionPayloadReader::read)
                    .sortedWith(
                        compareBy(
                            { snapshot: DeclaredPositionSnapshot -> LocalDate.parse(snapshot.asOfDate) },
                            { snapshot: DeclaredPositionSnapshot -> snapshot.updatedAtEpochMillis },
                            { snapshot: DeclaredPositionSnapshot -> snapshot.id },
                        ),
                    )
            }
            .flowOn(Dispatchers.Default)
}
