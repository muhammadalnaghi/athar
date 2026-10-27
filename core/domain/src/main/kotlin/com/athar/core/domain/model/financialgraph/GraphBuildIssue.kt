package com.athar.core.domain.model.financialgraph

/**
 * Typed, recoverable structural rejection reasons accumulated by the graph
 * factory. Rejections never produce a partial graph, a zero placeholder or a
 * silent repair (WM028-11). Declaration order mirrors typical accumulation
 * order: identity, component, fee, leg, metadata.
 */
enum class GraphBuildIssue {
    /** Draft event id is blank. */
    BLANK_EVENT_ID,

    /** Draft source namespace is blank. */
    BLANK_SOURCE_NAMESPACE,

    /** A component ref is blank. */
    BLANK_COMPONENT_ID,

    /** The same component ref appears more than once. */
    DUPLICATE_COMPONENT_ID,

    /** A non-fee component carries an explicit fee relation. */
    FEE_RELATION_FORBIDDEN,

    /** A FEE component states no fee relation. */
    FEE_RELATION_REQUIRED,

    /** A stated fee relation lacks its required evidence reference. */
    FEE_EVIDENCE_REQUIRED,

    /** A component amount is zero or negative. */
    NONPOSITIVE_COMPONENT_AMOUNT,

    /** A component currency differs from the booked amount's currency. */
    COMPONENT_CURRENCY_MISMATCH,

    /** A component type is not allowed for the event's economic kind. */
    COMPONENT_NOT_ALLOWED_FOR_KIND,

    /** A linked represented-fee reference is duplicated or invalid. */
    DUPLICATE_LINKED_FEE_REFERENCE,

    /** A leg account ref is blank. */
    BLANK_ACCOUNT_REF,

    /** A leg delta is zero. */
    ZERO_LEG_DELTA,

    /** A leg attributes to a component ref that no component defines. */
    UNKNOWN_LEG_COMPONENT_REFERENCE,

    /** A category id is blank. */
    BLANK_CATEGORY_ID,

    /** The fee is both linked as represented elsewhere and charged again here. */
    DUPLICATE_FEE_REPRESENTATION,

    /** INCLUDED fee composition does not sum to the booked total. */
    COMPOSITION_CONFLICT,

    /** Same-currency transfer/repayment legs do not balance. */
    CONFLICTING_LEG_AMOUNTS,

    /** Legs overshoot the booked money for the kind's coverage rules. */
    LEG_OVERSHOOT,

    /** A leg direction contradicts the event's signed-net-worth convention. */
    LEG_DIRECTION_CONFLICT,

    /** The supplied economic identity is blank or otherwise invalid input. */
    INVALID_ECONOMIC_IDENTITY,

    UNSUPPORTED_COMPONENT_AMOUNT,
    COMPONENT_EVIDENCE_REQUIRED,
    COMPONENT_UNSUPPORTED_PRECISION,
    INVALID_COMPONENT_CURRENCY,
    BLANK_LEG_ID,
    DUPLICATE_LEG_ID,
    UNSUPPORTED_LEG_AMOUNT,
    LEG_EVIDENCE_REQUIRED,
    LEG_UNSUPPORTED_PRECISION,
    INVALID_LEG_CURRENCY,
    DUPLICATE_FEE_EVIDENCE,
    LEG_COMPONENT_CURRENCY_MISMATCH,
    CONFLICTING_LEG_ACCOUNTS,
}

/**
 * Why an observation produces no replacement graph while retaining its typed
 * policy decision. Legacy evidence is preserved verbatim; non-posted,
 * unreliable, invalid and conflicting observations are never guessed into
 * posted money (WM028-11).
 */
enum class NoGraphReason {
    /** LegacyFacts/LEGACY_UNVERIFIED evidence — preserved verbatim, no replacement graph. */
    LEGACY_PRESERVED,

    /** Authorization/release/decline/notice/schedule — funds never posted. */
    NON_POSTED,

    /** Posting state is heuristic-only — never proof that funds moved. */
    UNRELIABLE_POSTING_EVIDENCE,

    /** The money value is heuristic-only — never proof of a transaction amount. */
    UNRELIABLE_AMOUNT_SUPPORT,

    /** No original and no settlement money — never a zero placeholder. */
    NO_KNOWN_AMOUNT,

    /** New-input money is invalid (unknown currency, precision, sign). */
    INVALID_INPUT_MONEY,

    /** The economic kind contradicts the explicitly observed direction. */
    KIND_DIRECTION_CONFLICT,
}
