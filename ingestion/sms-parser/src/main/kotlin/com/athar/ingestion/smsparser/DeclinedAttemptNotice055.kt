package com.athar.ingestion.smsparser

import com.athar.core.domain.model.financial.PostingSupport

/**
 * WM055 — a conservative pure classifier for a single genuinely declined payment
 * attempt stated in prose (positive event-linked refusal grammar), as opposed to
 * the line-headed `Reason:`/`Status:` decline headers of [SingleDeclinedNotice053].
 * The parent pipeline consults it only on the success branch of a row whose body
 * evidence already reads DECLINED; it is not wired into any template and never
 * changes legacy parser outputs.
 *
 * Positive identification requires a payment-event noun (purchase/payment/
 * transaction/transfer, or Arabic عملية/الشراء/الدفع) linked within a single
 * clause to a refusal predicate — "Purchase of SAR25.00 at QA was declined",
 * "Your payment was not completed", "عملية شراء مرفوضة", "لم تتم عملية الشراء".
 * A merchant merely named DECLINED/FAILED (no auxiliary linking a noun to the
 * refusal), a conditional clause ("if payment failed"), or a refusal that only
 * survives inside quoted error text never matches.
 *
 * Guard: any genuine separately booked debit/fee/credit/refund/reversal/
 * successful retry — including on the same line or in Reason/Status fields —
 * blocks the classification. The check is per line, never aggregate posting
 * evidence (negative words dominate the aggregate DECLINED state), and the
 * refusal's own negation ("not completed", "لم تتم") is neutralized first so it
 * cannot masquerade as movement vocabulary. Unknown or ambiguous shapes return
 * false. The helper parses no amounts and infers posting from no category;
 * numeral/bidi normalization reuses the proven [Normalize.digits].
 */
object DeclinedAttemptNotice055 {
    private val options = setOf(RegexOption.IGNORE_CASE)

    private const val eventNouns = """(?:purchase|payment|transaction|transfer)"""

    /** "Purchase of SAR25.00 at QA was declined." */
    private val passiveRefusal = Regex(
        """\b$eventNouns\b[^\r\n]{0,60}?\b(?:was|were|is|has\s+been|had\s+been)\s+(?:declin\w*|reject\w*|denied|unsuccessful|failed)\b""",
        options,
    )

    /** "Your payment was not completed." */
    private val negatedCompletion = Regex(
        """\b$eventNouns\b[^\r\n]{0,60}?\b(?:was|were|is|has\s+been|had\s+been)\s+not\s+(?:completed|successful|processed|paid)\b""",
        options,
    )

    /** "Your payment failed" / "did not go through" / "could not be completed". */
    private val directRefusal = Regex(
        """\b$eventNouns\s+(?:has\s+failed|failed|did\s+not\s+(?:complete|go\s+through)|could\s+not\s+be\s+completed)\b""",
        options,
    )

    /** Keep the refusal adjacent to the event, never across a merchant descriptor. */
    private val arabicRejectedOperation = Regex(
        """(?:العملية|عملية)(?:\s+(?:شراء|الشراء|دفع|الدفع|سداد|السداد|تحويل|التحويل))?\s+مرفوض(?:ة)?(?:\s|$|[،,.!؟])""",
    )

    /** "تم رفض عملية الشراء" / "فشلت عملية الشراء". */
    private val arabicVerbedRefusal = Regex("""(?:تم\s+رفض|فشلت?)\s+(?:العملية|عملية)""")

    /** "لم تتم عملية الشراء" / "لم يتم الدفع". */
    private val arabicNotCompleted = Regex(
        """لم\s+(?:تتم|يتم|تكتمل|يكتمل|تنجح|ينجح)\s+(?:العملية|عملية|الشراء|الدفع|السداد)""",
    )

    private val positives = listOf(
        passiveRefusal,
        negatedCompletion,
        directRefusal,
        arabicRejectedOperation,
        arabicVerbedRefusal,
        arabicNotCompleted,
    )

