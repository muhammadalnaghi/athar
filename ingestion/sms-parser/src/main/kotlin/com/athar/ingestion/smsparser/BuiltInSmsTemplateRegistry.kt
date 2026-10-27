package com.athar.ingestion.smsparser

import com.athar.ingestion.smsparser.alrajhi.AlRajhiBalanceAlertTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiBillPaymentTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiCreditCardPaymentTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiCreditLocalTransferTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiDebitInternalTransferTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiDebitLocalTransferTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiDeclinedTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiDepositRealTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiDepositTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiGenericAmountTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiInternalTransferTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiLoanInstalmentTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiOnlinePurchaseRealTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiPosPurchaseRealTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiPosPurchaseTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiPurchaseTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiReverseTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiTransferBetweenOwnTemplate
import com.athar.ingestion.smsparser.alrajhi.AlRajhiTransferOutTemplate
import com.athar.ingestion.smsparser.barq.BarqAtmWithdrawalTemplate
import com.athar.ingestion.smsparser.barq.BarqCreditTransferTemplate
import com.athar.ingestion.smsparser.barq.BarqDebitTransferTemplate
import com.athar.ingestion.smsparser.barq.BarqOnlinePurchaseTemplate
import com.athar.ingestion.smsparser.barq.BarqPosInternationalTemplate
import com.athar.ingestion.smsparser.barq.BarqRejectedTemplate
import com.athar.ingestion.smsparser.d360.D360AccountFundingTemplate
import com.athar.ingestion.smsparser.d360.D360DeclinedTemplate
import com.athar.ingestion.smsparser.d360.D360IncomingTransferTemplate
import com.athar.ingestion.smsparser.d360.D360InternationalPurchaseTemplate
import com.athar.ingestion.smsparser.d360.D360InternationalTransferTemplate
import com.athar.ingestion.smsparser.d360.D360LocalPurchaseTemplate
import com.athar.ingestion.smsparser.d360.D360OnlinePurchaseTemplate
import com.athar.ingestion.smsparser.genericbank.AlinmaTemplate
import com.athar.ingestion.smsparser.genericbank.AlJaziraTemplate
import com.athar.ingestion.smsparser.genericbank.AnbTemplate
import com.athar.ingestion.smsparser.genericbank.BarqTemplate as GenericBarqTemplate
import com.athar.ingestion.smsparser.genericbank.D360Template as GenericD360Template
import com.athar.ingestion.smsparser.genericbank.RiyadBankTemplate
import com.athar.ingestion.smsparser.genericbank.SnbTemplate
import com.athar.ingestion.smsparser.genericbank.UrpayTemplate
import com.athar.ingestion.smsparser.notification.GenericBankNotificationTemplate
import com.athar.ingestion.smsparser.stcbank.StcBankIncomingTransferTemplate
import com.athar.ingestion.smsparser.stcbank.StcBankOnlinePurchaseTemplate
import com.athar.ingestion.smsparser.stcbank.StcBankOutgoingTransferTemplate
import com.athar.ingestion.smsparser.stcbank.StcBankPayQattahTemplate
import com.athar.ingestion.smsparser.stcbank.StcBankSarieOutwardTemplate
import com.athar.ingestion.smsparser.stcpay.StcPayIgnoreTemplate
import com.athar.ingestion.smsparser.stcpay.StcPayIncomingTemplate
import com.athar.ingestion.smsparser.stcpay.StcPayOutgoingTemplate
import com.athar.ingestion.smsparser.universal.UniversalAmountTemplate

object BuiltInSmsTemplateRegistry {

    /**
     * Production parser order. Keep this in the pure parser module so app DI, corpus
     * tests, and private aggregate audits all exercise the same built-in behavior.
     */
    fun templates(): List<BankTemplate> = listOf(
        // Global ignore for OTPs / beneficiary admin / marketing across ALL banks. First in line.
        GlobalBankIgnoreTemplate(),
        // Store-safe flavor: bank-app push notifications use Android package names as senders.
        GenericBankNotificationTemplate(),

        // Al Rajhi - declined first (returns Ignored, not a failed parse).
        AlRajhiDeclinedTemplate(),
        AlRajhiBalanceAlertTemplate(),
        // Real-format templates derived from the private corpus.
        AlRajhiOnlinePurchaseRealTemplate(),
        AlRajhiPosPurchaseRealTemplate(),
        AlRajhiReverseTemplate(),
        AlRajhiBillPaymentTemplate(),
        AlRajhiLoanInstalmentTemplate(),
        AlRajhiCreditCardPaymentTemplate(),
        AlRajhiCreditLocalTransferTemplate(),
        AlRajhiDebitLocalTransferTemplate(),
        AlRajhiDebitInternalTransferTemplate(),
        AlRajhiTransferBetweenOwnTemplate(),
        AlRajhiDepositRealTemplate(),
        // Synthetic legacy templates kept as fallbacks for older message shapes.
        AlRajhiPosPurchaseTemplate(),
        AlRajhiInternalTransferTemplate(),
        AlRajhiPurchaseTemplate(),
        AlRajhiTransferOutTemplate(),
        AlRajhiDepositTemplate(),
        AlRajhiGenericAmountTemplate(),

        // STC Bank, distinct from STC Pay.
        StcBankIncomingTransferTemplate(),
        StcBankOutgoingTransferTemplate(),
        StcBankSarieOutwardTemplate(),
        StcBankOnlinePurchaseTemplate(),
        StcBankPayQattahTemplate(),

        // STC Pay wallet.
        StcPayIgnoreTemplate(),
        com.athar.ingestion.smsparser.stcpay.StcPayOnlinePurchaseTemplate(),
        StcPayOutgoingTemplate(),
        StcPayIncomingTemplate(),

        // D360 digital bank.
        D360DeclinedTemplate(),
        D360OnlinePurchaseTemplate(),
        D360InternationalPurchaseTemplate(),
        D360LocalPurchaseTemplate(),
        D360AccountFundingTemplate(),
        D360IncomingTransferTemplate(),
        D360InternationalTransferTemplate(),

        // Barq wallet.
        BarqRejectedTemplate(),
        BarqOnlinePurchaseTemplate(),
        BarqPosInternationalTemplate(),
        BarqAtmWithdrawalTemplate(),
        BarqDebitTransferTemplate(),
        BarqCreditTransferTemplate(),

        // Other Saudi banks/wallets using structured Amount/At/From/To formats.
        AlinmaTemplate(),
        GenericD360Template(),
        GenericBarqTemplate(),
        RiyadBankTemplate(),
        SnbTemplate(),
        AlJaziraTemplate(),
        UrpayTemplate(),
        AnbTemplate(),

        // Universal last-resort, restricted to known bank senders.
        UniversalAmountTemplate(),
    )
}
