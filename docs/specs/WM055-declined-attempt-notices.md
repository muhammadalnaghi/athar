# WM055 — Declined-attempt prose notices: a conservative pure classifier

## Goal

A known-bank SMS that states in prose that one payment attempt genuinely failed
("Purchase of SAR25.00 at QA was declined") currently parses to a successful row
whose body evidence is DECLINED. The parent pipeline needs a way to recognize
exactly that family — after parsing, on the success branch only — so it can
record the raw message as an audit IGNORED with no financial row, without
touching legacy parser behavior. WM053 already covers line-headed
`Reason:`/`Status:` decline headers; this ticket covers the event-linked
refusal grammar those headers do not reach.

## Contract

`public object DeclinedAttemptNotice055 { fun matches(body: String): Boolean }`
— a pure, stateless classifier in `ingestion:sms-parser`, wired into nothing.
The parent additionally requires `parsed.posting.state == DECLINED` before
acting; the helper itself never parses amounts, infers posting from category,
or consults aggregate posting evidence (negative words dominate the aggregate
DECLINED state and would hide positive clauses).

Positive identification requires a payment-event noun (English
purchase/payment/transaction/transfer; Arabic عملية/الشراء/الدفع) linked within
a single clause to a refusal predicate:

- English passive/direct: "… was/has been declined|rejected|denied|unsuccessful|failed",
  "was not completed", "payment failed", "did not go through".
- Arabic: "عملية شراء مرفوضة", "تم رفض عملية الشراء", "فشلت عملية الشراء",
  "لم تتم عملية الشراء", "لم يتم الدفع".

Numeral script and bidi controls normalize through the existing `Normalize.digits`
(Arabic-Indic digits supported). A merchant merely named DECLINED/FAILED (no
auxiliary linking the event noun to the refusal), a conditional clause
("if/unless/in case/when…", Arabic إذا/في حال), a question form, or a refusal
that only survives inside quoted error text never matches; unknown or
ambiguous shapes return false.

Any genuine separately booked debit/fee/credit/refund/reversal/successful
retry — including on the same line or in Reason/Status fields — blocks the
classification. The movement check runs per line (never aggregate), with the
refusal's own negation ("not completed", "لم تتم …") neutralized first so it
cannot masquerade as movement vocabulary, and the same credit-limit-cause
exemption as WM053.

The parent also admits a narrow headed family: exactly one `Notification: Declined
due to Insufficient balance` paired with exactly one recognized purchase-kind
`Transaction:` field. The same full-body financial-movement guard applies; neither
an amount alone nor a free-form merchant field can establish refusal. Arabic refusal
adjectives must be adjacent to the operation/kind, not reached across `لدى` into
the merchant name. Existing parser amount expectations remain unchanged.

## Acceptance

Focused JVM tests (`*DeclinedAttemptNotice055*Test`) cover the positive
families in both languages (Latin and Arabic-Indic numerals) and every refusal
above, plus negatives: merchants named DECLINED/FAILED, conditional failures,
quoted error beside a completed payment, same-line debited fee, later
successful retry, refund, reversal, completed Status field, pending/question/
card-only/blank shapes. Full-parser controls pin legacy outputs unchanged: the
prose decline families still parse to `Success` with `posting.state == DECLINED`
and the WM053 header family still parses to `Ignored`. No app wiring, no
registry or template edits, no history repair or mass confirmation in this
ticket; the parent applies the narrow success-branch guard separately.
