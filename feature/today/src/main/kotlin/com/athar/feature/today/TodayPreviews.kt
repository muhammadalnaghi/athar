package com.athar.feature.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate
import java.time.YearMonth

/**
 * Synthetic previews only — no real ledger data, no inferred monetary values.
 * They cover the required states: a full 12-pending tray, an empty month, large
 * money, Arabic and English, narrow width, and 1.3 font scale. Device
 * screenshot/readback is owned by the parent QA pass, not claimed here.
 */
private val PreviewMonth: YearMonth = YearMonth.of(2026, 6)

private fun previewTx(
    id: String,
    amount: String,
    type: TxType = TxType.EXPENSE,
    status: TxStatus = TxStatus.CONFIRMED,
): Transaction = Transaction(
    id = id,
    accountId = "acc-preview",
    type = type,
    amount = Money.of(amount),
    date = LocalDate(2026, 6, 14),
    occurredAt = kotlinx.datetime.Instant.parse("2026-06-14T10:15:00Z"),
    merchant = "متجر التجارب $id",
    merchantNormalized = "preview-$id",
    categoryId = null,
    notes = null,
    source = IngestSource.MANUAL,
    sourceRefId = "preview-$id",
    status = status,
    confidence = 0.9f,
    createdAt = kotlinx.datetime.Instant.parse("2026-06-14T10:15:00Z"),
    updatedAt = kotlinx.datetime.Instant.parse("2026-06-14T10:15:00Z"),
)

private fun previewState(
    pendingCount: Int = 0,
    todayCount: Int = 4,
    recentCount: Int = 6,
    largeMoney: Boolean = false,
): TodayState {
    val scale = if (largeMoney) "987654.32" else "1000"
    return TodayState.empty(PreviewMonth).copy(
        netFlow = Money.of(scale).minus(Money.of("640")),
        totalIncome = Money.of(scale),
        totalExpense = Money.of("640"),
        todayNet = Money.of(scale).minus(Money.of("640")),
        netWorth = Money.of(if (largeMoney) "12345678.5" else "18400"),
        today = (1..todayCount).map { previewTx("t$it", "12.5") }.toPersistentList(),
        recent = (1..recentCount).map { previewTx("r$it", "48") }.toPersistentList(),
        pending = (1..pendingCount).map { previewTx("p$it", "230.75", status = TxStatus.PENDING) }.toPersistentList(),
        dismissedToday = persistentListOf(),
        repeatedBacklogNudge = if (pendingCount > 0) {
            TodayRepeatedBacklogNudge(groupCount = 3, transactionCount = 9, largestGroupCount = 5)
        } else {
            null
        },
        isLoading = false,
    )
}

@Preview(name = "12 pending · tray caps · Arabic", locale = "ar", showBackground = true, widthDp = 412)
@Composable
private fun PreviewTodayTwelvePendingArabic() {
    AtharTheme {
        TodayContent(
            state = previewState(pendingCount = 12),
            onEvent = {},
        )
    }
}

@Preview(name = "12 pending · narrow width · stacked pills", locale = "ar", showBackground = true, widthDp = 320)
@Composable
private fun PreviewTodayNarrowArabic() {
    AtharTheme {
        TodayContent(
            state = previewState(pendingCount = 12),
            onEvent = {},
        )
    }
}

@Preview(name = "12 pending · font scale 1.3 · Arabic", locale = "ar", showBackground = true, widthDp = 360, fontScale = 1.3f)
@Composable
private fun PreviewTodayScaledFontArabic() {
    AtharTheme {
        TodayContent(
            state = previewState(pendingCount = 12),
            onEvent = {},
        )
    }
}

@Preview(name = "Large money · English", locale = "en", showBackground = true, widthDp = 412)
@Composable
private fun PreviewTodayLargeMoneyEnglish() {
    AtharTheme {
        TodayContent(
            state = previewState(largeMoney = true),
            onEvent = {},
        )
    }
}

@Preview(name = "Large money · narrow · English", locale = "en", showBackground = true, widthDp = 320, fontScale = 1.3f)
@Composable
private fun PreviewTodayLargeMoneyNarrowEnglish() {
    AtharTheme {
        TodayContent(
            state = previewState(largeMoney = true),
            onEvent = {},
        )
    }
}

@Preview(name = "Empty month · Arabic", locale = "ar", showBackground = true, widthDp = 412)
@Composable
private fun PreviewTodayEmptyArabic() {
    AtharTheme {
        TodayContent(
            state = previewState(todayCount = 0, recentCount = 0),
            onEvent = {},
        )
    }
}

@Preview(name = "Empty month with pending · English", locale = "en", showBackground = true, widthDp = 412)
@Composable
private fun PreviewTodayEmptyWithPendingEnglish() {
    AtharTheme {
        TodayContent(
            state = previewState(pendingCount = 3, todayCount = 0, recentCount = 0),
            onEvent = {},
        )
    }
}

/** Narrow-width provider kept for component-level pill checks inside previews. */
private class WidthScaleProvider : PreviewParameterProvider<Float> {
    override val values = sequenceOf(1.0f, 1.3f, 2.0f)
}

@Preview(name = "Pill stacking rule", showBackground = true, widthDp = 320)
@Composable
private fun PreviewSummaryPillsScaled(
    @PreviewParameter(WidthScaleProvider::class) fontScale: Float,
) {
    AtharTheme {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(
                    density = androidx.compose.ui.platform.LocalDensity.current.density,
                    fontScale = fontScale,
                ),
        ) {
            androidx.compose.foundation.layout.Column {
                SummaryPills(
                    totalIncome = Money.of("987654.32"),
                    totalExpense = Money.of("64120.5"),
                    netWorth = Money.of("12345678.5"),
                )
                AtharText(text = "fontScale=$fontScale")
            }
        }
    }
}
