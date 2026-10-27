# WM050 — Prefer explicitly labelled transaction money

## Goal and cause

The known-bank universal fallback currently chooses the first numeric substring, even when an account, card or reference precedes a clearly labelled `Amount` field. That can fabricate the amount and default its currency to SAR. This is a parsing/mapping defect, not evidence for automatic posting or categorization. A parent-local independent check of 95 single-primary-field sources found 38 disagreements with the retained historical parse profile. Those expectations are private and partial; synthetic regressions are public.

## Inputs and outputs

Keep the existing `BankTemplate` API, sender allow-list, `Normalize.amount`, literal currency and low-confidence result. Select a single explicitly labelled amount before the legacy unlabelled fallback. Support English and Arabic amount labels and normalized numeral scripts. The selected numeric token must begin at the field value; a malformed value must not fall forward to a later balance, identifier or date. Reject conflicting repeated primary labels and incomplete numeric/currency tokens rather than repairing them. Preserve valid inline following fields and foreign principal plus secondary settlement text without converting or adding currencies. This slice does not establish settlement/fee attribution for generic formats.

If no primary label exists, retain the current heuristic behavior and its limitations. No new receipt-date inference, certainty promotion, canonical graph/status change, historical correction, deletion, network request or rollout activation is included. Existing specific templates keep priority.

## Acceptance

Exercise the production registry as well as the universal template: identifiers and balances before an explicit amount, leading/trailing currencies, USD with SAR balance, meaningful KWD precision, Arabic digits, malformed explicit value, repeated labels, and pending/declined/refund/transfer preservation. Retain every previous parser testcase identity. Record genuine assertion RED before the fix, then full parser GREEN. Integrate both notice and money fixes into one unseeded candidate. The parent must execute the extended source-specific oracle on the authorized archive, walk its actual native results, and restore the exact encrypted nonzero baseline. Compilation alone is not real-data or complete financial accuracy acceptance.
