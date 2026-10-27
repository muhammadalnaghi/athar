package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class AmountlessServiceNoticeTest {
    private val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())
    private val received = Instant.parse("2026-10-20T12:00:00Z")

    @Test fun beneficiaryAddedColon() = ignored("AlRajhiBank", "Beneficiary added: Example Recipient")
    @Test fun beneficiaryAddedSuccessfully() = ignored("alinma", "The beneficiary Local bank has been added successfully")
    @Test fun beneficiaryActivatedSuccessfully() = ignored("alinma", "The beneficiary Local bank has been activated successfully")
    @Test fun walletUnclaimedVerification() = ignored("urpay", "Your wallet is unclaimed. Please open the app and verify via Nafath to continue using our services")
    @Test fun internationalTransferInTransit() = ignored("D360 Bank", "Your international transfer to Example Payments Limited is on the way")
    @Test fun internationalTransferRecipientDecline() = ignored("D360 Bank", "Your international transfer to Example Payments Limited has been declined by the receiving bank")
    @Test fun internationalTransferCompleteWithoutAmount() = ignored("D360 Bank", "Your international transfer to Example Payments Limited is complete")
    @Test fun onlineServiceRegistration() = ignored("alinma", "Hala TEST USER, You have been successfully registered for service: alinma internet")
    @Test fun fingerprintLoginActivation() = ignored("alinma", "Hala TEST USER, Fingerprint login service has been successfully activated.")
    @Test fun quickLoginActivation() = ignored("alinma", "Hala TEST USER, Quick login service has been successfully activated.")
    @Test fun cardPinCreation() = ignored("alinma", "Hala TEST USER, A new PIN has been successfully created for your card.")
    @Test fun passwordHasBeenChanged() = ignored("alinma", "Your password has been changed Service: Alinma App")
    @Test fun quickLoginRequestArabic() = ignored("SNB-AlAhli", "تم تقديم طلب التسجيل لخدمة الدخول السريع بنجاح ، يرجى قبول المكالمة للتنشيط")
    @Test fun onlineServiceStoppedArabic() = ignored("SNB-AlAhli", "تم ايقاف خدمة الأهلي أون لاين والأهلي موبايل")
    @Test fun privacyPolicyUpdatedArabic() = ignored("SNB-AlAhli", "عزيزي العميل، لأن حقوقك تهمنا تم تحديث سياسة الخصوصية، الرجاء زيارة موقع البنك الرسمي للمزيد من المعلومات.")
    @Test fun identityExpiryUpdatedArabic() = ignored("BankAlbilad", "عميلنا العزيز: لقد تم تحديث تاريخ انتهاء الهوية آليا لدى البنك دون الحاجة لزيارة الفرع.")
    @Test fun accountDormancyArabic() = ignored("BankAlbilad", "عميلنا العزيز، تم تحويل حالة حسابكم البنكي لراكد نأمل زيارة اقرب فرع لتفعيله")
    @Test fun walletSuspiciousIdentityWarning() = ignored("STCPAY", "We've noticed a suspicious behavior on your wallet. Please login to stc pay now to verify your identity. stc pay staff will never call to ask for your password")

    @Test fun newNoticeRulesNeverSuppressNumericMoneyEvidence() {
        val template = GlobalBankIgnoreTemplate()
        for (body in listOf(
            "Beneficiary added: Example Recipient\nPurchase Amount: SAR 25.00",
            "Your international transfer to Example Recipient is complete\nAmount: KWD ١٢٫٣٤٥",
            "Your wallet is unclaimed\nAmount: SAR ۲۵",
            "تم تحديث سياسة الخصوصية\nشراء بمبلغ ١٠ ريال",
        )) {
            assertThat(template.tryParse(body, received)).isInstanceOf(ParseResult.Failed::class.java)
        }
    }

    @Test fun unknownAmountlessBankMessageStillNeedsDiagnosis() {
        assertThat(parser.parse(event("AlRajhiBank", "Unrecognized bank message without financial detail")))
            .isInstanceOf(ParseResult.Failed::class.java)
    }

    @Test fun explicitPurchaseStillParsesThroughProductionRegistry() {
        val result = parser.parse(event("AlRajhiBank", "PoS purchase\nCard:1234\nAt: Example Shop\nAmount:25 SAR"))
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        assertThat((result as ParseResult.Success).amount.amount.compareTo(java.math.BigDecimal("25"))).isEqualTo(0)
    }

    private fun ignored(sender: String, body: String) {
        assertThat(parser.parse(event(sender, body))).isEqualTo(ParseResult.Ignored)
    }

    private fun event(sender: String, body: String) = RawIngestEvent(
        id = "synthetic-notice", rawId = "synthetic-notice", source = IngestSource.SMS,
        sender = sender, body = body, receivedAt = received,
    )
}
