# WM051 — Transfer authentication is not a completed transfer

## Goal

A numeric authentication instruction must not become a financial row merely because it mentions the amount of a future transfer. During the parent-local full archive walkthrough of the WM049/050 candidate, five such sources were still parsed as heuristic transfers. Their templates say that a supplied code is needed to complete the transfer. They do not say that funds moved. This is an ingestion/parser classification defect and unnecessary user review, not a reason to confirm or repair history.

## Contract

Recognize the bounded Arabic structure: a code heading (`رمز … هو:` and a numeric token), followed by the explicit purpose `لإتمام عملية التحويل`. This strong authentication purpose identifies a nonfinancial notice even when it includes a proposed amount. Recognize supported Arabic numeral scripts through the existing normalizer. Do not generalize to any message containing the word code, transfer, or a number.

A genuine completed debit, credit, refund, charge or transfer clause in the same body prevents this exclusion. The older bank templates retain the mixed message for normal parsing and uncertainty handling. No source text is rewritten; no existing financial record is deleted or automatically confirmed. No sender expansion, database migration, category-rule change, network use or rollout activation is included.

## Acceptance

Add synthetic production-registry tests for the purpose-qualified code with an amount, Arabic numerals, and a real completed movement appended to the authentication text. Retain all existing parser cases and the WM049/050 money/hold controls. Verify all five exact parent-private source references produce no financial row through the production pipeline. Preserve the historical archive bytes, audit evidence, source money/currency correctness, and the original encrypted application baseline. This scoped removal of false review items is not proof that every remaining item represents genuine ambiguity.
