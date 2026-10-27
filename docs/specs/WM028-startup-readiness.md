# WM028 — startup preference readiness

## Goal

Returning users must reach their retained dashboard without seeing onboarding or a financial screen using temporary preferences. This is a scoped startup/navigation defect repair inside the incumbent native visual system, not a redesign or full-product acceptance.

## Observed boundary

The controller launched the exact installed0177 synthetic QA APK on AtharHermesQaApi35. Android reported a startup wait timeout; the first screenshot showed onboarding page one. Without any tap, a subsequent hierarchy showed Today with both retained expense records. Therefore the observation does not establish missing data. Source inspection found onboarding state initialized to false while its asynchronous preference read was pending; currency and Hijri flows likewise exposed plausible defaults before reading persisted settings.

## Implementation

Represent each not-yet-loaded startup preference explicitly with a null initial StateFlow value. The root takes immutable snapshots and renders only a branded parchment loading surface until all three real values exist. Do not construct the navigation graph, tabs, onboarding or monetary destinations while that state is unresolved. Select the initial destination only once after readiness; subsequent preference changes must not rebuild the graph or destroy nested navigation. Actual false still means first-run onboarding; actual true means the existing dashboard. Existing completion callbacks and top-level restoration semantics remain intact.

## Acceptance and limits

Add focused state-holder regressions covering unresolved versus resolved false/true and actual foreign currency/Hijri preferences, with deterministic dispatcher control and lifecycle cleanup. Compile/build an unseeded candidate, then perform a real cold launch, first-run/returning-route checks, tab and Settings/back navigation on the owned emulator. Preserve records and identity without clear/uninstall. Retain the original timeout and screenshots. Eliminating the wrong-screen flash does not by itself prove that the entire startup latency is fixed; measure it separately. Parent owns source integration and all device operations.
