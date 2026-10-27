package com.athar.core.domain.model.financialgraph

/**
 * Cross-source identity of one economic movement (WM028-11), deliberately
 * separate from [ObservationIdentity]. Equality here can only come from a
 * proven issuer reference — never from similar text, amount or time.
 */
sealed interface EconomicIdentity {
    /**
     * A reference issued by the financial institution that proves two
     * observations point at the same economic movement.
     *
     * Blank values are invalid identity input: the graph factory flags them
     * as [GraphBuildIssue.INVALID_ECONOMIC_IDENTITY] instead of repairing or
     * generating a replacement reference. No random identifiers exist inside
     * the functional core.
     */
    data class IssuerReference(
        val issuer: String,
        val reference: String,
    ) : EconomicIdentity
}
