# WM054 — Optional opening-capital date in portfolio creation

## Surface and outcome

Operate mode, within Plan → Investments → Create portfolio. Let the user explicitly date opening capital once, rather than inventing a date from a later trade. Preserve Athar's existing parchment/ink/ember interface and exactly three primary destinations. This is a bounded extension of WM052, not a portfolio redesign or full WM-12 acceptance.

## Inputs, ownership and persistence

Add an optional ISO opening-capital date beside the existing opening-cash input. Leave it blank by default; never insert today's date or infer the first transaction date. Store only the supplied date and existing exact input values, using the feature-owned portfolio document and existing encrypted SavedFinancialPlan repository. The optional field is an additive version-1 input, like the existing optional allocation targets; unchanged legacy documents must encode exactly as before. Non-string, invalid and impossible dates must be refused, not discarded. Other unknown fields and future versions remain retained and unloadable.

## Pipeline and edges

The existing CreatePortfolio event gains a default-blank date input. Its ViewModel validates before saving and forwards the stored date through PortfolioWorkbench to the reviewed ledger and returns adapter. A dated opening amount is one external investment, not an extra cash deposit or duplicated gain. A date after the first ledger event is invalid. No opening date keeps the existing unavailable time-based return. No per-flow valuation evidence means no invented TWR. Statement import, correction, allocation, family pools, original portfolio revisions and transaction history must remain unchanged.

## Acceptance

Cover strict date codec round trips, malformed date types and legacy omission; creation/reopen, invalid-input no-write and preservation through statement preview/apply. Build both unseeded debug flavors, retain prior test identities, and perform a single owned-emulator input → explicit create → statement preview/apply → process restart → reopen flow. Inspect Arabic copy and monetary/date output visually, cancel an unsaved draft and remove only the new synthetic portfolio. Keep production recording off. Full physical-phone, performance, TWR and all-capability acceptance remain separate.
