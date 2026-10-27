package com.athar.ingestion.smsparser

/** Administrative numbers must not enter the generic amount fallback as money. */
internal object PaymentServiceNotices {
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    private val statementHeader = Regex("""^\s*Credit\s+Card\s*:[^\r\n]*\bStatement\b[^\r\n]*""", RegexOption.IGNORE_CASE)
    private val totalDue = Regex("""^\s*Total\s+amount\s+due\s*:""", options)
    private val minimumDue = Regex("""^\s*Minimum\s+amount\s+due\s*:""", options)
    private val dueDate = Regex("""^\s*Due\s+date\s*:""", options)
    private val receipt = Regex("""^\s*(?:Online\s+Purchases?|PoS(?:\s+International)?\s+purchase|Payment\s+(?:of|received)|Credit\s+Card\s+Payment)\b""", options)
    private val codeHeader = Regex("""^\s*(?:Please\b[^\r\n]{0,80}\bcode|One[\s-]+time[\s-]+password\s+code)\s*:\s*\d{3,8}\b""", RegexOption.IGNORE_CASE)
    private val oneTimePassword = Regex("""\bone[\s-]+time[\s-]+password\b""", RegexOption.IGNORE_CASE)

    fun matches(body: String): Boolean {
        if (codeHeader.containsMatchIn(body) && oneTimePassword.containsMatchIn(body)) return true
        return statementHeader.containsMatchIn(body) && totalDue.containsMatchIn(body) &&
            minimumDue.containsMatchIn(body) && dueDate.containsMatchIn(body) &&
            !receipt.containsMatchIn(body)
    }
}
