package com.athar.core.domain.model.financialgraph

/**
 * What supports a category assignment. Independent optional classification
 * metadata only — it never gates, reshapes or re-prices a graph.
 */
enum class CategoryBasis {
    /** Assigned by the rules-first categorization engine. */
    RULE_DERIVED,

    /** Explicitly stated by the user; distinct evidence from any rule. */
    USER_DECLARED,
}

/**
 * Optional category metadata attached to a posted event. Absence
 * (null) is a normal state: a missing category must never suppress known
 * posted money. A blank [categoryId] is invalid input, flagged by the graph
 * factory as [GraphBuildIssue.BLANK_CATEGORY_ID], never repaired.
 */
data class CategoryAssignment(
    val categoryId: String,
    val basis: CategoryBasis,
)
