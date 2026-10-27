package com.athar.core.domain.model.financialgraph

/**
 * Where one observed event came from: a source namespace plus the stable
 * source item id within that namespace (WM028-11).
 *
 * This is source-observation identity only. It says nothing about economic
 * identity: similar text, amount or time is never proof that two
 * observations are the same economic movement — see [EconomicIdentity].
 */
data class ObservationIdentity(
    val namespace: String,
    val sourceItemId: String,
)
