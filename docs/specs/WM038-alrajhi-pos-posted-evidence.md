# WM038 — Al Rajhi PoS International purchase template-context posted evidence

## Goal

Upgrade the posting support of the already-validated Al Rajhi real PoS International
purchase template (`al-rajhi-pos-purchase-real`, heading `PoS International purchase`)
from `POSTED/HEURISTIC_ONLY` to `POSTED/EXPLICIT_POSTED`, using the same reviewed
template-specific enrichment pattern as WM031 (Barq `validatedCardTemplate`), narrowly
scoped to this one heading and template.

## Root cause

`BankPostedLanguage.evidence` upgrades to `EXPLICIT_POSTED` on a completed-verb heading
or a validated card template. The generic completed-verb regex never spans the two-word
heading `PoS International purchase` (its `pos\s+purchase` prefix requires `purchase`
directly after `PoS `), so every real international PoS purchase stays heuristic even
though the bank template's exact heading proved the purchase posted. Barq solved the
same gap in WM031 with a registry-supplied `validatedCardTemplate` flag; Al Rajhi was
not covered.

## Inputs

- Body text of an Al Rajhi sender event already parsed successfully by
  `AlRajhiPosPurchaseRealTemplate`.
- The template's own normalized body (`Normalize.digits`) — the same text the template
  matched its header against.

## Outputs

- New `AlRajhiPostedEvidence` (package `com.athar.ingestion.smsparser.alrajhi`): a
  single pure predicate, true only when the body carries the exact
  `PoS International purchase` heading (case-insensitive, multiline-anchored).
- One minimal hook in `TemplateRegistry.kt`: `validatedCardTemplate` additionally true
  when the winning template is `AlRajhiPosPurchaseRealTemplate` **and** the gate passes.

## Invariants (unchanged code, unchanged behavior)

- `PostedEvidence.kt` / `BankPostedLanguage` precedence is untouched: declined,
  released (incl. reversed/cancelled/refund), scheduled, authorized (incl. literal
  `*PEND`, `* PEND`, `*PENDING`, `pending`, `hold`), and instruction/notice evidence
  always win over the template-context upgrade.
- Money, date, merchant extraction untouched: settlement stays the SAR-in-parens
  value, foreign literal currency is never normalized, `bankEventDate` stays null when
  the source states no date (no receive-time fallback), settlement is never
  manufactured.
- Category and confidence never grant authority: the gate derives only from the
  template class identity plus the heading text.
- Wrong template, wrong institution, generic `Success` (e.g. universal-amount),
  transfer/ATM/notice templates, and the domestic `PoS purchase` heading gain nothing
  new from this change (the domestic heading already carries public completed-verb
  evidence and must stay byte-identical).

## Edges

- Heading case variants (`POS INTERNATIONAL PURCHASE`) are the same heading.
- Mixed principal/settlement (`129.00AED(131.87 SAR)`) and foreign-only (`5.25 USD`)
  both post explicitly with literal stored money.
- Any contradictory token anywhere in the raw body keeps `HEURISTIC_ONLY` and its
  contradiction state.

## Acceptance

Dedicated JVM tests (`AlRajhiPostedEvidenceTest`) cover: two positive heading case
variants; mixed principal/settlement; absent source date; every contradiction family;
`*PEND` / `* PEND` / `*PENDING` under the positive heading and in raw display
evidence; OTP ignored at registry level; transfer and universal-amount generic
controls; wrong institution and wrong/forged template controls; domestic-heading
narrow-scope control. Positive tests fail on the pre-change code (assert
`EXPLICIT_POSTED`) and pass only with the new file plus the registry hook. Status:
code delivered, NOT executed in this lane (no Gradle invocation permitted here);
the parent owns the reversible RED/GREEN run.
