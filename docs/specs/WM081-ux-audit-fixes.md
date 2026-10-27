# WM081 — Full page-per-page UX audit + fix pass

## Goal
Systematic UX audit of every Arabic page (18 captures), then fix all confirmed findings as product26.

## Inputs
- Live QA app (product25) on owned AVD `AtharHermesQaApi35`, Arabic locale, user's real baseline data.
- Mechanical analysis of all uiautomator trees: touch targets (≥48dp), TalkBack labels (subtree text), Arabic copy patterns (ux-araby), numeral discipline.
- impeccable craft-floor + audit.native + android references; Athar KMP Compose skill.

## Outputs
- Audit report (14.5/20) + product26: 8 files changed, 587 fresh tests green, on-device acceptance PASS.
- Receipt: `build/qa/wealth-068/product26-native-scoped-acceptance.json`.

## Edges
- uiautomator reports scrollable containers as clickable leaf Views → verified each "unlabeled" claim against XML tree structure before fixing (3 of 5 downgraded as artifacts).
- uiautomator self-closing-node regex misses container parents → parse with ElementTree for semantic checks.
- ANR observed once under rapid scripted interaction (trace: main busy in LazyLayout measure). Not reproducible in 2 bounded attempts; monitored for real-device signal, not "fixed" blind.

## Acceptance
On-device: Bills Arabic-Indic numerals (٣٠ يومًا, calendar chips, horizon date, zero ASCII leaks), Arabic ٪ in Goals, labeled checkable compare toggle 48dp, all segmented chips exactly 48dp, copy updates live (إدخال صوتي, نعيد تحميل الشاشة تلقائيًا, إكسل). Recording pref remains disabled post-acceptance.
