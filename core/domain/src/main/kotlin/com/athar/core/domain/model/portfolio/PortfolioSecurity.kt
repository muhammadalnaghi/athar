package com.athar.core.domain.model.portfolio

/**
 * A security the user holds or traded, with its (possibly unknown) asset class.
 *
 * `assetClass == null` means the user has not classified it; allocation reporting
 * must surface it as an explicit unknown, never guess.
 */
data class PortfolioSecurity(
    val securityId: String,
    val name: String,
    val assetClass: AssetClass?,
)
