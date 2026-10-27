package com.athar.ingestion.smsparser.alrajhi

import com.athar.ingestion.smsparser.Normalize

/**
 * WM038: template-context gate for explicit posted evidence on the Al Rajhi real
 * PoS card-purchase template (WM031 Barq pattern: registry-supplied
 * `validatedCardTemplate`).
 *
 * The heading `PoS International purchase` is the exact bank heading of the
 * already-validated `al-rajhi-pos-purchase-real` template's international shape. It
 * never satisfies the generic completed-verb language in
 * [com.athar.ingestion.smsparser.BankPostedLanguage] — the `pos\s+purchase` prefix
 * does not span the two-word heading — so without template context these posted
 * purchases stay HEURISTIC_ONLY.
 *
 * Scope is deliberately narrower than the template's own header (which also matches
 * the domestic `PoS purchase` heading; that one already carries public completed-verb
 * evidence and is untouched). Only the exact international heading grants context,
 * and [com.athar.ingestion.smsparser.BankPostedLanguage] conservative precedence is
 * unchanged: declined / released / scheduled / authorized (incl. literal `*PEND`,
 * `* PEND`, `*PENDING`) / instruction evidence always wins over this upgrade.
 */
internal object AlRajhiPostedEvidence {

    private val posInternationalHeading = Regex(
        """^PoS\s+International\s+purchase\s*$""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )

    /**
     * True only when the body — normalized exactly like the template normalizes it —
     * carries the template's `PoS International purchase` heading. Never derives from
     * category, confidence, money or date extraction.
     */
    fun validatesPosInternationalPurchaseContext(body: String): Boolean =
        posInternationalHeading.containsMatchIn(Normalize.digits(body))
}
