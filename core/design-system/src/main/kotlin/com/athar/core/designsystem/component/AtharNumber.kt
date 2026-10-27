package com.athar.core.designsystem.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate as animateProgress
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.athar.core.common.money.Money
import com.athar.core.designsystem.display.MoneyTextFormat
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Money / numeric display with tabular figures.
 *
 * Always use this for amounts. Master Brief §2.4 requires tabular numerals so money
 * columns align. §2.5 specifies "numbers animate by counting up/down, not crossfading" —
 * landmark figures count up over [CountUpDurationMs]; inline numbers render snappy.
 */
@Composable
fun AtharNumber(
    money: Money,
    modifier: Modifier = Modifier,
    landmark: Boolean = false,
    locale: Locale = currentUiLocale(),
    color: Color? = null,
    animate: Boolean = landmark,
) {
    val style: TextStyle = if (landmark) AtharTheme.typography.moneyLandmark else AtharTheme.typography.money
    val resolvedColor: Color = color ?: AtharTheme.colors.ink
    var displayedAmount by remember(money.currency) { mutableStateOf(money.amount) }
    LaunchedEffect(money.amount, money.currency, animate) {
        val from = displayedAmount
        val target = money.amount
        if (animate && from.compareTo(target) != 0) {
            val scale = maxOf(from.stripTrailingZeros().scale(), target.stripTrailingZeros().scale(), 0)
            animateProgress(initialValue = 0f, targetValue = 1f, animationSpec = CountUpSpec) { progress, _ ->
                // Progress is dimensionless. Neither endpoint ever becomes a Float/Double.
                displayedAmount = MoneyAnimation.amountAt(from, target, BigDecimal(progress.toString()))
                    .setScale(scale, RoundingMode.HALF_EVEN)
            }
        }
        displayedAmount = target
    }
    val text = MoneyTextFormat.format(if (animate) displayedAmount else money.amount, money.currency, locale)
    if (landmark) {
        LandmarkMoneyText(text = text, modifier = modifier, style = style, color = resolvedColor)
    } else {
        Text(text = text, modifier = modifier, color = resolvedColor, style = style)
    }
}

@Composable
private fun LandmarkMoneyText(text: String, modifier: Modifier, style: TextStyle, color: Color) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val fittedStyle = remember(text, style, measurer, width) {
            val size = LandmarkSizes.firstOrNull { candidate ->
                measurer.measure(
                    text = text,
                    style = style.copy(fontSize = candidate.sp, lineHeight = candidate.sp),
                    maxLines = 1,
                    softWrap = false,
                ).size.width <= width
            } ?: LandmarkSizes.last()
            style.copy(fontSize = size.sp, lineHeight = size.sp)
        }
        Text(
            text = text,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            color = color,
            style = fittedStyle,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val LandmarkSizes = listOf(64, 40, 28, 22, 17, 15, 13, 11)
private const val CountUpDurationMs = 200
private val CountUpSpec: AnimationSpec<Float> = tween(durationMillis = CountUpDurationMs, easing = EaseOut)

@Preview
@Composable
private fun PreviewLandmark() {
    AtharTheme {
        AtharNumber(money = Money.of("12847.00"), landmark = true)
    }
}

@Preview
@Composable
private fun PreviewInline() {
    AtharTheme {
        AtharNumber(money = Money.of("200.00"))
    }
}

@Preview(widthDp = 320, fontScale = 1.3f, locale = "ar")
@Composable
private fun PreviewLargeMoneyNarrow() {
    AtharTheme {
        AtharNumber(money = Money.of("-100000001.01"), landmark = true)
    }
}
