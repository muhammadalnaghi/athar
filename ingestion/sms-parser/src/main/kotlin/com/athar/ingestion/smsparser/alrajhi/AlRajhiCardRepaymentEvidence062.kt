package com.athar.ingestion.smsparser.alrajhi

import com.athar.ingestion.smsparser.Normalize

/**
 * WM062: template-context gate for explicit posted evidence on the Al Rajhi real
 * credit-card repayment template (`al-rajhi-credit-card-payment`, headings
 * `Credit Card:Payment` / `بطاقة ائتمانية:سداد`; WM031 Barq pattern:
 * registry-supplied `validatedCardTemplate`).
 *
 * Neither heading satisfies the generic completed-verb language in
 * [com.athar.ingestion.smsparser.BankPostedLanguage] — `Credit Card:Payment` is not
 * a `payment … completed|successful` phrase and the Arabic heading lacks the
 * `تم …` verb — so without template context these completed repayments stay
 * HEURISTIC_ONLY.
 *
 * Authority is deliberately narrower than the template's own match:
 * - the payment amount must carry an explicit `SAR` / `SR` / `ر.س` marker (the
 *   template itself also accepts an unmarked number; that bare shape, and any
 *   foreign-currency amount, gains nothing here);
 * - additional monetary lines — fees, commissions, taxes, a second marked amount,
 *   or any other currency-token-bearing line — refuse certification rather than
 *   certify a composite single-principal repayment. Result-balance lines
 *   (`Balance` / `رصيد`) are the only monetary lines ignored;
 * - never derives from category, confidence, money or date extraction.
 *
 * [com.athar.ingestion.smsparser.BankPostedLanguage] conservative precedence is
 * unchanged: declined / released / scheduled / authorized (incl. literal `*PEND`,
 * `* PEND`, `*PENDING`) / instruction evidence always wins over this upgrade.
 */
internal object AlRajhiCardRepaymentEvidence062 {

    private val options = setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)

    /** Mirrors the winning template's own heading set, on the same normalized body. */
    private val heading = Regex(
        """^(?:Credit\s+Card\s*:\s*Payment|بطاقة\s+(?:ائتمانية|إئتمانية|فيزا)\s*:\s*سداد(?:\s+بـ[^\n\r]+)?)\s*$""",
        options,
    )

    private val amount = """\d{1,3}(?:[ ,]\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?"""

    private val marker = """(?:SAR|SR|ر\.?\s*س)"""
    private val markedValue = """(?:$marker\s+(?:$amount)|(?:$amount)\s*$marker)"""

    /** Match the WHOLE field: a second fee/currency on this line is not exempt. */
    private val markedPaymentAmount = Regex(
        """^(?:Amount|مبلغ)\s*:\s*$markedValue\s*$""",
        options,
    )
    private val compactPayment = Regex(
        """^بطاقة\s+(?:ائتمانية|إئتمانية|فيزا)\s*:\s*سداد\s+بـ\s*$markedValue\s*$""",
        options,
    )

    /** Result-balance lines are not movements; every other monetary line is suspect. */
    private val balanceLine = Regex("""^(?:Balance|رصيد)\s*:\s*(?:$markedValue|(?:$amount))\s*$""", options)

    /** Fee / commission / tax lines refuse certification even without a currency marker. */
    private val feeLine = Regex(
        """^(?:Amount|Payment|Settlement|Transfer|Refund|Debit|Credit|Charges?|Fee|Fees|Commission|VAT|Tax|مبلغ|دفعة|تحويل|رسوم|عمولة|ضريبة)\b""",
        options,
    )

    /** Any currency token together with a digit marks an additional monetary line. */
    private val currencyToken = Regex(
        "(?<![A-Za-z])(?:" + java.util.Currency.getAvailableCurrencies()
            .map { Regex.escape(it.currencyCode) }.sorted().joinToString("|") + "|SR|ر\\.?\\s*س)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True only when the body — normalized exactly like the template normalizes it —
     * carries the repayment heading, exactly one explicitly SAR/SR/ر.س-marked payment
     * amount, and no additional monetary line beyond the result balance.
     */
    fun validatesCardRepaymentContext(body: String): Boolean {
        val lines = Normalize.digits(body).lineSequence().map { it.trim() }.toList()
        if (lines.count { heading.matches(it) } != 1) return false
        if (lines.count { markedPaymentAmount.matches(it) || compactPayment.matches(it) } != 1) return false
        return lines.none { line ->
            when {
                markedPaymentAmount.matches(line) || compactPayment.matches(line) -> false
                heading.matches(line) && line.none { it.isDigit() } -> false
                balanceLine.matches(line) -> false
                feeLine.containsMatchIn(line) && line.any { it.isDigit() } -> true
                currencyToken.containsMatchIn(line) && line.any { it.isDigit() } -> true
                else -> false
            }
        }
    }
}
