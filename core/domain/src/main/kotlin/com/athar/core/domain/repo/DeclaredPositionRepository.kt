package com.athar.core.domain.repo

import com.athar.core.domain.model.position.DeclaredPositionSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * WM033 shared read-side over the currently saved user-declared position
 * documents. Strictly read-only: there is no save, no migration and no repair
 * on this boundary — authoring stays owned by the Plan feature.
 *
 * Contract:
 * - Each element is one stored document's projection. Values are NEVER summed
 *   across documents (saved documents can overlap), never converted between
 *   currencies, and never combined with cash-flow history.
 * - The list is sorted by as-of date, then last-update time, then id — a total,
 *   deterministic order safe for Today and widget rendering.
 * - Rows that are not loadable v1 balance_anchor documents (foreign CASH
 *   siblings, unsupported versions, malformed payloads) are excluded from the
 *   list; they stay retained and untouched in storage.
 * - Status is always [com.athar.core.domain.model.position.DeclaredPositionStatus.USER_DECLARED]:
 *   this data is user input, never verified wealth or liquidity.
 */
interface DeclaredPositionRepository {
    fun observeSnapshots(): Flow<List<DeclaredPositionSnapshot>>
}
