package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pins parsing of REAL message shapes from the user's 1,000+ message export
 * (docs/sms-corpus-analysis.md). One test per shape; samples are anonymized but
 * structurally identical to the corpus.
 */
class SmsCorpusTest {

    private val at = Instant.parse("2026-01-01T12:00:00Z")

    private fun parser() = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())

    private fun event(sender: String, body: String) = RawIngestEvent(
        id = "x", source = IngestSource.SMS, sender = sender, body = body, receivedAt = at, rawId = "1",
    )

    // ─── AlRajhi ──────────────────────────────────────────────────────────

    @Test fun `AlRajhi Online Purchase SAR`() {
        val body = """
            Online Purchase
            By:5916 ;Visa
            Amount:56.35 SAR
            At:Amazon SA
            Balance:2270.94 SAR
            Date:27-12-2025 23:04
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("56.35"))
        assertThat(r.merchant).isEqualTo("Amazon SA")
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
    }

    @Test fun `AlRajhi Online Purchase multi-currency captures SAR-in-parens`() {
        val body = """
            Online Purchase
            Card:5916 ;Visa
            Amount:16.25USD(60.96 SAR)
            At: HARVARD B
            Fee &VAT: 1.40 SAR
            Exchange rate~ 3.751385
            Total due amount:62.36 SAR
            Country:USA
            Balance:2973.79 SAR
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        // Preserve this inherited case identity. The booked expense includes the stated fee once;
        // 60.96 is settlement principal, while the source explicitly states total due of 62.36.
        assertThat(r.amount.amount).isEqualTo(BigDecimal("60.96").add(BigDecimal("1.40")))
        assertThat(r.amount.currency).isEqualTo("SAR")
        assertThat(r.amount.amount.subtract(BigDecimal("60.96"))).isEqualTo(BigDecimal("1.40"))
        assertThat(r.merchant).isEqualTo("HARVARD B")
    }

    @Test fun `AlRajhi PoS purchase`() {
        val body = """
            PoS purchase
            Card:5916 ;Visa-Samsung Pay
            At: Hamad Alm
            Amount:249 SAR
            Balance: 1922.81 SAR
            Date:31-12-2025 20:43
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("249"))
        assertThat(r.merchant).isEqualTo("Hamad Alm")
    }

    @Test fun `AlRajhi Reverse Transaction = INCOME refund`() {
        val body = """
            Reverse Transaction
            By:5916;Visa-Samsung Pay
            Amount:SAR 40
            At:ROBA KABA
            Balance:SAR 3383.01
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("40"))
        assertThat(r.merchant).isEqualTo("ROBA KABA")
    }

    @Test fun `AlRajhi Debit Internal Transfer captures recipient name not account number`() {
        val body = """
            Debit Internal Transfer
            From:0930
            Amount:SAR 3000
            To:HAMED ALGHAMDI
            To:4186
            Date:25-12-27 20:10
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("3000"))
        assertThat(r.counterparty).isEqualTo("HAMED ALGHAMDI")
        assertThat(r.type).isEqualTo(TxType.TRANSFER)
    }

    @Test fun `AlRajhi Debit Transfer Local`() {
        val body = """
            Debit Transfer Local
            Bank:SNB
            From:0930
            Amount:SAR 1400
            To:عبدالرحمن محمد عبدالله الغامدي
            To:5308
            26-1-6 10:53
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("1400"))
        assertThat(r.counterparty).contains("عبدالرحمن")
    }

    @Test fun `AlRajhi Credit Transfer Local salary inbound`() {
        val body = """
            Credit Transfer Local
            Via:NATIONAL COMMERCIAL BANK, THE
            Amount:SAR 23597.21
            To:0930
            From:شركه علم شركة مساهمة سعودية
            From:0203
            26/1/25 09:04
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("23597.21"))
        assertThat(r.counterparty).contains("شركه علم")
    }

    @Test fun `AlRajhi Bill Payment captures service as merchant`() {
        val body = """
            Bill Payment
            From:4268
            Amount:SAR 443
            Biller:002
            Service:SAUDI ELECTRIC COMPANY
            Bill:30017473123
            26-1-6 19:46
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("443"))
        assertThat(r.merchant).isEqualTo("SAUDI ELECTRIC COMPANY")
    }

    @Test fun `AlRajhi Arabic bill payment without biller keeps bill payment merchant`() {
        val body = """
            سداد فاتورة
            بطاقة:1234 ;فيزا
            مبلغ:SAR 250.00
            رصيد:SAR 12000.00
            في:26-1-1 10:10
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("250.00"))
        assertThat(r.merchant).isEqualTo("سداد فاتورة")
        assertThat(r.templateId).isEqualTo("al-rajhi-generic-amount")
    }

    @Test fun `AlRajhi Arabic electronic payment headline becomes merchant`() {
        val body = """
            اليكترون
            مبلغ:SAR 75.00
            إلى:123456
            في:16-01-26 20:27
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("75.00"))
        assertThat(r.merchant).isEqualTo("اليكترون")
        assertThat(r.templateId).isEqualTo("al-rajhi-generic-amount")
    }

    @Test fun `AlRajhi Arabic Interior Ministry traffic payment headline becomes merchant`() {
        val body = """
            مدفوعات وزارة الداخلية-المخالفات المرورية
            من:1234
            مبلغ:SAR 300
            رقم الفاتورة:1234567890
            16/1/26 20:27
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("300"))
        assertThat(r.merchant).isEqualTo("مدفوعات وزارة الداخلية-المخالفات المرورية")
        assertThat(r.templateId).isEqualTo("al-rajhi-generic-amount")
    }

    @Test fun `AlRajhi Deposit Saving Monthly Profit = INCOME`() {
        val body = """
            Deposit:Saving Account Monthly Profit
            Amount:SAR 92.80
            To:4268
            Date:26-1-1 04:32
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("92.80"))
    }

    @Test fun `AlRajhi Loan Instalment`() {
        val body = """
            Debit: Loan Instalment
            Instalment: SAR 7166.67
            From: 0930
            Remaining Amount: SAR 358333.50
            25/1/26 20:26
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("7166.67"))
        assertThat(r.merchant).isEqualTo("Loan Instalment")
    }

    @Test fun `AlRajhi Declined Transaction is Ignored`() {
        val body = """
            Notification : Declined due to insufficient fund
            Transaction : Online Purchase
            Card: 2909
            Amount : SAR 78.35
            Merchant : Amazon SA
            Date : 03-01-2026 01:59
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body))
        assertThat(r).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `AlRajhi OTP message is Ignored`() {
        val body = """
            OTP Code:2232
            Reason:Rajhi Transfer - Mobile App
            Amount:3,000.00 SAR
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body))
        assertThat(r).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `AlRajhi Arabic labeled purchase captures merchant`() {
        val body = """
            شراء عبر نقاط البيع
            بطاقة:1234
            لدى: STARBUCKS RIYADH
            مبلغ:56.35 SAR
            رصيد:2270.94 SAR
            في:27/12/25 23:04
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("56.35"))
        assertThat(r.merchant).isEqualTo("STARBUCKS RIYADH")
        assertThat(r.templateId).isEqualTo("al-rajhi-pos-purchase")
    }

    @Test fun `AlRajhi Arabic compact purchase captures lam merchant`() {
        val body = """
            شراء
            عبر:1234;مدى-أثير
            بـSAR 20
            لـSTC Pay
            26/1/1 10:10
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("20"))
        assertThat(r.merchant).isEqualTo("STC Pay")
        assertThat(r.templateId).isEqualTo("al-rajhi-pos-purchase")
    }

    @Test fun `AlRajhi Arabic online purchase prefers explicit merchant over account source`() {
        val body = """
            شراء انترنت
            بطاقة:1234;مدى
            من:4186
            مبلغ:SAR 49.45
            لدى:Tawuniya
            في:25-2-13 09:41
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("49.45"))
        assertThat(r.merchant).isEqualTo("Tawuniya")
        assertThat(r.templateId).isEqualTo("al-rajhi-pos-purchase")
    }

    @Test fun `AlRajhi Arabic credit card settlement is transfer`() {
        val body = """
            بطاقة ائتمانية:سداد
            بطاقة:1234 ;فيزا
            مبلغ:SAR 1500
            رصيد:SAR 12000.00
            في:26-1-1 10:10
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.TRANSFER)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("1500"))
        assertThat(r.templateId).isEqualTo("al-rajhi-credit-card-payment")
    }

    @Test fun `AlRajhi Arabic compact card settlement is transfer`() {
        val body = """
            بطاقة فيزا:سداد بـSR 100
            عبر:فيزا;1234
            رصيد:12000.0 SR
            26/1/1 10:10
        """.trimIndent()
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.TRANSFER)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("100"))
        assertThat(r.templateId).isEqualTo("al-rajhi-credit-card-payment")
    }

    @Test fun `AlRajhi Arabic salary and transfer confirmations keep non-expense type`() {
        val salary = """
            راتب
            مبلغ:SAR 12000.00
            الى:1234
            في:26-1-1 10:10
        """.trimIndent()
        val salaryResult = parser().parse(event("AlRajhiBank", salary)) as ParseResult.Success
        assertThat(salaryResult.type).isEqualTo(TxType.INCOME)
        assertThat(salaryResult.templateId).isEqualTo("al-rajhi-generic-amount")

        val transfer = """
            عزيزي العميل تم تعميد حوالتكم
            مبلغ: SAR 2500
            في: 2026-01-01 10:10
            مرجع: ABC123456
        """.trimIndent()
        val transferResult = parser().parse(event("AlRajhiBank", transfer)) as ParseResult.Success
        assertThat(transferResult.type).isEqualTo(TxType.TRANSFER)
        assertThat(transferResult.templateId).isEqualTo("al-rajhi-generic-amount")
    }

    @Test fun `AlRajhi Arabic temporary code with amount is Ignored`() {
        val body = """
            رمز مؤقت
            لـ: عملية دفع
            المبلغ: 100 SAR
        """.trimIndent()
        assertThat(parser().parse(event("AlRajhiBank", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `AlRajhi Arabic card statement notice is Ignored`() {
        val body = """
            بطاقة ائتمانية
            البطاقة:1234
            إجمالي المبلغ المستحق: 2500 SAR
            المبلغ الأدنى المستحق: 125 SAR
            كما يمكنك سداد مستحقات البطاقة عبر التطبيق.
        """.trimIndent()
        assertThat(parser().parse(event("AlRajhiBank", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `digital wallet provisioning and loyalty expiry notices are Ignored`() {
        assertThat(
            parser().parse(event("AlRajhiBank", "Apple Pay , الرجاء الموافقة على الطلب من خلال التطبيق")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("AlRajhiBank", "برنامج مكافآتي 2486 نقطة سينتهي خلال 30 يوم")),
        ).isEqualTo(ParseResult.Ignored)
    }

    // ─── STC Bank ──────────────────────────────────────────────────────────

    @Test fun `STC Bank Internal incoming transfer`() {
        val body = """
            Internal incoming transfer
            Amount:218.00SAR
            From:AWS ALGHAMDI
            Acc:0847*
            At:22/11/25 01:16
        """.trimIndent()
        val r = parser().parse(event("STC Bank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("218.00"))
        assertThat(r.counterparty).isEqualTo("AWS ALGHAMDI")
        assertThat(r.type).isEqualTo(TxType.INCOME)
    }

    @Test fun `STC Bank Internal outward transfer`() {
        val body = """
            Internal outward transfer
            Amount:137.27SAR
            To:ABDULAZIZ ALFAYYADH
            Acc:0004*
            At:22/11/25 01:18
        """.trimIndent()
        val r = parser().parse(event("STC Bank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("137.27"))
        assertThat(r.counterparty).isEqualTo("ABDULAZIZ ALFAYYADH")
    }

    @Test fun `STC Bank OTP is Ignored`() {
        val body = """
            0530 is your OTP
            For: Add Beneficiary
            *Do not share the code
        """.trimIndent()
        val r = parser().parse(event("STC Bank", body))
        assertThat(r).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `STC Bank Beneficiary added is Ignored`() {
        val r = parser().parse(event("STC Bank", "Beneficiary: عبدالعزيز الفياض has been added."))
        assertThat(r).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `STC Bank Online Purchase`() {
        val body = """
            Online Purchase Transaction Amount 93.10
            From: Nahdi Care Clinic
            Card: *******3279
            Date 19/02/2026
        """.trimIndent()
        val r = parser().parse(event("STC Bank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("93.10"))
        assertThat(r.merchant).isEqualTo("Nahdi Care Clinic")
    }

    // ─── D360 ──────────────────────────────────────────────────────────

    @Test fun `D360 Online Purchase multi-currency CNY`() {
        val body = """
            Online Purchase
            Amount: CNY 1.80 (SAR 0.94)
            Card: *6169 - VISA (Ecommerce)
            At: ALP*HelloBike
            On: 2025-07-11 11:35
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("0.94"))
        assertThat(r.merchant).isEqualTo("ALP*HelloBike")
    }

    @Test fun `D360 International Purchase GBP`() {
        val body = """
            International Purchase
            Amount: GBP 18.39 (SAR 93.22)
            Card: *6169 - VISA (Card)
            At: TESCO STORES 4850
            Country: United Kingdom
            On: 2025-10-03 18:17
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("93.22"))
        assertThat(r.merchant).contains("TESCO STORES")
        assertThat(r.merchant).contains("United Kingdom")
    }

    @Test fun `D360 Local Purchase SAR`() {
        val body = """
            Purchase
            Amount: SAR 25.00
            Card: *6169 - mada (Samsung Pay)
            At: Abdullah Dhafer Ali AlShe
            On: 2026-02-20 17:50
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("25.00"))
        assertThat(r.merchant).isEqualTo("Abdullah Dhafer Ali AlShe")
    }

    @Test fun `D360 Account Funding`() {
        val body = """
            Account Funding Amount: SAR 5000.00
            Card: *5916 - Visa
            To: *0424
            On: 22/08/2025 03:19:04
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("5000.00"))
    }

    @Test fun `D360 Incoming Transfer from another bank`() {
        val body = """
            Incoming Transfer: AlRajhi Bank
            Amount: SAR 6,300.00
            From: WALEED HAMED****
            IBAN: ****0424
            at: 2025-08-22 03:24
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("6300.00"))
        assertThat(r.counterparty).contains("WALEED HAMED")
    }

    @Test fun `D360 Arabic transfer between accounts is transfer`() {
        val body = """
            تحويل بين حساباتك
            من: *1111
            المبلغ: 12,000.00 ريال
            إلى: *2222
            في: 2026-01-16 20:27
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.TRANSFER)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("12000.00"))
        assertThat(r.counterparty).isEqualTo("*2222")
        assertThat(r.templateId).isEqualTo("d360-structured")
    }

    @Test fun `D360 Declined Transaction is Ignored`() {
        val body = """
            Transaction Declined: Insufficient balance
            Amount: GBP 103.29 (SAR 520.21)
            Card: *6169 - Visa
            At: BURGER & LOBSTER
            On: 2025-10-10 14:48:55
        """.trimIndent()
        assertThat(parser().parse(event("D360 Bank", body))).isEqualTo(ParseResult.Ignored)
    }

    // ─── Barq ──────────────────────────────────────────────────────────

    @Test fun `Barq Online Purchase multi-currency captures SAR in parens`() {
        val body = """
            Online Purchases:
            Visa card: **9803
            Amount: 6 CNY (3.14 SAR)
            Wallet Balance: 2496.86
            At: ZUOMENDUNCANYIN
            On: 2025-07-11 03:24
        """.trimIndent()
        val r = parser().parse(event("barq app", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("3.14"))
        assertThat(r.merchant).isEqualTo("ZUOMENDUNCANYIN")
    }

    @Test fun `Barq POS International Purchase EUR`() {
        val body = """
            POS International Purchase
            Visa card: **9803
            Amount: 8.3 EUR (36.25 SAR) FX 4.3675
            Wallet balance: 1955.55
            At: REGOLI DAL 1916
            Country: Italy
            2026-03-26 12:42
        """.trimIndent()
        val r = parser().parse(event("barq app", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("36.25"))
        assertThat(r.merchant).contains("REGOLI")
    }

    @Test fun `Barq Debit Transfer Internal`() {
        val body = """
            Debit Transfer Internal
            Amount: 240.00 SAR
            To: VAHID AHMA**
            Beneficiary A/C:**4445
            2025-10-02 00:29
        """.trimIndent()
        val r = parser().parse(event("barq app", body)) as ParseResult.Success
        assertThat(r.amount.amount).isEqualTo(BigDecimal("240.00"))
        assertThat(r.counterparty).contains("VAHID")
    }

    @Test fun `Barq Credit Transfer Local`() {
        val body = """
            Credit transfer Local
            Amount: 2500.00 SAR
            From: WALEED ALGHAMDI
            a/c: **4268
            Bank: RAJHI BANK
            2025-10-02 01:25
        """.trimIndent()
        val r = parser().parse(event("barq app", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("2500.00"))
    }

    @Test fun `Barq OTP is Ignored`() {
        val body = """
            One time password:
            OTP: 493626
            Amount: 618.0
            Currency: CNY
            At: Alipay
        """.trimIndent()
        assertThat(parser().parse(event("barq app", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `Barq beneficiary activated is Ignored`() {
        val body = "New benefeciary activated: Waleed Hamed Alghamdi"
        assertThat(parser().parse(event("barq app", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `Barq Rejected Transaction is Ignored`() {
        val body = """
            Rejected transaction: Insufficient balance
            Card: **9803
            Amount: 4115.88 CNY (2151.15 SAR)
            At: ALP zhangjiajieruidel
        """.trimIndent()
        assertThat(parser().parse(event("barq app", body))).isEqualTo(ParseResult.Ignored)
    }

    // ─── Family-device corpus additions ──────────────────────────────────

    @Test fun `SNB AlAhli outgoing internal transfer is TRANSFER`() {
        val body = """
            حوالة صادرة داخلية
            مبلغ:1100 SAR
            إلى:مستفيد العائلة
            إلى:304*111
            في:20/01/25 16:56
        """.trimIndent()
        val r = parser().parse(event("SNB-AlAhli", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.TRANSFER)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("1100"))
        assertThat(r.counterparty).isEqualTo("مستفيد العائلة")
        assertThat(r.templateId).isEqualTo("snb-structured")
    }

    @Test fun `SNB AlAhli OTP amount preauthorization is Ignored`() {
        val body = """
            لا تشارك رمز التفعيل 5311
            ‬‪تحويل داخل البنك
            مبلغ ‬‪SAR ‬‪1100
        """.trimIndent()
        assertThat(parser().parse(event("SNB-AlAhli", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `SNB AlAhli public service payment captures agency merchant`() {
        val body = """
            مدفوعات وزارة الداخلية
            من 304*111
            مبلغ 300 SAR
            الجهة المخالفات المرورية
            الخدمة الاستعلام عن المخالفات برقم المخالفة
            رقم الفاتورة 1234567890
            في 20/01/25 16:56
        """.trimIndent()
        val r = parser().parse(event("SNB-AlAhli", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("300"))
        assertThat(r.merchant).isEqualTo("المخالفات المرورية")
        assertThat(r.templateId).isEqualTo("snb-structured")
    }

    @Test fun `SNB AlAhli ATM withdrawal gets ATM merchant fallback`() {
        val body = """
            سحب صراف آلي
            مبلغ 1000 SAR
            بطاقة مدى *1111
            بـ SAMPLE ATM
            في 20/01/25 16:56
        """.trimIndent()
        val r = parser().parse(event("SNB-AlAhli", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("1000"))
        assertThat(r.merchant).isEqualTo("ATM Withdrawal")
        assertThat(r.templateId).isEqualTo("snb-structured")
    }

    @Test fun `D360 Arabic cash withdrawal gets ATM merchant fallback`() {
        val body = """
            سحب نقدي
            مبلغ: SAR 1000.00
            بطاقة: *1111 - mada
            لدى: SAMPLE BANK
            في: 10:59 2025-01-19
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("1000.00"))
        assertThat(r.merchant).isEqualTo("ATM Withdrawal")
        assertThat(r.templateId).isEqualTo("d360-structured")
    }

    @Test fun `AlJazira incoming internal transfer is INCOME`() {
        val body = """
            حوالة واردة داخلية
            مبلغ: SAR 205,328.79
            إلى: 8001
            اسم المرسل: جهة تحويل
            في: 2025-04-06 13:16
        """.trimIndent()
        val r = parser().parse(event("AlJaziraSMS", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("205328.79"))
        assertThat(r.counterparty).isEqualTo("جهة تحويل")
        assertThat(r.templateId).isEqualTo("aljazira-structured")
    }

    @Test fun `Jazira outgoing accepted transfer is TRANSFER`() {
        val body = """
            عملية حوالة مالية صادرة مقبولة
            خصمت من حساب: 8001
            الى: مستفيد العائلة
            مبلغ العملية: 498.00 SAR
            المعرف البديل \الايبان : 8573
            [بنك الراجحي]
            في: 2026-01-16 20:27
            رقم المعاملة: 2BTMS12027368595
        """.trimIndent()
        val r = parser().parse(event("Jazira Bank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.TRANSFER)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("498.00"))
        assertThat(r.counterparty).isEqualTo("مستفيد العائلة")
        assertThat(r.templateId).isEqualTo("aljazira-structured")
    }

    @Test fun `Jazira account-only fee debit gets bank fees merchant`() {
        val body = """
            خصم: رسوم
            السبب: ضريبة القيمة المضافة
            من: 8001
            مبلغ: 0.08 SAR
            في: 2026-01-16 20:27
        """.trimIndent()
        val r = parser().parse(event("Jazira Bank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("0.08"))
        assertThat(r.merchant).isEqualTo("Bank fees")
        assertThat(r.templateId).isEqualTo("aljazira-structured")
    }

    @Test fun `AlJazira Arabic credit card settlement is transfer`() {
        val body = """
            بطاقة إئتمانية: تسديد
            بطاقة: 1234;إئتمانية
            مبلغ: SAR 500.00
            من: 8001
            في: 2026-01-16 20:27
        """.trimIndent()
        val r = parser().parse(event("AlJaziraSMS", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.TRANSFER)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("500.00"))
        assertThat(r.counterparty).isEqualTo("Credit Card Payment")
        assertThat(r.templateId).isEqualTo("aljazira-structured")
    }

    @Test fun `Jazira one-time-password with amount is Ignored`() {
        val body = """
            كلمة مرور صالحة لمرة واحدة
            رمز: 6826
            السبب: التحويل عبر خدمة مدفوعات سريع
            المستفيد: مستفيد
            المبلغ: 498.00 SAR
            التاريخ: 20:27 16-01-2026
        """.trimIndent()
        assertThat(parser().parse(event("Jazira Bank", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `beneficiary status notices are Ignored`() {
        val inactive = """
            اسم المستفيد : مستفيد تجريبي
            اسم المخصص : مستفيد
            حالة: غير نشط
            SA0000000000000000000000 : حساب
            مصرف الراجحي : مصرف
            في : 20:27 16-01-2026
        """.trimIndent()
        assertThat(parser().parse(event("AlJaziraSMS", inactive))).isEqualTo(ParseResult.Ignored)

        val activated = """
            اسم المستفيد:مستفيد تجريبي, رقم المرجع: 1234, الحالة: تم التنشيط, التاريخ والوقت: 16-01-2026 20:27:00
        """.trimIndent()
        assertThat(parser().parse(event("D360 Bank", activated))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `D360 Arabic international purchase uses structured fallback merchant`() {
        val body = """
            شراء دولي
            مبلغ: USD 12.00 (SAR 45.00)
            بطاقة: *1111 - VISA (Card)
            لدى: SAMPLE MERCHANT
            الدولة: USA
            في: 20:27 2026-01-16
        """.trimIndent()
        val r = parser().parse(event("D360 Bank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("45.00"))
        assertThat(r.merchant).isEqualTo("SAMPLE MERCHANT")
        assertThat(r.templateId).isEqualTo("d360-structured")
    }

    @Test fun `urpay Arabic purchase captures merchant from from field`() {
        val body = """
            شراء
            بطاقة:2322
            مبلغ:SAR 19
            من:BARNS AL..
            في:14-1-2025 09:45
            الرصيد المتبقي:824.18 SAR
        """.trimIndent()
        val r = parser().parse(event("urpay", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("19"))
        assertThat(r.merchant).isEqualTo("BARNS AL..")
        assertThat(r.templateId).isEqualTo("urpay-structured")
    }

    @Test fun `urpay Arabic refund is income`() {
        val body = """
            استرداد مبلغ
            من:SAMPLE MERCHANT
            بطاقة:1234; بطاقة مدى
            مبلغ:SAR 42.50
            16-01-2026 20:27
        """.trimIndent()
        val r = parser().parse(event("urpay", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("42.50"))
        assertThat(r.counterparty).isEqualTo("SAMPLE MERCHANT")
        assertThat(r.templateId).isEqualTo("urpay-structured")
    }

    @Test fun `urpay device-linking notice is Ignored`() {
        val body = "تم إلغاء ربط جهاز android v33 بحسابك"
        assertThat(parser().parse(event("urpay", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `lowercase Alinma maintenance notice is Ignored`() {
        val body = """
            نفيدكم أنه اعتبارًا من 01 فبراير 2026 سيتم تحديث قائمة رسوم التعرفة البنكية لبعض الخدمات والمنتجات.
            للمزيد، يرجى الاطلاع على موقع المصرف.
        """.trimIndent()
        assertThat(parser().parse(event("alinma", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `generic structured bank template parses comma decimal amount`() {
        val body = """
            شراء
            مبلغ:18,50 SAR
            لدى: Coffee Shop
            في:14-1-2026 09:45
        """.trimIndent()
        val r = parser().parse(event("Alinma", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("18.50"))
        assertThat(r.amount.currency).isEqualTo("SAR")
        assertThat(r.merchant).isEqualTo("Coffee Shop")
        assertThat(r.templateId).isEqualTo("alinma-structured")
    }

    @Test fun `universal fallback preserves comma decimal foreign currency amount`() {
        val body = "Purchase EUR 18,50 at Carrefour"
        val r = parser().parse(event("Alinma", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("18.50"))
        assertThat(r.amount.currency).isEqualTo("EUR")
        assertThat(r.merchant).isEqualTo("Carrefour")
        assertThat(r.templateId).isEqualTo("universal-amount")
    }

    @Test fun `universal fallback preserves broader foreign currency code`() {
        val body = "Purchase SGD 6.40 at Starbucks"
        val r = parser().parse(event("Alinma", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("6.40"))
        assertThat(r.amount.currency).isEqualTo("SGD")
        assertThat(r.merchant).isEqualTo("Starbucks")
        assertThat(r.templateId).isEqualTo("universal-amount")
    }

    @Test fun `universal fallback classifies Arabic transfer wording as transfer`() {
        val body = """
            حوالة مالية صادرة SAR 250
            إلى: مستفيد تجريبي
            في:27/12/25 23:04
        """.trimIndent()

        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success

        assertThat(r.type).isEqualTo(TxType.TRANSFER)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("250"))
        assertThat(r.merchant).isNull()
        assertThat(r.counterparty).isEqualTo("مستفيد تجريبي")
        assertThat(r.templateId).isEqualTo("universal-amount")
    }

    @Test fun `universal fallback keeps Arabic purchase wording as expense`() {
        val body = """
            تمت عملية شراء SAR 42
            لدى: متجر تجريبي
            في:27/12/25 23:04
        """.trimIndent()

        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success

        assertThat(r.type).isEqualTo(TxType.EXPENSE)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("42"))
        assertThat(r.merchant).isEqualTo("متجر تجريبي")
        assertThat(r.counterparty).isNull()
        assertThat(r.templateId).isEqualTo("universal-amount")
    }

    @Test fun `uppercase STCPAY migration notice is Ignored`() {
        val body = """
            ستنتقل جميع خدمات stc pay إلى STC Bank ولضمان استمرار خدماتكم، يرجى تحميل تطبيق STC Bank.
        """.trimIndent()
        assertThat(parser().parse(event("STCPAY", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `BSF digital-services outage notice is Ignored`() {
        val body = """
            عزيزي عميل BSF،
            نود إشعاركم بأن البنك سيقوم بتحديث الأنظمة وستكون الخدمات الرقمية خارج الخدمة مؤقتًا.
        """.trimIndent()
        assertThat(parser().parse(event("BSF", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `Arabic beneficiary add and activation notices are Ignored`() {
        assertThat(
            parser().parse(event("AlRajhiBank", "تمت اضافة المستفيد: مستفيد تجريبي")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("AlRajhiBank", "تم تنشيط المستفيد:مستفيد تجريبي")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("SNB-AlAhli", "تم إضافة مستفيد-داخل البنك المستفيد مستفيد تجريبي")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(
                event(
                    "SNB-AlAhli",
                    """
                    تم تنشيط مستفيد - بنك محلي
                    الاسم مستفيد تجريبي
                    """.trimIndent(),
                ),
            ),
        ).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `Arabic mobile login and biometric notices are Ignored`() {
        assertThat(
            parser().parse(event("AlRajhiBank", "اشعار:تم تسجيل جهاز جديد للدخول لتطبيق الراجحي باستخدام البصمة")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("AlRajhiBank", "اشعار:تم تفعيل خدمة الدخول السريع لتطبيق الراجحي")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("SNB-AlAhli", "تم تسجيل الدخول إلى حسابك عبر تطبيق الأهلي موبايل باستخدام جهاز جديد")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("SNB-AlAhli", "تم التسجيل في خاصية الدخول السريع للأهلي موبايل")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("SNB-AlAhli", "تم إلغاء خاصية البصمة للأهلي موبايل")),
        ).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `card product and inactive card notices are Ignored`() {
        val creditApproval = """
            عزيزي العميل، تمت الموافقة على طلبكم لمنتج بطاقة الائتمان والحد الائتماني هو 0،
            ويمكنك تنفيذ العقد واصدار البطاقة من خلال تطبيق البنك.
        """.trimIndent()
        assertThat(parser().parse(event("AlRajhiBank", creditApproval))).isEqualTo(ParseResult.Ignored)

        val cardTerms = "عميلنا العزيز، تم تحديث شروط استبدال البطاقة البلاستيكية التالفة أو المفقودة في صفحة التعرفة البنكية."
        assertThat(parser().parse(event("D360 Bank", cardTerms))).isEqualTo(ParseResult.Ignored)

        val inactiveCard = "عملية مرفوضة: بطاقتك غير مفعلة، الرجاء الدخول للتطبيق ثم الضغط على إدارة البطاقة ثم التفعيل."
        assertThat(parser().parse(event("D360 Bank", inactiveCard))).isEqualTo(ParseResult.Ignored)

        val inactiveCardVariant = """
            إشعار: عملية مرفوضة - البطاقة غير مفعلة
            العملية : شراء انترنت
            بطاقة : 1111***
            مبلغ : 250.00 SAR
            على : Sample
            في :16-01-2026 20:27
        """.trimIndent()
        assertThat(parser().parse(event("AlRajhiBank", inactiveCardVariant))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `card terms and renewal notices are Ignored`() {
        val terms = """
            بطاقة مدى
            يمكن الاطلاع على الشروط والأحكام، يرجى زيارة موقع مصرف الراجحي.
        """.trimIndent()
        assertThat(parser().parse(event("AlRajhiBank", terms))).isEqualTo(ParseResult.Ignored)

        val renewal = """
            تقترب بطاقة مدى من الانتهاء، بإمكانكم تجديد البطاقة من خلال تطبيق المصرف.
        """.trimIndent()
        assertThat(parser().parse(event("AlRajhiBank", renewal))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `Arabic card cashback is income not expense`() {
        val body = "مبروك! تم إضافة 5.00 ريال لبطاقتك المنتهية بـ 1111 كاسترداد نقدي."
        val r = parser().parse(event("AlRajhiBank", body)) as ParseResult.Success
        assertThat(r.type).isEqualTo(TxType.INCOME)
        assertThat(r.amount.amount).isEqualTo(BigDecimal("5.00"))
    }

    @Test fun `bank promo app migration document and fraud notices are Ignored`() {
        val savingsPromo = """
            مبروك، حساب سنابل حقق لك أول ربح يومي!
            افتح التطبيق وشيّك على أرباحك وتابع نمو مدخراتك.
        """.trimIndent()
        assertThat(parser().parse(event("D360 Bank", savingsPromo))).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("D360 Bank", "تم تفعيل الرمز الترويجي على حسابك الادخاري، استمتع بعرضك الآن!")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("D360 Bank", "مبروك! تم تنشيط حسابك الادخاري الجديد. رقم الآيبان الخاص بك هو SA0000000000000000000000")),
        ).isEqualTo(ParseResult.Ignored)

        val migration = """
            عزيزي العميل،
            خلال الأيام القادمة سيتم تحويل جميع الخدمات المصرفية الى تطبيق بنك الجزيرة الجديد.
            حمّل التطبيق الآن لتجربة مصرفية متكاملة.
        """.trimIndent()
        assertThat(parser().parse(event("AlJaziraSMS", migration))).isEqualTo(ParseResult.Ignored)

        val documentNotice = "Dear customer, you can view and download your document through AlJazira Online by navigating to the Bank Documents menu."
        assertThat(parser().parse(event("AlJaziraSMS", documentNotice))).isEqualTo(ParseResult.Ignored)

        val fraudNotice = """
            عزيزي العميل، احذر من المكالمات التي تدعي أنها جهة رسمية
            وتطلب منك شراء بطاقات إهداء ثم تزويدها برموز هذه البطاقات.
        """.trimIndent()
        assertThat(parser().parse(event("AlJaziraSMS", fraudNotice))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `card service recovery and brand announcement notices are Ignored`() {
        val cardServiceNotice = """
            عميلنا العزيز،
            نعتذر عن الخلل الذي واجهته أثناء استخدام البطاقة، ونود إبلاغك بأنه يمكنك استخدام بطاقتك مجددًا الآن.
            شكرًا لتفهّمك.
        """.trimIndent()
        assertThat(parser().parse(event("D360 Bank", cardServiceNotice))).isEqualTo(ParseResult.Ignored)

        val brandAnnouncement = """
            نطلق اليوم هويتنا الجديدة والتي تعكس رؤيتنا وتلبي طموحاتكم.
            معًا نواصل رحلة النمو.
            بنك الجزيرة .. هنا تنمو الثروات.
        """.trimIndent()
        assertThat(parser().parse(event("Jazira Bank", brandAnnouncement))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `account card and reward state notices with amounts are Ignored`() {
        assertThat(
            parser().parse(event("AlRajhiBank", "تم ربط رقم الجوال 0500000000 بالبطاقة 1234 بنجاح")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("AlJaziraSMS", "إجمالي رصيد نقاطك في برنامج مكافآتي هو 12345.0 نقطة.")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("AlRajhiBank", "تم تغيير حد التحويل اليومي\nالحد:50000SAR")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("SNB-AlAhli", "تم تغيير الحد اليومي للعمليات بنجاح\nإلى 50000.00 SAR\nفي 16/01/26 20:27")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("SNB-AlAhli", "اسم المستخدم الخاص بك للخدمات الالكترونية هو 123456789")),
        ).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `compact beneficiary and digital-card administration notices are Ignored`() {
        assertThat(
            parser().parse(event("D360 Bank", "تم تنشيط مستفيد:مستفيد تجريبي فى : 16-01-2026 20:27:00")),
        ).isEqualTo(ParseResult.Ignored)
        assertThat(
            parser().parse(event("D360 Bank", "تم اصدار بطاقتك الرقمية المنتهية ب *1234 بنجاح")),
        ).isEqualTo(ParseResult.Ignored)
        val activeBeneficiary = """
            اسم المستفيد : مستفيد تجريبي
            اسم المخصص : مستفيد
            حالة: نشط
            SA0000000000000000000000 : حساب
            مصرف الراجحي : مصرف
            في : 20:27 16-01-2026
        """.trimIndent()
        assertThat(parser().parse(event("AlJaziraSMS", activeBeneficiary))).isEqualTo(ParseResult.Ignored)
    }

    // ─── Cross-cutting: unknown sender ────────────────────────────────────

    @Test fun `unknown sender with money figure is Ignored — NOT a transaction`() {
        // The exact bug the user reported: random promo "Earn SAR 10,000 today!"
        // from a marketing shortcode used to slip through UniversalAmountTemplate.
        val body = "Congratulations! You can earn up to SAR 10,000 today. Apply now."
        val r = parser().parse(event("PROMO-9999", body))
        assertThat(r).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `unknown sender even with bank-like message is Ignored`() {
        val body = """
            Online Purchase
            Amount: SAR 50
            At: McDonalds
        """.trimIndent()
        // Sender is clearly not a bank — refuse to parse.
        val r = parser().parse(event("RANDOM-SENDER", body))
        assertThat(r).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `known sender with marketing message is Ignored`() {
        // Banks sometimes send marketing themselves; the GlobalBankIgnoreTemplate catches it.
        val body = "Your financing is ready! No salary transfer required, with instant approval and deposit. Apply now."
        val r = parser().parse(event("AlRajhiBank", body))
        assertThat(r).isEqualTo(ParseResult.Ignored)
    }

    // ─── Sustainability guarantees ─────────────────────────────────────────
    // These pin behaviours future users depend on. The Saudi CITC '-AD' suffix
    // is the convention for marketing channels and must never reach the parser.

    @Test fun `any sender ending in -AD is ignored regardless of body`() {
        // AlRajhiB-AD is the Al Rajhi *marketing* channel — looks bank-ish but isn't transactions.
        val cashbackPromo = """
            Earn 10% cashback up to SAR 250 on international spends above SAR 8000
            using credit card. Offer valid till 15 Jan 2026.
        """.trimIndent()
        assertThat(parser().parse(event("AlRajhiB-AD", cashbackPromo))).isEqualTo(ParseResult.Ignored)

        val travelPromo = "Use your Infinite Credit Card while travelling abroad and earn 100% cashback up to SAR 50"
        assertThat(parser().parse(event("eXtra-AD", travelPromo))).isEqualTo(ParseResult.Ignored)
        assertThat(parser().parse(event("JARIR-AD", travelPromo))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `mokafaa loyalty program is blocked even though it talks about money`() {
        val body = "Save your Platinum Rewards! You are 4 Purchases away from earning 5X Shukrans on your purchases!"
        assertThat(parser().parse(event("mokafaa", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `FoodicsOTP and similar OTP-only senders are blocked`() {
        assertThat(parser().parse(event("FoodicsOTP", "Your verification code is 179996"))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `Tasaheal installment marketing from real bank sender is ignored`() {
        val body = "Hello, Pay your credit card transactions over 24 months with Tasaheal fixed monthly installments plan."
        assertThat(parser().parse(event("AlRajhiBank", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `prize contest with monetary value is ignored`() {
        val body = "جوائز ما تتفوّت بقيمة 7,000 ريال وأكثر. سجّل الآن لفرصة الفوز بايفون 15"
        assertThat(parser().parse(event("AlRajhiBank", body))).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `Arabic personal financing offer is ignored`() {
        val body = "مرحبا وليد، تمويلك الشخصي بمبلغ يوصل لـ 432000 وفوقه 50,000 نقطة مكافأة. تطبق الشروط."
        assertThat(parser().parse(event("AlRajhiBank", body))).isEqualTo(ParseResult.Ignored)
    }
}
