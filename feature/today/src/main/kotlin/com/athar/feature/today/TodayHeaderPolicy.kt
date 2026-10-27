package com.athar.feature.today

import com.athar.core.common.money.Money

/** Keep the labelled month useful when the feed has no activity today; no money animation cast. */
internal fun todayLandmarkMoney(state: TodayState): Money =
    if (state.today.isEmpty()) state.netFlow else state.todayNet

internal fun shouldShowHeaderHistory(state: TodayState): Boolean =
    !state.isLoading && state.pending.isEmpty() && state.dismissedToday.isEmpty()
