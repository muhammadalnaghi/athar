package com.athar.feature.plan.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.component.AtharAmountField
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.feature.plan.InvestmentEditorSheet
import com.athar.feature.plan.InvestmentPrimaryButton
import com.athar.feature.plan.PortfolioEntryDraft
import com.athar.feature.plan.R
import java.math.BigDecimal
import java.math.RoundingMode

/** Formats an 8dp fraction as a plain percent string; display only, never recomputed. */
internal fun percentText(value: BigDecimal): String =
    value.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + "%"

/** Maps a safe rejection token to a localized line; unknown tokens stay generic, never leak payload. */
@Composable
internal fun PortfolioRejectMessage(token: String) {
    val theme = AtharTheme
    val specific = when (token) {
        "UNSUPPORTED_SHORT_SALE" -> R.string.plan_portfolio_reject_UNSUPPORTED_SHORT_SALE
        "INSUFFICIENT_CASH" -> R.string.plan_portfolio_reject_INSUFFICIENT_CASH
        "DUPLICATE_EVENT_ID", "DUPLICATE_SECURITY_ID" -> R.string.plan_portfolio_reject_DUPLICATE_EVENT_ID
        "UNKNOWN_SECURITY" -> R.string.plan_portfolio_reject_UNKNOWN_SECURITY
        "CROSS_CURRENCY" -> R.string.plan_portfolio_reject_CROSS_CURRENCY
        "NON_POSITIVE_AMOUNT" -> R.string.plan_portfolio_reject_NON_POSITIVE_AMOUNT
        "NON_POSITIVE_QUANTITY" -> R.string.plan_portfolio_reject_NON_POSITIVE_QUANTITY
        "NON_POSITIVE_UNIT_PRICE" -> R.string.plan_portfolio_reject_NON_POSITIVE_UNIT_PRICE
        "AMBIGUOUS_QUOTES" -> R.string.plan_portfolio_reject_AMBIGUOUS_QUOTES
        "NEGATIVE_QUOTE_PRICE" -> R.string.plan_portfolio_reject_NEGATIVE_QUOTE_PRICE
        "INCONSISTENT_REINVESTMENT" -> R.string.plan_portfolio_reject_INCONSISTENT_REINVESTMENT
        "AMBIGUOUS_ORDERING" -> R.string.plan_portfolio_reject_AMBIGUOUS_ORDERING
        "MALFORMED_OPENING_DATE" -> R.string.plan_portfolio_opening_date_order
        else -> null
    }
    AtharText(
        text = specific?.let { stringResource(it) } ?: stringResource(R.string.plan_portfolio_problem_generic, token),
        style = theme.typography.caption,
        color = theme.colors.ember,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortfolioChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val theme = AtharTheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(if (isSelected) theme.colors.ember else theme.colors.parchment)
                    .then(
                        if (isSelected) {
                            Modifier
                        } else {
                            Modifier.border(0.5.dp, theme.colors.divider, RoundedCornerShape(theme.spacing.s))
                        },
                    )
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(
                    text = label,
                    style = theme.typography.caption,
                    color = if (isSelected) theme.colors.parchment else theme.colors.ink,
                )
            }
        }
    }
}

@Composable
internal fun PortfolioCreateSheet(onDismiss: () -> Unit, onCreate: (String, String, String, String) -> Unit) {
    val theme = AtharTheme
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("SAR") }
    var opening by remember { mutableStateOf("0") }
    var openingDate by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val invalidDate = openingDate.isNotBlank() && PortfolioInputText.date(openingDate) == null
    com.athar.feature.plan.InvestmentEditorSheet(stringResource(R.string.plan_portfolio_create_title), onDismiss) {
        AtharTextField(name, { name = it }, stringResource(R.string.plan_portfolio_create_name), Modifier.fillMaxWidth())
        AtharTextField(
            currency, { currency = it }, stringResource(R.string.plan_portfolio_create_currency),
            Modifier.fillMaxWidth(),
        )
        AtharAmountField(
            opening, { opening = it },
            label = stringResource(R.string.plan_portfolio_create_opening), modifier = Modifier.fillMaxWidth(),
        )
        AtharText(
            stringResource(R.string.plan_portfolio_scope_empty, currency),
            style = theme.typography.caption, color = theme.colors.muted,
        )
        AtharTextField(
            openingDate,
            { openingDate = it },
            stringResource(R.string.plan_portfolio_opening_date_label),
            Modifier.fillMaxWidth(),
            isError = attempted && invalidDate,
            supportingText = stringResource(
                if (attempted && invalidDate) R.string.plan_portfolio_opening_date_invalid else R.string.plan_portfolio_opening_date_hint,
            ),
        )
        InvestmentPrimaryButton(stringResource(R.string.plan_portfolio_section_create)) {
            attempted = true
            if (!invalidDate) onCreate(name, currency, opening, openingDate)
        }
    }
}

