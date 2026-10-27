package com.athar.feature.plan.portfolio

/** The stored input must reopen identically, not merely replay successfully in memory. */
internal fun encodeLoadablePortfolio(document: PortfolioPlanDocument): String? {
    val encoded = PortfolioPlanCodec.encode(document)
    val decoded = PortfolioPlanCodec.decode(encoded) as? PortfolioDecodeResult.Success ?: return null
    return encoded.takeIf { decoded.document == document }
}
