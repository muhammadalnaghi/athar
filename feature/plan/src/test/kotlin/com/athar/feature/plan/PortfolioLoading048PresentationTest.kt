package com.athar.feature.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PortfolioLoading048PresentationTest {
    @Test
    fun `initial state does not claim stored portfolios are empty`() {
        val initial = InvestmentsState.initial()
        assertEquals(
            R.string.plan_portfolio_loading,
            portfolioListMessage(initial.isLoading, initial.portfolios.isNotEmpty()),
        )
    }

    @Test
    fun `settled empty data keeps the existing empty explanation`() {
        assertEquals(R.string.plan_portfolio_empty_hint, portfolioListMessage(false, false))
    }

    @Test
    fun `ready rows show no loading or empty claim`() {
        assertNull(portfolioListMessage(false, true))
    }

    @Test
    fun `retained rows stay visible during secondary refresh`() {
        assertNull(portfolioListMessage(true, true))
    }
}