@Composable
internal fun PortfolioSecuritySheet(onDismiss: () -> Unit, onSave: (String, String, String?) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var classToken by remember { mutableStateOf("") }
    com.athar.feature.plan.InvestmentEditorSheet(stringResource(R.string.plan_portfolio_security_title), onDismiss) {
        AtharTextField(id, { id = it }, stringResource(R.string.plan_portfolio_security_id), Modifier.fillMaxWidth())
        AtharTextField(name, { name = it }, stringResource(R.string.plan_portfolio_security_name), Modifier.fillMaxWidth())
        AtharText(stringResource(R.string.plan_portfolio_security_class), style = AtharTheme.typography.caption, color = AtharTheme.colors.muted)
        PortfolioChips(
            options = listOf(
                "" to stringResource(R.string.plan_portfolio_class_none),
                "EQUITY" to stringResource(R.string.plan_portfolio_class_equity),
                "SUKUK" to stringResource(R.string.plan_portfolio_class_sukuk),
                "CASH" to stringResource(R.string.plan_portfolio_class_cash),
                "OTHER" to stringResource(R.string.plan_portfolio_class_other),
            ),
            selected = classToken,
            onSelect = { classToken = it },
        )
        InvestmentPrimaryButton(stringResource(R.string.plan_portfolio_add_security)) {
            onSave(id, name, classToken.ifEmpty { null })
        }
    }
}

@Composable
internal fun PortfolioQuoteSheet(
    securityIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var securityId by remember { mutableStateOf(securityIds.firstOrNull().orEmpty()) }
    var date by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    com.athar.feature.plan.InvestmentEditorSheet(stringResource(R.string.plan_portfolio_add_quote), onDismiss) {
        if (securityIds.isNotEmpty()) {
            PortfolioChips(options = securityIds.map { it to it }, selected = securityId, onSelect = { securityId = it })
        }
        AtharTextField(date, { date = it }, stringResource(R.string.plan_portfolio_entry_date), Modifier.fillMaxWidth())
        AtharAmountField(
            price, { price = it },
            label = stringResource(R.string.plan_portfolio_quote_price), modifier = Modifier.fillMaxWidth(),
        )
        InvestmentPrimaryButton(stringResource(R.string.plan_portfolio_add_quote)) {
            onSave(securityId, date, price)
        }
    }
}

/**
 * Add form for one dated entry — or, when [initial] is present (built by
 * [com.athar.feature.plan.editDraftOf]), the in-place correction editor: every field
 * starts from the exact stored literal, and dismissing writes nothing. In edit mode
 * the note is shown for every kind so an imported annotation is always visible and
 * user-controlled, never silently dropped.
 */
