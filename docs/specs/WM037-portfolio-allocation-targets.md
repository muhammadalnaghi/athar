# WM037 — saved portfolio allocation targets

## Goal
Expose the existing reviewed allocation engine and already persisted TargetInput fields through a reachable portfolio-local editor. This replaces spreadsheet target-allocation maintenance with explicit save, reopen, cancel and invalid-input recovery. It is a planning choice, never a trade instruction, verified underlying fund exposure or whole-wealth allocation.

## Inputs and outputs
Open one retained securities portfolio from Plan→Investments. Edit point target percentages for existing asset classes, preserve stored range targets and unchanged literal fractions, and explicitly clear all targets only through a named Clear action. Validate bounded exact decimals and all-point sum100% through the existing engine. Save only allocationTargets in the existing versioned document, retaining every financial event/security/date/price/fee literal, ID and list order. Reuse the repository revision/CAS boundary.

## Recovery and concurrency
A synchronous submission fence rejects repeated saves. Bind completion/error to the captured plan, revision and request token; an old response cannot close a newer editor. Validation, conflict and storage errors keep the entered values and usable retry/cancel controls. Cancel writes nothing. Range targets remain visibly read-only rather than silently becoming point targets.

## Acceptance
Focused rules and persistence tests exercise exact totals, unsupported/negative inputs, unchanged literals, cancellation, conflict and repeated saves. Parent verifies input→explicit Save→immediate visible actual/target/drift→reopen and cancel/invalid behavior on the owned AVD, with authenticated restoration of its original baseline. No module/schema/backup/codec/version change or automatic trading. Benchmarks, underlying ETF look-through and executable/contribution rebalancing are not claimed by this target-editor slice.
