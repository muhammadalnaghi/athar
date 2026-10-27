package com.athar.core.domain.model.position

import kotlinx.datetime.LocalDate

/** One document in the literal currency, never a sum of overlapping saved positions. */
fun latestDeclaredPosition(
    snapshots: List<DeclaredPositionSnapshot>,
    currency: String,
): DeclaredPositionSnapshot? = snapshots
    .filter { currency in it.netByCurrency }
    .maxWithOrNull(
        compareBy(
            { snapshot: DeclaredPositionSnapshot -> LocalDate.parse(snapshot.asOfDate) },
            { snapshot: DeclaredPositionSnapshot -> snapshot.updatedAtEpochMillis },
            { snapshot: DeclaredPositionSnapshot -> snapshot.id },
        ),
    )