@Composable
internal fun PortfolioEntrySheet(
    securityIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (PortfolioEntryDraft) -> Unit,
    initial: PortfolioEntryDraft? = null,
) {
    val theme = AtharTheme
    val editing = initial != null
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: PortfolioEntryKind.BUY) }
    var securityId by remember(initial) { mutableStateOf(initial?.securityId ?: securityIds.firstOrNull().orEmpty()) }
    var date by remember(initial) { mutableStateOf(initial?.dateText.orEmpty()) }
    var quantity by remember(initial) { mutableStateOf(initial?.quantityText.orEmpty()) }
    var price by remember(initial) { mutableStateOf(initial?.priceText.orEmpty()) }
    var fee by remember(initial) { mutableStateOf(initial?.feeText.orEmpty()) }
    var amount by remember(initial) { mutableStateOf(initial?.amountText.orEmpty()) }
    var external by remember(initial) { mutableStateOf(initial?.externalDestination ?: false) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }

    val needsSecurity = kind == PortfolioEntryKind.BUY || kind == PortfolioEntryKind.SELL ||
        kind == PortfolioEntryKind.CASH_DIVIDEND || kind == PortfolioEntryKind.REINVESTMENT
    val isTrade = kind == PortfolioEntryKind.BUY || kind == PortfolioEntryKind.SELL
    val needsAmount = !isTrade

    com.athar.feature.plan.InvestmentEditorSheet(
        stringResource(if (editing) R.string.plan_portfolio_edit_title else R.string.plan_portfolio_entry_title),
        onDismiss,
    ) {
        PortfolioChips(
            options = PortfolioEntryKind.entries.map { entryKind ->
                entryKind.name to kindLabel(entryKind)
            },
            selected = kind.name,
            onSelect = { kind = PortfolioEntryKind.valueOf(it) },
        )
        if (editing) {
            AtharText(
                stringResource(R.string.plan_portfolio_edit_hint),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        if (needsSecurity && securityIds.isNotEmpty()) {
            PortfolioChips(options = securityIds.map { it to it }, selected = securityId, onSelect = { securityId = it })
        }
        AtharTextField(date, { date = it }, stringResource(R.string.plan_portfolio_entry_date), Modifier.fillMaxWidth())
        if (isTrade || kind == PortfolioEntryKind.REINVESTMENT) {
            AtharAmountField(quantity, { quantity = it }, label = stringResource(R.string.plan_portfolio_entry_qty), modifier = Modifier.fillMaxWidth())
            AtharAmountField(price, { price = it }, label = stringResource(R.string.plan_portfolio_entry_price), modifier = Modifier.fillMaxWidth())
        }
        if (isTrade) {
            AtharAmountField(fee, { fee = it }, label = stringResource(R.string.plan_portfolio_entry_fee), modifier = Modifier.fillMaxWidth())
        }
        if (needsAmount) {
            AtharAmountField(amount, { amount = it }, label = stringResource(R.string.plan_portfolio_entry_amount), modifier = Modifier.fillMaxWidth())
        }
        if (kind == PortfolioEntryKind.CASH_DIVIDEND) {
            PortfolioChips(
                options = listOf(
                    false.toString() to stringResource(R.string.plan_portfolio_div_cash),
                    true.toString() to stringResource(R.string.plan_portfolio_entry_external),
                ),
                selected = external.toString(),
                onSelect = { external = it.toBoolean() },
            )
        }
        if (editing || kind == PortfolioEntryKind.CONTRIBUTION || kind == PortfolioEntryKind.WITHDRAWAL || kind == PortfolioEntryKind.FEE) {
            AtharTextField(note, { note = it }, stringResource(R.string.plan_portfolio_entry_note), Modifier.fillMaxWidth())
        }
        InvestmentPrimaryButton(stringResource(if (editing) R.string.plan_portfolio_edit_save else R.string.plan_portfolio_add_entry)) {
            onSave(
                PortfolioEntryDraft(
                    kind = kind,
                    securityId = securityId,
                    dateText = date,
                    quantityText = quantity,
                    priceText = price,
                    feeText = fee,
                    amountText = amount,
                    externalDestination = external,
                    note = note,
                ),
            )
        }
    }
}

@Composable
internal fun kindLabel(kind: PortfolioEntryKind): String = stringResource(
    when (kind) {
        PortfolioEntryKind.BUY -> R.string.plan_portfolio_kind_BUY
        PortfolioEntryKind.SELL -> R.string.plan_portfolio_kind_SELL
        PortfolioEntryKind.CONTRIBUTION -> R.string.plan_portfolio_kind_CONTRIBUTION
        PortfolioEntryKind.WITHDRAWAL -> R.string.plan_portfolio_kind_WITHDRAWAL
        PortfolioEntryKind.FEE -> R.string.plan_portfolio_kind_FEE
        PortfolioEntryKind.CASH_DIVIDEND -> R.string.plan_portfolio_kind_CASH_DIVIDEND
        PortfolioEntryKind.REINVESTMENT -> R.string.plan_portfolio_kind_REINVESTMENT
    },
)