    /** Hypothetical clauses state no completed refusal event. */
    private val conditionalClause = Regex(
        """\b(?:if|unless|in\s+case|when|should\s+(?:your|the|this|payment))\b|إذا|في\s+حال""",
        options,
    )

    /** Quoted error text is not the bank's own statement of the attempt. */
    private val quotedSegment = Regex(
        """\u201C[^\u201D\r\n]{0,160}\u201D|\u00AB[^\u00BB\r\n]{0,160}\u00BB|\u0022[^\u0022\r\n]{0,160}\u0022""",
    )

    /** The refusal's own negation must not look like booked money. */
    private val negatedCompletionText = Regex(
        """\bnot\s+(?:complet\w*|successful|processed|paid)\b|""" +
            """لم\s+(?:تتم|يتم|تكتمل|يكتمل|تنجح|ينجح)(?:\s+(?:العملية|عملية|الشراء|الدفع|السداد))?""",
        options,
    )

    /** A credit-limit cause alone does not certify a credited movement. */
    private val creditLimit = Regex("""\bcredit\s+limit\b""", options)

    /** Real movement vocabulary on any line blocks the notice classification. */
    private val separateMoney = Regex(
        """\b(?:fee|charge[sd]?|debit(?:ed)?|credit(?:ed)?|deduct(?:ed)?|refund\w*|revers\w*|posted|completed|successful|paid)\b|""" +
            """رسوم|استرداد|عكس\s+العملية|تم\s+(?:خصم|سداد|دفع|إيداع|ايداع)""",
        options,
    )

    private val merchantField = Regex("""(?:^|\s)(?:merchant|at|التاجر|المتجر|لدى)\s*[:：]""", options)

    // A single explicit refusal notification paired with one purchase-kind field.
    // Amount/card/balance fields are not completion evidence or amounts to post.
    private val notificationField = Regex("""^\s*Notification\s*:""", options + RegexOption.MULTILINE)
    private val transactionField = Regex("""^\s*Transaction\s*:""", options + RegexOption.MULTILINE)
    private val refusedNotification = Regex(
        """^\s*Notification\s*:\s*Declined\s+due\s+to\s+Insufficient\s+balance\.?\s*$""",
        options + RegexOption.MULTILINE,
    )
    private val purchaseKind = Regex(
        """^\s*Transaction\s*:\s*(?:شراء(?:\s+عبر\s+\S+)?|(?:online\s+)?purchase|payment)\s*$""",
        options + RegexOption.MULTILINE,
    )

    /** Sentence boundaries, but never the decimal point inside an amount. */
    private val clauseBreak = Regex("""(?<!\d)[.](?!\d)|[!?؟؛;]""")

    fun matches(body: String): Boolean {
        if (body.isBlank()) return false
        val normalized = Normalize.digits(body)
        val unquoted = quotedSegment.replace(normalized, " ")
        val hasProseRefusal = unquoted.lineSequence()
            .map { line -> merchantField.find(line)?.let { line.take(it.range.first) } ?: line }
            .flatMap { line -> clauseBreak.split(line).asSequence() }
            .filterNot { conditionalClause.containsMatchIn(it) }
            .any { clause -> positives.any { it.containsMatchIn(clause) } }
        val hasPurchaseRefusalHeader = notificationField.findAll(unquoted).count() == 1 &&
            transactionField.findAll(unquoted).count() == 1 &&
            refusedNotification.containsMatchIn(unquoted) && purchaseKind.containsMatchIn(unquoted)
        if (!hasProseRefusal && !hasPurchaseRefusalHeader) return false
        val moneyScan = negatedCompletionText.replace(normalized, " ")
        moneyScan.lineSequence().forEach { line ->
            if (separateMoney.containsMatchIn(creditLimit.replace(line, ""))) return false
            if (BankPostedLanguage.evidence(line).support == PostingSupport.EXPLICIT_POSTED) return false
        }
        return true
    }
}
