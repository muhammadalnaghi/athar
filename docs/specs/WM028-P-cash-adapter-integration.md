# WM028-P — Cash draft adapter integration

## Goal and scope

Integrate the isolated manual cash adapter into the parent-owned temporary scenario flow described by `WM028-P-usable-scenario-flows.md`. Preserve the existing cash calculator, encrypted history, financial authority, literal currencies, Plan navigation, and the accepted result renderer. No database writes, schema changes, automatic forecasts, ingestion activation, cloud requests, source publication, or phone operations are authorized by this integration.

## Inputs and behavior

Inputs are a literal currency, dated signed opening cash, an optional known reserve, an end date, and at most twenty-four positively stated inflow/outflow magnitudes with dates strictly after the end-of-day opening anchor. Reserve omission means unknown, never zero. Complete-scope and trustworthy-opening attestations are unchecked by default. All movements remain expected scenario inputs, not observed financial records. Results and paged full-path details must come from the accepted calculator. Editing, clearing, changing currency, and late calculation completion cannot leave a stale result visible. A currency change clears the draft rather than converting amounts.

## Integration review

The worker delivered adapter and twelve tests as authored, not executed. Its source bindings and destination absence must be checked before transfer. Parent review found two Java-style `isAfter` calls on kotlinx-datetime dates, a test helper accepting a result while callers supply drafts, and default fixture cash of 1000 while three expected examples describe 100. Repair these narrow integration defects explicitly: use the existing Comparable date contract, evaluate the draft inside the helper, and make the example opening cash 100. Independently verify arithmetic rather than weakening expected results. Add negative-reserve, JPY, signed-opening, and horizon-boundary coverage.

## Acceptance

Run current-source Plan tests and both unseeded debug flavor builds, retain prior test identities, and bind every result to actual source hashes. The full path must include opening and all dated calculator points with real pagination and row provenance. Exercise Arabic native entry, incomplete and complete assumptions, significant three-decimal money, open-keyboard compute, detail navigation, and existing synthetic-history retention on the owned disposable AVD. Build acceptance alone does not establish native usability or complete WM-10 acceptance.
