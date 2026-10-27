# WM062 — Al Rajhi card repayment template-context posted evidence

## Goal

Upgrade the posting support of the already-validated Al Rajhi credit-card repayment
template (`al-rajhi-credit-card-payment`, exact headings `Credit Card:Payment` /
`بطاقة ائتمانية:سداد`) from `POSTED/HEURISTIC_ONLY` to `POSTED/EXPLICIT_POSTED`
using the reviewed WM031/WM038 pattern: a registry-supplied `validatedCardTemplate`
flag granted only by a true template-context gate. The recorded movement stays a
TRANSFER to merchant "Credit Card Payment" — never an expense or income, and no
account ownership or routing is invented. This parser lane only supplies evidence;
the parent owns canonical/app integration.

## Root cause

`BankPostedLanguage.evidence` upgrades to `EXPLICIT_POSTED` only on generic
completed-verb language or a validated card template. Neither heading carries such
language: `Credit Card:Payment` is not a `payment … completed|successful` phrase,
and the Arabic heading lacks the `تم …` verb — so completed repayments stay
heuristic even though the winning template's exact heading proves the posting.

## Inputs

- Body of an Al Rajhi sender event parsed successfully by an actual
  `AlRajhiCreditCardPaymentTemplate` instance (class identity, never a returned
  templateId alone).
- The template's own normalized body (`Normalize.digits`) — the same text the
  template matched against.

## Outputs

- New `AlRajhiCardRepaymentEvidence062` (package `com.athar.ingestion.smsparser.alrajhi`):
  a single pure predicate, true only when (a) the body carries the template's exact
  heading, (b) exactly one payment amount carries an explicit `SAR`/`SR`/`ر.س`
  marker, and (c) no additional monetary line exists — fee/commission/tax lines, a
  second marked amount, or any other currency-token-bearing line. Result-balance
  lines (`Balance`/`رصيد`) are the only monetary lines ignored.
- One minimal `TemplateRegistry.kt` hook: `validatedCardTemplate` additionally true
  when the winning template instance is `AlRajhiCreditCardPaymentTemplate` and the
  gate passes. Existing Barq and WM038 PoS clauses stay byte-equivalent.

## Invariants (unchanged code, unchanged behavior)

- `BankPostedLanguage` precedence untouched: declined, released/reversed/cancelled,
  scheduled/future payment, authorized (incl. literal `*PEND`, `* PEND`, `*PENDING`,
  hold), and instruction/OTP/notice evidence always win over this upgrade.
- Money, currency, type, merchant and date extraction untouched: no currency
  rewrite, no completion guess from category/confidence; `bankEventDate` stays
  null when the source states no date (no receive-time fallback).
- Marker-less or foreign-currency payment amounts gain nothing; fee or second
  movement lines refuse certification instead of certifying a composite
  single-principal repayment.

## Edges

Heading case variants; Arabic variants incl. compact `بطاقة فيزا:سداد بـSR …`;
absent source date (stays null); missing and foreign currency markers; fee/second
amount lines; wrong sender; borrowed or forged template ids; unrelated
debit/transfer bodies.

## Acceptance

Dedicated JVM tests (`AlRajhiCardRepaymentEvidence062Test`) cover every family
above; positives fail on the pre-change code. One focused offline
`:ingestion:sms-parser:test` run also re-runs the adjacent registry, Al Rajhi and
Barq evidence suites to prove byte-equivalence of untouched clauses.

## Parent boundary correction (WM064)

Six actual assertion failures exposed same-line payment/balance fee exemptions,
case-sensitive secondary currency detection and the old card parser's foreign
currency loss (USD suffix became SAR; USD prefix fell back to INCOME). Require
whole-field matches, inspect all other monetary lines case-insensitively, and
preserve explicit prefix/suffix currencies in this one repayment template.
Foreign repayments remain uncertified; missing currency retains legacy parsing
but cannot enter the new path. Existing literal SR inputs must remain unchanged.
`CardRepaymentBoundary064Test` retains these six independent counterexamples.

Generic completion is not an alternative route around this family-specific gate.
Four further assertion failures (fee, missing currency, foreign currency and a
forged winning-template id) require downgrading only unsupported POSTED repayment
evidence to HEURISTIC_ONLY. Typed negatives and unrelated templates stay unchanged.
`CardRepaymentAuthority064Test` also retains the passing negative-precedence control.
