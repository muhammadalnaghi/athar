package com.athar.core.designsystem.component

import java.math.BigDecimal

/** Only the animation's dimensionless progress may originate as a floating-point value. */
internal object MoneyAnimation {
    fun amountAt(from: BigDecimal, to: BigDecimal, progress: BigDecimal): BigDecimal {
        require(progress >= BigDecimal.ZERO && progress <= BigDecimal.ONE)
        return when {
            progress.signum() == 0 -> from
            progress.compareTo(BigDecimal.ONE) == 0 -> to
            else -> from + (to - from) * progress
        }
    }
}
