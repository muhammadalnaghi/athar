package com.athar.feature.widgets

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.selectOperatingTransactions
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.position.DeclaredPositionSnapshot
import com.athar.core.domain.model.position.latestDeclaredPosition

internal data class WidgetFlow(val income: Money, val expense: Money) {
    val net: Money get() = income - expense
}

/** Same confirmed operating scope as the app; no implicit currency conversion. */
internal fun widgetFlow(rows: List<Transaction>, currency: String): WidgetFlow {
    val operating = selectOperatingTransactions(rows.filter {
        it.status == TxStatus.CONFIRMED && it.amount.currency == currency
    })
    return WidgetFlow(
        Money.sumAmounts(operating.income.map { it.amount }, currency),
        Money.sumAmounts(operating.expenses.map { it.amount }, currency),
    )
}

/** One dated declared document, never a sum of overlapping documents or cash flows. */
internal fun latestDeclaredPositionView(
    snapshots: List<DeclaredPositionSnapshot>,
    currency: String,
): WidgetDataLoader.DeclaredPositionView? = latestDeclaredPosition(snapshots, currency)?.let {
    WidgetDataLoader.DeclaredPositionView(
        asOfDate = it.asOfDate,
        net = Money.of(requireNotNull(it.netByCurrency[currency]), currency),
        revision = it.revision,
    )
}
