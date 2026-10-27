package com.athar.ingestion.smsparser

import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Body evidence, not category or parser confidence. Contradictions always win. */
object BankPostedLanguage {
    private val declined = pattern(
        """\b(?:declined|rejected|unsuccessful|failed|not\s+(?:(?:be|been|successfully|yet)\s+)*(?:completed|successful|processed|paid))\b|مرفوض|فشل|لم\s+(?:يتم|تتم|تنجح|يكتمل)|غير\s+(?:ناجح|مكتمل|منفذ)""",
    )
    private val released = pattern("""\b(?:released|reversed|cancelled|canceled|refunded|refund)\b|استرداد|استرجاع|إرجاع|ارجاع|إلغاء|الغاء|عكس\s+العملية""")
    private val scheduled = pattern("""سيتم\s+(?:خصم|شراء|سداد|دفع|تحويل)|سيخصم|\b(?:scheduled|will\s+be\s+(?:deducted|charged|paid|debited|credited|transferred))\b""")
    private val authorized = pattern("""معلق|محجوز|حجز\s+مؤقت|تفويض|قيد\s+(?:التنفيذ|المعالجة)|\b(?:pending|hold|held|pre[- ]?auth\w*|authoriz\w*|reserved)\b|\*\s*PEND(?:ING)?\b""")
    private val instruction = pattern("""\b(?:to\s+purchase|confirm\s+(?:your\s+)?purchase|verification\s+code|one[- ]time\s+(?:password|code)|otp)\b|رمز\s+(?:التحقق|التأكيد)|لتأكيد\s+(?:الشراء|عملية)""")
    // A bank's transaction heading or completed verb, never a merchant called PAID.
    private val completed = pattern(
        """^\s*(?:شراء(?:\s|$)|خصم\s+(?:بمبلغ|مبلغ|بقيمة)|حوالة\s+واردة|إيداع\s+(?:بمبلغ|مبلغ)|ايداع\s+(?:بمبلغ|مبلغ)|(?:online\s+|pos\s+|international\s+|local\s+)?purchase\b|purchased\b|(?:successful|completed)\s+(?:purchase|payment|transaction|transfer)\b|(?:payment|transaction)\s+(?:completed|successful)\b)|(?<![\p{L}\p{N}_])تم(?:ت)?\s+(?:عملية\s+)?(?:شراء|خصم|دفع|سداد|إيداع|ايداع|إضافة|اضافة)|\b(?:was|has\s+been)\s+(?:debited|credited|deposited|paid)\b""",
    )

    fun evidence(body: String): PostingEvidence = evidence(body, validatedCardTemplate = false)

    /** Only the registry supplies context after a real bank-specific template succeeds. */
    internal fun evidence(body: String, validatedCardTemplate: Boolean): PostingEvidence = when {
        declined.containsMatchIn(body) -> uncertain(PostingState.DECLINED)
        released.containsMatchIn(body) -> uncertain(PostingState.RELEASED)
        scheduled.containsMatchIn(body) -> uncertain(PostingState.SCHEDULED)
        authorized.containsMatchIn(body) -> uncertain(PostingState.AUTHORIZED)
        instruction.containsMatchIn(body) -> uncertain(PostingState.NOTICE)
        completed.containsMatchIn(body) || validatedCardTemplate -> PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED)
        else -> uncertain(PostingState.POSTED)
    }

    private fun uncertain(state: PostingState) = PostingEvidence(state, PostingSupport.HEURISTIC_ONLY)
    private fun pattern(value: String) = Regex(value, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
}

/** Uses only body tokens through the established date extractor, never receive fallback. */
fun parseSmsStatedDate(body: String, receivedAt: Instant): LocalDate? =
    parseSmsDate(body, receivedAt)?.toLocalDateTime(TimeZone.currentSystemDefault())?.date
