package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import java.time.YearMonth

class TodayHeaderPolicyTest {
    @Test fun `no activity today keeps exact useful month money instead of an irrelevant daily zero`() {
        val state = TodayState.empty(YearMonth.of(2026, 10)).copy(
            netFlow = Money.of("-100000001.01"), todayNet = Money.zero(), isLoading = false,
        )
        assertThat(todayLandmarkMoney(state).amount.toPlainString()).isEqualTo("-100000001.01")
        assertThat(todayLandmarkMoney(state).currency).isEqualTo("SAR")
    }
    @Test fun `loaded calm state keeps an action to full history without inventing review work`() {
        val state = TodayState.empty(YearMonth.of(2026, 10)).copy(isLoading = false)
        assertThat(shouldShowHeaderHistory(state)).isTrue()
    }
    @Test fun `initial loading and existing review entries do not duplicate the header history action`() {
        val state = TodayState.empty(YearMonth.of(2026, 10))
        assertThat(shouldShowHeaderHistory(state)).isFalse()
        assertThat(shouldShowHeaderHistory(state.copy(isLoading = false,
            pending = persistentListOf(Fixtures.transaction(status = com.athar.core.domain.model.TxStatus.PENDING)),
        ))).isFalse()
        assertThat(shouldShowHeaderHistory(state.copy(isLoading = false,
            dismissedToday = persistentListOf(Fixtures.transaction(status = com.athar.core.domain.model.TxStatus.DISMISSED)),
        ))).isFalse()
    }
    @Test fun `actual today activity retains exact daily landmark`() {
        val state = TodayState.empty(YearMonth.of(2026, 10)).copy(
            netFlow = Money.of("99999"), todayNet = Money.of("-42.01"),
            today = persistentListOf(Fixtures.transaction()), isLoading = false,
        )
        assertThat(todayLandmarkMoney(state)).isEqualTo(state.todayNet)
    }
}
