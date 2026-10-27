package com.athar.feature.widgets

import com.athar.core.common.money.Money
import com.athar.core.designsystem.display.MoneyTextFormat
import java.util.Locale

/** Exact shared number formatting; ISO suffix is readable with the launcher system font. */
internal fun widgetMoneyText(money: Money, localeTag: String): String {
    val locale = if (localeTag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(localeTag)
    return MoneyTextFormat.format(money.amount, money.currency, locale)
        .substringBeforeLast(' ') + " " + money.currency
}
