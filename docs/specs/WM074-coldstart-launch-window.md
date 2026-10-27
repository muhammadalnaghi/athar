# WM074 — Cold-start launch-window DB contention

**Goal.** Remove work that competes with first composition during cold start.
Measured on product22 (owned API35, swiftshader): `am start -W` COLD TotalTime
6766 ms; logcat shows the activity window created at +2.8 s but **first frame at
+6.8 s** (Davey 3835 ms, 206 skipped frames). The gap is DB contention, not
class loading: WorkManager boot reschedule + an eager `RecurringMaterializeWorker`
+ two SQLCipher JNI critical-lock stalls (407/379 ms) + a RuleSeed no-op check
that loads all 729 rules and decodes `seed_rules.json` every boot (777 ms).

**Changes (behavior-preserving).**
1. `RecurringWorkScheduler`: the one-time startup materialize pass gets a 20 s
   initial delay (was immediate). Due recurring instances materialize 20 s after
   process start instead of during launch; the 24 h periodic pass is unchanged.
2. `RuleSeed.seedIfEmpty()`: steady-state marker skip — when the bundled payload
   SHA-256 matches the last completed seed marker AND `countSystemRules()` is
   unchanged, skip decoding the payload and loading all rows. Fresh install,
   bundle change, or seed-row tampering breaks the marker and takes the existing
   full verification path; the marker is written after every completed seed.
   `seedRulesNeedRefresh` and its tests are untouched.

**Not claimed.** Emulator class-loading floor (~3 s of the 6.8 s) is device
overhead; no phone/SLA claim. WorkManager force-stop rescheduling churn is a
benchmark artifact (force-stop before each COLD run).

**Acceptance.** core:data + app unit suites green; gated product23 build; COLD
`am start -W` measured before/after on the same emulator with the same
force-stop procedure; first-frame gap in logcat (Displayed minus window-create)
shrinks; recurring materialization still observed completing post-launch.
