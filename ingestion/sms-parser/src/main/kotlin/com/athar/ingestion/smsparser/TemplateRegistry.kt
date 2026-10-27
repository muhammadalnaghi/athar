package com.athar.ingestion.smsparser

import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport

/**
 * Parser implementation that tries each [BankTemplate] in order, returning the first
 * Success or Ignored. Failed results are aggregated for diagnostics.
 *
 * Order matters: more specific templates should come first (balance-alert before purchase),
 * so balance/OTP/marketing messages exit early instead of getting parsed as zero-amount.
 */
class TemplateBasedSmsParser(
    private val templates: List<BankTemplate>,
) : SmsParser {

    override fun parse(event: RawIngestEvent): ParseResult {
        val applicable = templates.filter { it.senderMatcher.matches(event.sender) }
        if (applicable.isEmpty()) return ParseResult.Ignored

        val attempts = mutableListOf<String>()
        for (template in applicable) {
            when (val result = template.tryParse(event.body, event.receivedAt)) {
                is ParseResult.Success -> {
                    val repaymentContext = template is com.athar.ingestion.smsparser.alrajhi.AlRajhiCreditCardPaymentTemplate &&
                        com.athar.ingestion.smsparser.alrajhi.AlRajhiCardRepaymentEvidence062
                            .validatesCardRepaymentContext(event.body)
                    val posting = BankPostedLanguage.evidence(
                        event.body,
                        validatedCardTemplate = template is com.athar.ingestion.smsparser.barq.BarqOnlinePurchaseTemplate ||
                            template is com.athar.ingestion.smsparser.barq.BarqPosInternationalTemplate ||
                            (
                                template is com.athar.ingestion.smsparser.alrajhi.AlRajhiPosPurchaseRealTemplate &&
                                    com.athar.ingestion.smsparser.alrajhi.AlRajhiPostedEvidence
                                        .validatesPosInternationalPurchaseContext(event.body)
                                ) ||
                            repaymentContext,
                    )
                    // For this restricted family, generic completed language cannot
                    // bypass the real winning-template and single-SAR-amount gate.
                    // Preserve every typed negative state and all other families.
                    val restrictedRepayment = result.templateId == "al-rajhi-credit-card-payment"
                    return result.copy(
                        posting = if (restrictedRepayment && !repaymentContext && posting.state == PostingState.POSTED) {
                            PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY)
                        } else posting,
                        bankEventDate = parseSmsStatedDate(event.body, event.receivedAt),
                    )
                }
                is ParseResult.Ignored -> return result
                is ParseResult.Failed -> attempts += template.id
            }
        }
        return ParseResult.Failed(
            reason = "no template matched (${attempts.size} attempts)",
            templateAttempts = attempts,
        )
    }
}
