# WM031 — Barq completed-card-purchase posting evidence (narrow)

## Goal

Barq's two already-validated completed-card-purchase templates produce `Success` results whose
posting evidence lands as `HEURISTIC_ONLY` because the generic `BankPostedLanguage.completed`
pattern misses Barq's exact bank headings:

- `Online Purchases:` — plural heading; `purchase\b` cannot match `Purchases`.
- `POS International Purchase` — heading carries two prefix tokens (`POS International`) while the
  generic alternation permits at most one (`online|pos|international|local`).

A completed Barq card purchase is explicit posted language and must be recorded as
`PostingEvidence(POSTED, EXPLICIT_POSTED)`.

## Scope (strictly narrow)

- One new enrichment file: `barq/BarqCompletedPurchaseEvidence.kt`. Keyed on `templateId`
  (`barq-online-purchase`, `barq-pos-international`) **and** the bank heading being present in the
  body. Any other template id, or the heading absent, returns `null` → caller falls back to the
  untouched generic evidence. A generic `Success`, transfer/ATM/notice body therefore never gains
  payment authority from this path.
- Minimal hook in `TemplateRegistry.kt` (`TemplateBasedSmsParser` Success branch): consult the
  enrichment first, fall back to `BankPostedLanguage.evidence`.
- No change to `PostedEvidence.kt` precedence, no core/app changes, no new templates, no registry
  list changes (both templates are already registered).

## Preservation contract

- Parsed `amount` stays the **SAR settlement** extraction (e.g. `6 CNY (3.14 SAR)` → `3.14`,
  `45.00 USD (168.75 SAR)` → `168.75`); foreign amount never wins.
- `merchant`, `type`, `occurredAt`, `templateId` unchanged.
- `bankEventDate` remains source-stated dates only; a body without any date never becomes the
  receive-time instant.

## Contradiction precedence (unchanged, must win)

`declined → released → scheduled → authorized → instruction` ordering in `BankPostedLanguage` is
reused verbatim. The enrichment defers to it first and only upgrades a heuristic `POSTED` result,
so `*PEND`, `* PEND`, `*PENDING` under a purchase heading still yield
`AUTHORIZED / HEURISTIC_ONLY`, and declined/released/scheduled/instruction bodies never upgrade.

## Inputs / outputs

- In: `RawIngestEvent` (sender `Barq`, synthetic public bodies), parsed `ParseResult.Success`.
- Out: `posting` evidence upgraded for the two positives; everything else byte-identical behavior.

## Edges covered by tests

1. Positive format 1: `Online Purchases:` body → `EXPLICIT_POSTED`.
2. Positive format 2: `POS International Purchase` body → `EXPLICIT_POSTED`.
3. Mixed currency: SAR settlement extracted (`AED`/`CNY`/`USD` wrapped values).
4. Missing source date: `bankEventDate == null` with a live `receivedAt`.
5. Negatives: `*PEND` / `* PEND` / `*PENDING` descriptors, declined, released under the headings →
   contradiction states, never upgraded.
6. Generic controls: ATM withdrawal, debit transfer, and a non-Barq template id with a Barq-shaped
   body all stay `HEURISTIC_ONLY`.

## Acceptance

- RED: new `BarqCompletedPurchaseEvidenceTest` fails on the two positive assertions before the fix.
- GREEN: same test passes after the enrichment + hook, and the full `:ingestion:sms-parser:test`
  suite stays green.
- No private inputs, no emulator, no git writes.
