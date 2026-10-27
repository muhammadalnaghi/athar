# WM033 — Prospective Automatic Recording Controls (Settings)

## Goal

Give the user a real, user-facing Settings control for the existing prospective
automatic recording capability ("certified purchase recording"), wired to the
long-standing `FinancialRecordingRollout` flag and the canonical purchase path it
already gates (`FinancialRecordingAdapter.beginSessionIfEnabled()` →
`CanonicalPurchaseRecorder`). The control ships **off**; activation happens only
after parent emulator acceptance. No ingestion, parser, store, or schema changes.

## Inputs

- Existing `FinancialRecordingRollout` (app module, SharedPreferences
  `certified_purchase_rollout/enabled`, default `false`, synchronous
  `isEnabled()` used on the ingest path).
- Existing pure evidence engines (`FinancialRecordingPolicy`,
  `FinancialRecordingDecisions`): only clearly evidenced **completed**
  (POSTED, explicit-support) purchases are ever recorded; holds/declines and
  heuristic money are never recorded. This task changes no decision logic.

## Outputs

- `core/domain/repo/FinancialRecordingPreferences.kt` — new narrow interface
  (`Flow<Boolean>` observe + suspend write) so feature/settings never depends on
  the app module.
- `FinancialRecordingRollout` implements the interface; its existing
  `isEnabled()`/`setEnabled()` semantics are untouched.
- New recording-only Hilt binding (`app/di/FinancialRecordingPreferencesModule.kt`).
- Settings state + control: `recordingEnabled` StateFlow, setter with explicit
  OFF precedence; Arabic-first AtharCard toggle matching the established
  Settings card pattern, ≥48dp touch target, no new accent.
- Focused unit tests (state default-off, ON, explicit OFF persistence) in
  `:feature:settings`.

## Edges

- User's explicit OFF always wins: the setter persists exactly what the user
  chose; nothing re-enables implicitly, and no write path bypasses the control.
- New events only: the flag gates new ingest sessions; toggling never
  backfills, mass-confirms, or rewrites existing data.
- Recording never grants category authority and never alters provider settings.
- A failed preference write leaves the previous persisted value and UI state.

## Acceptance

1. Control visible in Settings, Arabic-first copy, default OFF on fresh install.
2. Toggling ON persists and flips the rollout flag; toggling OFF persists OFF
   and is preserved across restart.
3. The canonical purchase path observes the same flag (no second source of truth).
4. Focused `:feature:settings` unit tests pass via the shared gate
   (`rec033`), including the updated test fake for the new dependency.
5. Existing Settings behavior unchanged; no activation before parent acceptance.
