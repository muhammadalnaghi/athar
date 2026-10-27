# WM036 — native widget financial parity

## Goal
Make existing Today and Monthly Android widgets useful, exact, and truthful. Widgets read the same confirmed operating records and literal display currency as the app. A saved dated position is a separate user-declared snapshot, never a verified live balance or a sum of account history. This scope repairs current widget projection/layout; it does not introduce network access, a cache engine, or broader financial authority.

## Inputs and outputs
Read current SQLCipher-backed transaction and declared-position repositories through the existing Hilt widget entry point. Reuse latestDeclaredPosition selection. Show exact grouped localized money with literal ISO currency, income/expense/net for the selected scope, and selected dated declared net separately when present. Missing positions remain missing. Loading, unavailable and known zero are distinct. Cancellation propagates. Keep receivers and launch actions.

## Edges
Exclude foreign-currency, pending, transfer and reconciliation rows from operating sums. Never reinterpret unlike currencies at1:1. Never call raw account book values whole net worth. Retain malformed saved inputs without changes. Use a single-column constrained layout, no three tight large-money pills. No raw financial cache outside Room is added. Existing system RemoteViews display is user-opted via widget placement.

## Acceptance
Focused pure currency/selection/load-state tests pass; both debug flavors build. Parent adds widgets to the owned visible AVD, checks actual full money/date/locale and opens the app through a widget. Revision-wide encrypted last-good caching, all sizes/accessibility and full WM22 remain separately open; do not claim them from this slice.
