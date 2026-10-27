# WM031: Non-payment notices and international purchase money

## Goal and diagnosis

Prevent administrative numbers from becoming expenses and prevent a recognized international card receipt from being handled by a generic amount-in-SAR fallback. This is prospective parser correctness, not permission to rewrite existing history or activate automatic recording. The private intake remains parent-local; regression fixtures contain public bank grammar and wholly synthetic amounts, dates and merchants.

## Non-payment boundary

An English monthly credit-card statement with total/minimum amounts due and a due-date is an obligation notice, not a completed payment. A hyphenated one-time-password service request containing a code but no booked movement is not money. Recognize these narrowly through the existing first-priority known-bank ignore boundary. Preserve genuine completed-payment receipts, including receipts with explanatory statement text or security footers. Ignored messages remain in the encrypted SMS audit through the existing ingestion path; this does not implement a bills adapter or erase audit evidence.

## International purchase boundary

Recognize the existing Al Rajhi PoS receipt's International heading in the bank-specific purchase parser. Use the explicit SAR settlement rather than treating the leading foreign amount as SAR. When the receipt supplies a total-due amount inclusive of fees/VAT, use that explicit booked total exactly once, without separately adding its component amounts or computing an estimated exchange rate. A foreign-only amount must retain its literal currency, never inherit SAR merely because the sender is Saudi. Keep the merchant, source-stated date, held-descriptor precedence and missing-source-date behavior. Do not grant generic templates new completed-payment authority.

## Linked STC counterexamples

The independent linked-source money oracle additionally exposed four failures behind the same Online Purchase heading: STC Bank's colon-form amount and explicit SAR total were falling through the legacy Transaction-only header; STC Pay's card number was reaching the universal numeric fallback. Extend the existing bank template without removing legacy syntax, and add a distinct wallet template before the universal fallback. Reuse the same exact declared-money reader. Keep source identities separate; a wallet template is not silently granted the bank adapter's certification whitelist.

## Acceptance and exclusions

Execute synthetic assertion RED before the production repair, then the complete parser suite with all prior testcase identities retained. Rerun the authorized archive and independently reconcile changed populations and changed purchase amounts against linked source fields, without printing source bodies or amounts. The old successful-parse count is not an independent financial-truth oracle; preserve its report and explicitly bind any new expected count to demonstrated notice reclassification. Native ingestion, query visibility, repeat delivery and authenticated baseline restore remain required before accepting a new installed candidate. No schema change, migration, historical deletion, network service, new dependency, phone operation or publication is part of this repair.
