package com.athar.core.domain.model.portfolio

/**
 * Coarse asset classification used by allocation reporting.
 *
 * An *unknown* classification is modeled as `null` on [PortfolioSecurity.assetClass],
 * not as an enum entry, so callers can never silently bucket unclassified holdings
 * into a real class.
 */
enum class AssetClass {
    EQUITY,
    SUKUK,
    CASH,
    OTHER,
}
