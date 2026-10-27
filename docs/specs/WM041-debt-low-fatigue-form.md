# WM041 — lower-fatigue debt comparison

## Surface and outcome

Operate mode. Refine the existing Plan → Bills → repayment comparison, not its financial model. Users should enter actual essential terms, compare once, and see the result without scrolling back through a long form. The incumbent native Arabic screenshot is retained in build/private-results/wealth-039-resume-oct25/debt041-baseline-form.png.

## Preserved truth and ownership

DebtScenarioViewModel remains the sole financial state owner. Drafts, exact strings, currency-reset behavior, supported nominal-monthly terms, explicit fixed monthly fees, rounding, comparison engines, generation fencing, saved-document identities and CAS revisions are unchanged. Fees are REQUIRED by the real validator, even when zero: they remain visible, never silently defaulted or hidden as optional. Currency, first full-cycle due date, budget, loan identity, principal, rate and minimum remain visible.

## Implementation

Move only the existing comparison horizon and explanatory rounding detail into an optional disclosure after the loan inputs. Always show the retained horizon in its closed summary; opening/closing never edits a field. A horizon validation error forces and retains expansion until corrected. Use existing shared components,48dp controls, tokens and bilingual resources.

After a successful existing compute/load, show results first with a short conditional-estimate scope note and an explicit Edit inputs action. That action reveals the same draft, not a copy. Existing parent scroll-to-top callback makes the result or reopened editor visible; keyboard clearing stays at the original compute boundary. Invalid computation leaves the editor visible and cannot resurrect old results. Saved-plan actions and all schedule/back behavior remain reachable.

## Acceptance

Pure presentation tests cover result/editor and hidden-error visibility and unchanged exact draft/codec behavior, including explicit fees. Build the integrated Plan scope and both unseeded debug flavors. On the owned emulator verify advanced collapse/reopen, correction of a hidden invalid horizon, keyboard-open compute, automatic result visibility, saved load/edit/recompute and original data preservation. No full debt capability, phone, release, accessibility or process-death acceptance is implied.
