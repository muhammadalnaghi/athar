package com.athar.ingestion.smsparser

import com.athar.core.domain.model.RawIngestEvent
import kotlinx.datetime.Instant

/**
 * Cross-bank ignore matchers — OTPs, beneficiary administration, marketing, password
 * resets, system maintenance. These messages *come from* known bank senders but are
 * not transactions; the parser must short-circuit them as [ParseResult.Ignored] so
 * they don't appear as "Failed" in the audit log and don't generate pending entries.
 *
 * Order: this template should be tried FIRST in the registry for every bank. Any
 * downstream parse-attempt for an ignorable body is wasted work.
 */
class GlobalBankIgnoreTemplate : BankTemplate {
    override val id: String = "global-bank-ignore"
    override val senderMatcher: SenderMatcher = SenderMatcher.AnyOf(KnownBankSenders.builtIn)

    private val ignorePatterns: List<Regex> = listOf(
        // OTP codes — many shapes
        Regex("""\b\d{3,6}\s+is\s+your\s+OTP""", RegexOption.IGNORE_CASE),
        Regex("""\bOTP\s*:\s*\d{3,6}""", RegexOption.IGNORE_CASE),
        Regex("""\bOTP\s+Code\s*:\s*\d{3,6}""", RegexOption.IGNORE_CASE),
        Regex("""\bone\s+time\s+password\b""", RegexOption.IGNORE_CASE),
        Regex("""\bverification\s+code\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdo\s+not\s+share\s+(?:the\s+)?(?:code|otp)""", RegexOption.IGNORE_CASE),
        Regex("""رمز\s+(?:التحقق|التفعيل|الدخول|OTP)"""),
        Regex("""رمز\s*:\s*\d{3,6}"""),
        Regex("""لا\s+تشارك\s+رمز"""),
        Regex("""لا\s+تشاركه"""),
        Regex("""كلمة\s+مرور\s+مؤقتة"""),
        Regex("""كلمة\s+مرور\s+صالحة\s+لمرة\s+واحدة"""),
        Regex("""رمز\s+مؤقت"""),

        // Beneficiary management
        Regex("""\bBeneficiary\s*:\s*[^\n\r]+?\s+has\s+been\s+(?:added|activated|deleted|removed)""", RegexOption.IGNORE_CASE),
        // Match both "beneficiary" (correct) and corpus misspellings like "benefeciary".
        Regex("""\bNew\s+bene[a-z]*[ec]iary\s+activated\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbenefi?[ec]iary\s+activated\b""", RegexOption.IGNORE_CASE),
        Regex("""\bBeneficiary\s+activated\b""", RegexOption.IGNORE_CASE),
        Regex("""تمت\s+إضافة\s+مستفيد"""),
        Regex("""تم\s+تفعيل\s+المستفيد"""),
        Regex("""تمت?\s+ا(?:ضافة|ضافه)\s+المستفيد"""),
        Regex("""تم\s+تنشيط\s+المستفيد"""),
        Regex("""تم\s+تنشيط\s+مستفيد\s*:"""),
        Regex("""تم\s+إ?ضافة\s+مستفيد\s*[-–]"""),
        Regex("""تم\s+تنشيط\s+مستفيد\s*[-–]"""),
        Regex("""اسم\s+المستفيد[\s\S]{0,180}(?:حالة\s*:\s*غير\s+نشط|حالة\s*:\s*نشط|الحالة\s*:\s*تم\s+التنشيط|تم\s+التنشيط)"""),

        // Card management (activation, digital wallet provisioning, renewal)
        Regex("""\bcard\s+(?:has\s+been\s+)?added\s+to\s+(?:digital\s+wallet|wallet)""", RegexOption.IGNORE_CASE),
        Regex("""\bcomplete\s+the\s+card\s+activation""", RegexOption.IGNORE_CASE),
        Regex("""\bThank\s+you\s+for\s+activating\s+your\s+card""", RegexOption.IGNORE_CASE),
        Regex("""\bcard\s+renewal\b""", RegexOption.IGNORE_CASE),
        Regex("""(?:apple|mada)\s*pay[^\n\r]{0,120}(?:following\s+your\s+request|الموافقة|بناءً\s+على\s+طلبك)""", RegexOption.IGNORE_CASE),
        Regex("""تطبيق\s*(?:apple|mada)\s*pay[^\n\r]{0,120}طلبك""", RegexOption.IGNORE_CASE),
        Regex("""الموافقة\s+على\s+طلبكم[^\n\r]{0,80}منتج\s+بطاقة\s+الائتمان"""),
        Regex("""سندات\s+الأمر[^\n\r]{0,120}البطاقات\s+الائتمانية"""),
        Regex("""بطاقة\s+ائتمانية[\s\S]{0,240}(?:إجمالي\s+المبلغ\s+المستحق|المبلغ\s+الأدنى\s+المستحق)"""),
        Regex("""بطاقة\s+(?:ائتمانية|إئتمانية)[\s\S]{0,240}(?:كشف\s+حساب|المبلغ\s+المستحق|المبلغ\s+الأدنى\s+المستحق)"""),
        Regex("""كشف\s+حسابكم\s+الشهري"""),
        Regex("""تحديث\s+شروط\s+استبدال\s+البطاقة"""),
        Regex("""البطاقة\s+البلاستيكية\s+التالفة\s+أو\s+المفقودة"""),
        Regex("""(?:الشروط\s+والأحكام|الشروط)[\s\S]{0,160}(?:يرجى\s+زيارة|موقع\s+مصرف|موقع\s+البنك)"""),
        Regex("""الانتهاء[\s\S]{0,160}تجديد\s+البطاقة"""),
        Regex("""عملية\s+مرفوضة[^\n\r]{0,80}بطاقتك\s+غير\s+مفعلة"""),
        Regex("""عملية\s+مرفوضة[\s\S]{0,120}البطاقة\s+غير\s+مفعلة"""),
        Regex("""تم\s+ربط\s+رقم\s+الجوال[\s\S]{0,120}بالبطاقة[\s\S]{0,80}بنجاح"""),
        Regex("""تم\s+اصدار\s+بطاقتك\s+الرقمية[\s\S]{0,100}بنجاح"""),
        Regex("""تم\s+تحديث\s+شروط\s+البطاقات\s+الائتمانية\s+ومسبقة\s+الدفع"""),

        // System / scheduled maintenance / service announcements
        Regex("""\bscheduled\s+(?:update|maintenance)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbanking\s+services\s+will\s+be\s+temporarily\s+unavailable""", RegexOption.IGNORE_CASE),
        Regex("""\bsystem\s+maintenance\b""", RegexOption.IGNORE_CASE),
        Regex("""يرجى\s+العلم\s+بأنه\s+سيتم"""),
        Regex("""تحديث\s+الأنظمة|تحديث\s+انظمتنا|تحديثات?\s+على\s+انظمتنا"""),
        Regex("""(?:الخدمات|القنوات)\s+الرقمية[^\n\r]{0,80}خارج\s+الخدمة"""),
        Regex("""خارج\s+الخدمة\s+مؤقت"""),
        Regex("""خدمات\s+بطاقة\s+مدى[^\n\r]{0,80}خارج\s+الخدمة"""),

        // Marketing / promotional offers — derived from real Saudi banking promo corpus
        // (see docs/all-senders-analysis.md). The shape is always "Earn X / Win Y /
        // Bonus Z / Cashback up to N" — an offer, not a confirmation.
        Regex("""\bfinancing\s+is\s+ready""", RegexOption.IGNORE_CASE),
        Regex("""\bno\s+salary\s+transfer\s+required""", RegexOption.IGNORE_CASE),
        Regex("""\binstant\s+approval\b""", RegexOption.IGNORE_CASE),
        Regex("""\bspecial\s+offer\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcashback\s+up\s+to\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:\d+\s*%\s*)?cashback\b""", RegexOption.IGNORE_CASE),
        // "Earn X SAR/points/Shukrans" — offer language.
        Regex("""\b(?:Earn|Win|Get|Claim)\s+(?:up\s+to\s+)?(?:SAR|﷼)?\s*[\d,]+\s+(?:cashback|points|Shukrans|bonus|reward)""", RegexOption.IGNORE_CASE),
        Regex("""\bEarn\s+(?:up\s+to\s+)?(?:SAR|﷼)\s*[\d,]+""", RegexOption.IGNORE_CASE),
        Regex("""\bWin\s+(?:an?\s+)?(?:iPhone|PlayStation|car|prize)""", RegexOption.IGNORE_CASE),
        // Loyalty programs (Al Rajhi mokafaa, NCB Shukrans)
        Regex("""\bextra\s+(?:\d+%?|points|mokafaa|Shukrans|reward)""", RegexOption.IGNORE_CASE),
        Regex("""\b\d+\s*X\s+(?:points|Shukrans|reward)""", RegexOption.IGNORE_CASE),
        Regex("""\bBuy\s+\d+\s+Get\s+\d+\b""", RegexOption.IGNORE_CASE),
        Regex("""\bBOGO\b"""),
        Regex("""\bredeem\s+your\b""", RegexOption.IGNORE_CASE),
        // Instalment / payment-spreading offers
        Regex("""\bTasaheal\b""", RegexOption.IGNORE_CASE),
        Regex("""\bPay.*over\s+\d+\s+months\b""", RegexOption.IGNORE_CASE),
        Regex("""\binstallments?\s+plan\b""", RegexOption.IGNORE_CASE),
        // Contest / prize draws
        Regex("""\bfor\s+(?:a\s+)?chance\s+to\s+win\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdraw\s+prize\b""", RegexOption.IGNORE_CASE),
        Regex("""\blucky\s+draw\b""", RegexOption.IGNORE_CASE),
        Regex("""\bregister\s+(?:in|at|now)\b""", RegexOption.IGNORE_CASE),
        // Arabic marketing copy
        Regex("""عرض\s+ترويجي|عرض\s+خاص|تمويل\s+شخصي|كاش\s*باك"""),
        Regex("""جوائز|جائزة|مسابقة|اربح|اكسب"""),
        Regex("""فرصة\s+الفوز|سجّل\s+الآن|سجل\s+الآن"""),
        Regex("""نقاط\s+مكافأة|نقطة\s+مكافأة"""),
        Regex("""برنامج\s+مكافآتي[^\n\r]{0,80}نقط[^\n\r]{0,80}سينتهي"""),
        Regex("""إجمالي\s+رصيد\s+نقاطك[\s\S]{0,80}برنامج\s+مكافآتي"""),
        Regex("""تطبق\s+الشروط"""),
        Regex("""تقسيط|التقسيط"""),
        Regex("""موافقة\s+فورية"""),
        Regex("""خصومات?\s+(?:تصل|كبيرة)"""),
        Regex("""مبروك[^\n\r]{0,80}حساب\s+سنابل[^\n\r]{0,80}(?:ربح|أرباح)"""),
        Regex("""افتح\s+التطبيق[^\n\r]{0,80}أرباحك"""),
        Regex("""الرمز\s+الترويجي"""),
        Regex("""استمتع\s+بعرضك"""),
        Regex("""احذر\s+المحتالين|ينتحلون\s+هوية"""),
        Regex("""بطاقة\s+هدية"""),
        Regex("""بطاقات\s+إهداء"""),
        Regex("""فرصة\s+استثمارية"""),
        Regex("""رمز\s+الريال\s+السعودي"""),
        Regex("""نطلق\s+اليوم\s+هويتنا\s+الجديدة"""),
        Regex("""هنا\s+تنمو\s+الثروات"""),

        // Password / login / device alerts (not a transaction; sometimes contains amounts as limits)
        Regex("""\bpassword\s+(?:reset|change)""", RegexOption.IGNORE_CASE),
        Regex("""\blogin\s+from\s+(?:a\s+)?(?:new\s+)?device""", RegexOption.IGNORE_CASE),
        Regex("""\bsuccessful\s+login\b""", RegexOption.IGNORE_CASE),
        Regex("""تم\s+(?:إلغاء\s+)?ربط\s+جهاز"""),
        Regex("""تم\s+إنشاء\s+(?:كلمة\s+المرور|حساب)"""),
        Regex("""تم\s+تحديث\s+نموذج\s+معلومات\s+العميل"""),
        Regex("""تم\s+تسجيل\s+جهاز\s+جديد"""),
        Regex("""تم\s+تسجيل\s+الدخول[^\n\r]{0,80}جهاز\s+جديد"""),
        Regex("""تم\s+تفعيل\s+خدمة\s+الدخول\s+السريع"""),
        Regex("""اسم\s+المستخدم\s+الخاص\s+بك\s+للخدمات\s+الالكترونية"""),
        Regex("""تم\s+تغيير\s+حد\s+التحويل\s+اليومي"""),
        Regex("""تم\s+تغيير\s+الحد\s+اليومي\s+للعمليات\s+بنجاح"""),
        Regex("""تم\s+التسجيل\s+في\s+خاصية\s+(?:البصمة|الدخول\s+السريع)"""),
        Regex("""تم\s+إلغاء\s+خاصية\s+البصمة"""),
        Regex("""تفعيل\s+خدمة\s+الدخول[^\n\r]{0,80}بصمة"""),
        Regex("""تم\s+تنشيط\s+حسابك\s+الادخاري"""),
        Regex("""رقم\s+الآيبان\s+الخاص\s+بك"""),
        Regex("""نعتذر\s+عن\s+الخلل[^\n\r]{0,120}استخدام\s+البطاقة"""),
        Regex("""يمكنك\s+استخدام\s+بطاقتك\s+مجدد"""),

        // Account request acknowledgement
        Regex("""تم\s+تسجيل\s+طلبكم"""),
        Regex("""\brequest\s+(?:received|registered|number)\b""", RegexOption.IGNORE_CASE),
        Regex("""تحديث\s+قائمة\s+رسوم\s+التعرفة\s+البنكية"""),
        Regex("""رسوم\s+التعرفة\s+البنكية"""),
        Regex("""تعرفة\s+المنتجات\s+البنكية"""),

        // Non-transaction bank notices observed in real family-device exports.
        Regex("""تحديث\s+الشروط\s+والأحكام"""),
        Regex("""تم\s+تغيير\s+شريحة\s+الحساب"""),
        Regex("""سيتم\s+تحديث\s+تعرفة"""),
        Regex("""تحديثات?\s+على\s+انظمتنا\s+البنكية"""),
        Regex("""قنواتنا\s+الرقمية\s+خارج\s+الخدمة"""),
        Regex("""ستنتقل\s+جميع\s+خدمات\s+stc\s*pay""", RegexOption.IGNORE_CASE),
        Regex("""كل\s+خدماتك\s+انتقلت\s+لتطبيق\s+STC\s+Bank""", RegexOption.IGNORE_CASE),
        Regex("""إيقاف\s+خدمات\s+تطبيق\s+stc\s*pay""", RegexOption.IGNORE_CASE),
        Regex("""حمّل\s+تطبيق\s+STC\s+Bank""", RegexOption.IGNORE_CASE),
        Regex("""تحويل\s+جميع\s+الخدمات\s+المصرفية\s+الى\s+تطبيق"""),
        Regex("""حمّل\s+التطبيق\s+الآن"""),
        Regex("""\bview\s+and\s+download\s+your\s+document\b""", RegexOption.IGNORE_CASE),
        Regex("""\bBank\s+Documents\s+menu\b""", RegexOption.IGNORE_CASE),
        Regex("""المكالمات\s+التي\s+تدعي\s+أنها\s+جهة\s+رسمية"""),
    )

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        when (NumericServiceNotices049.classify(body)) {
            NumericServiceNotices049.Disposition.NON_FINANCIAL -> return ParseResult.Ignored
            NumericServiceNotices049.Disposition.MIXED_FINANCIAL ->
                return ParseResult.Failed("service wording with financial evidence", listOf(id))
            NumericServiceNotices049.Disposition.NOT_NOTICE -> Unit
        }
        return if (ignorePatterns.any { it.containsMatchIn(body) } || AmountlessServiceNotices.matches(body) || PaymentServiceNotices.matches(body) || SingleDeclinedNotice053.matches(body)) {
            ParseResult.Ignored
        } else {
            ParseResult.Failed("not an ignorable message", listOf(id))
        }
    }
}

