package com.athar.feature.plan.portfolio

import com.athar.core.domain.model.portfolio.AssetClass
import java.math.BigDecimal

/** Feature inputs only: financial ledger fields never participate in this edit. */
data class PortfolioAllocationDraft(
    val original: List<TargetInput>,
    val percentages: Map<String, String>,
    val clearAll: Boolean = false,
)
enum class AllocationWriteProblem { INVALID, TOTAL, EMPTY, UNSUPPORTED, CONFLICT, STORAGE }
data class PortfolioAllocationWrite(
    val token: String,
    val planId: String,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val problem: AllocationWriteProblem? = null,
)
sealed interface AllocationTargetResult {
    data class Valid(val targets: List<TargetInput>) : AllocationTargetResult
    data class Invalid(val problem: AllocationWriteProblem) : AllocationTargetResult
}

fun allocationDraftOf(targets: List<TargetInput>): PortfolioAllocationDraft = PortfolioAllocationDraft(
    original = targets.toList(),
    percentages = AssetClass.entries.associate { asset ->
        val source = targets.firstOrNull { it.assetClass == asset.name && it.kind == "POINT" }
        asset.name to (source?.fraction?.toBigDecimalOrNull()?.movePointRight(2)?.toPlainString().orEmpty())
    },
)

/** Preserve unchanged stored fractions and RANGE targets, and require explicit whole-plan clear. */
internal fun validateAllocationDraft(draft: PortfolioAllocationDraft): AllocationTargetResult {
    fun invalid(problem: AllocationWriteProblem) = AllocationTargetResult.Invalid(problem)
    val classes = AssetClass.entries.map { it.name }.toSet()
    if (draft.original.map { it.assetClass }.distinct().size != draft.original.size) return invalid(AllocationWriteProblem.UNSUPPORTED)
    if (draft.original.any { it.assetClass !in classes || it.kind !in setOf("POINT", "RANGE") } ||
        draft.percentages.keys.any { it !in classes }) return invalid(AllocationWriteProblem.UNSUPPORTED)
    if (draft.clearAll) return AllocationTargetResult.Valid(emptyList())
    val targets = draft.original.filter { it.kind == "RANGE" }.toMutableList()
    for (asset in AssetClass.entries) {
        if (targets.any { it.assetClass == asset.name }) continue
        val raw = draft.percentages[asset.name].orEmpty()
        if (raw.isBlank()) continue
        val text = raw.trim().replace('٫', '.').map { c ->
            if (c in '٠'..'٩') ('0'.code + c.code - '٠'.code).toChar() else c
        }.joinToString("")
        if (text.length > 128 || text.any { it == 'e' || it == 'E' }) return invalid(AllocationWriteProblem.INVALID)
        val percent = text.toBigDecimalOrNull() ?: return invalid(AllocationWriteProblem.INVALID)
        if (percent.signum() < 0 || percent > BigDecimal("100") || percent.stripTrailingZeros().scale() > 6) {
            return invalid(AllocationWriteProblem.INVALID)
        }
        val old = draft.original.firstOrNull { it.assetClass == asset.name && it.kind == "POINT" }
        val oldPercent = old?.fraction?.toBigDecimalOrNull()?.movePointRight(2)?.toPlainString()
        if (percent.signum() == 0 && (old == null || raw != oldPercent)) continue
        targets += if (old != null && raw == oldPercent) old else
            TargetInput(asset.name, "POINT", fraction = percent.movePointLeft(2).toPlainString())
    }
    if (targets.isEmpty()) return invalid(AllocationWriteProblem.EMPTY)
    if (targets.all { it.kind == "POINT" } && targets.fold(BigDecimal.ZERO) { sum, t ->
        sum + requireNotNull(t.fraction).toBigDecimal()
    }.compareTo(BigDecimal.ONE) != 0) return invalid(AllocationWriteProblem.TOTAL)
    val originalOrder = draft.original.mapIndexed { index, target -> target.assetClass to index }.toMap()
    return AllocationTargetResult.Valid(targets.sortedBy {
        originalOrder[it.assetClass] ?: (draft.original.size + AssetClass.valueOf(it.assetClass).ordinal)
    })
}
