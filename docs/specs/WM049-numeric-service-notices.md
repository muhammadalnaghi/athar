# WM049 — Numeric service notices must not become fake transactions

## Goal

Numeric administrative/authentication notices from known bank senders — beneficiary
activation with account/reference numbers, plain-code instructions, Arabic OTP
warnings — currently fall through `GlobalBankIgnoreTemplate` and reach
`UniversalAmountTemplate`, which reads the bare identifier digits (`11112222`,
`123456`) as an amount and posts a fake EXPENSE. They must be `ParseResult.Ignored`.

## Root cause

- Existing beneficiary ignore patterns are same-line (`[^\n\r]` windows) and do not
  allow the optional adverb `successfully` between `been` and `activated`, so
  `Beneficiary: X\nHas been successfully activated Account number:11112222` misses.
- `AmountlessServiceNotices` positively identifies service notices but requires a
  digit-free body (`body.none(Char::isDigit)`); every shape here carries digits by
  design, so it cannot fire.
- No pattern covers `use the code:<digits>` instructions, Arabic
  `لا تشارك … الرمز` (an interposed word breaks `لا تشارك رمز`), or `لديك رمز <digits>`
  (space, not colon, before the digits).
- `UniversalAmountTemplate` matches `\d+` with no currency and defaults to EXPENSE.

## Change (minimal, guarded)

New bounded helper `NumericServiceNotices049` (same package) consulted from
`GlobalBankIgnoreTemplate.tryParse` in `IgnorePatterns.kt`:

1. Positive patterns on `Normalize.digits(body)` (Arabic-Indic digits covered):
   beneficiary token within a bounded window of
   `has been (successfully)? added|activated|deleted|removed`; `use|enter the code:<3-8 digits>`;
   `لا تشارك … الرمز`; `لديك رمز <3-8 digits>`.
2. Financial-evidence guard — an explicit monetary field (including unsupported
   currencies), a currency-qualified amount or a completed movement keeps the body
   parseable. An instruction to use a code to transfer is not a completed transfer.
   Mixed money/admin notices bypass older blanket administration patterns too;
   this preserves a real refund with an appended beneficiary activation clause.
   Merchants merely named CODE do not identify authentication notices.

No registry order or posting diagnostic (`BankPostedLanguage`) is modified.
`AmountlessServiceNotices` keeps its digit-free guard. The global ignore template
consults this narrow positive classification before its older pattern list.

## Inputs / outputs

- Input: `RawIngestEvent` from a `KnownBankSenders.builtIn` sender (synthetic bodies
  only; all names/values fictional).
- Output: `Ignored` for the shapes above; unchanged Success/Failed for everything else.

## Acceptance

- New `GlobalBankIgnoreNumericServiceNotices049Test` (through the real
  `BuiltInSmsTemplateRegistry` + `TemplateBasedSmsParser`): each shape Ignored, incl.
  Arabic-Indic digits; posted purchase + appended code wording, Arabic debit +
  appended OTP warning, refund, declined-with-code all stay Success with amount/type/
  posting state preserved; unknown numeric text does not match the helper.
- Existing 541 parser test IDs unchanged; full `:ingestion:sms-parser:test` green.