/**
 * Belt-and-braces guard: returns [ParseResult.Ignored] for any sender that isn't in
 * the [KnownBankSenders] allow-list. Placing this first in the registry guarantees
 * promotional senders with "Earn SAR 10,000 today!" never go further.
 *
 * The TemplateBasedSmsParser already returns Ignored when no template's senderMatcher
 * matches, but this template makes the intent visible and adds belt-and-braces against
 * a future template accidentally declaring `SenderMatcher.Regex(".+")` again.
 */
class UnknownSenderIgnoreTemplate : BankTemplate {
    override val id: String = "unknown-sender-ignore"

    // Match everything; the registry calls every template's matcher, but tryParse
    // short-circuits to Ignored if the sender we recorded on the event wasn't in
    // the known set. We use a Regex(".+") matcher here precisely because we WANT
    // to see every event and explicitly Ignore unknown ones.
    override val senderMatcher: SenderMatcher = SenderMatcher.Regex(Regex(".+"))

    override fun tryParse(body: String, receivedAt: Instant): ParseResult {
        // tryParse doesn't receive sender directly — this template stays in the chain
        // but acts as a no-op when the registry already filtered to known senders.
        // The real protection is upstream: the parser uses [filterToKnownSenders] (below)
        // to drop unknown-sender events before the template loop runs.
        return ParseResult.Failed("placeholder — sender filtering happens in the parser", listOf(id))
    }
}

/**
 * Pure-Kotlin helper for the parser pipeline: returns true iff the SMS came from a
 * sender Athar treats as a bank/wallet. Use this at the registry boundary to drop
 * promotional shortcodes before any template runs.
 */
fun isFromKnownBank(event: RawIngestEvent): Boolean = KnownBankSenders.isKnown(event.sender)
