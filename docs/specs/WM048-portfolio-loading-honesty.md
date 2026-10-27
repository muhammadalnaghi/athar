# WM048 — Honest portfolio loading state

## Goal and native counterexample
The Investments overview must not claim that no securities portfolio is saved while its initial financial query and document calculations are still pending. In the product10 native walk, a contribution/buy/quote import displayed the correct value and annual return after explicit Apply. Following an actual process restart, the overview briefly rendered the empty-portfolio explanation while its combined state had not completed. The captured hierarchy had no scrollable body, so an immediate seek also failed. That frame is a user-facing false-empty defect, not evidence that stored inputs were deleted.

## Cause and smallest fix
InvestmentsViewModel already exposes an initial isLoading state and changes it only after the combined portfolio/family calculation completes. The family empty explanation respects this flag; PortfolioSection did not. Reuse that same state at the presentation boundary. With no rows and initial loading, show a concise localized loading status. Show the existing empty explanation only after loading completes. If retained rows exist, keep displaying them without replacing their layout with a loading placeholder.

## Preserved behavior and exclusions
Keep portfolio create/open/delete callbacks, original row identity/revision, family pools, dated position ownership, summary-first opened detail and all finance calculations unchanged. Do not add another state holder, query, delay, database write or migration. This correction does not establish cold-start performance, a complete portfolio persistence walk, or full WM12 acceptance.

## Acceptance
Cover initial loading, genuinely loaded empty data, loaded rows, and retained rows during refresh through the production presentation selector. In the next combined native candidate, await actual row readiness before scrolling, then complete import/save/process-restart/reopen with exact independent financial expectations. Preserve earlier failed captures and authenticated baseline cleanup rather than replaying completed financial operations.
