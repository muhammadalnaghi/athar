# WM076 — Release R8 minification

**Goal.** The release build shipped unminified since the alpha phase ("keep
symbols for debugging"). At beta.25 with an established emulator-verified QA
chain, debuggability no longer requires a fat release artifact, and the sideload
path (Obtainium) pays the APK size on every update.

**Change.** `app/build.gradle.kts` release block: `isMinifyEnabled = true`,
`isShrinkResources = true`. Debug build types untouched (QA chain unaffected).
No new proguard rules needed on first attempt — Hilt/Room/WorkManager/
kotlinx-serialization/SQLCipher consumer rules cover keeps; the only production
reflection in the repo is JAXP `DocumentBuilderFactory.newInstance()`
(platform-provided, R8-safe).

**Measured (owned API35, same procedure as WM075).**
- APK: **81.6 MB → 32.1 MB (−61 %)**.
- Release COLD: 2073/1909 ms → **1622/1829 ms**.
- On-device R8 smoke: permissions granted, onboarding paged, all three bottom
  destinations render content (ر.س present); rule seeding ran → kotlinx-
  serialization intact under R8; no crash. Screen visually confirmed via vision
  (parchment/ember design intact).

**Known residual risk.** Deep flows (backup/restore, widget pinning, CSV import)
were NOT re-run on the R8 build — bounded smoke only, per don't-overtest
discipline; the debug QA chain remains the acceptance vehicle. Release-side
regressions would surface in the next release-candidate acceptance.

**Artifacts.** `build/private-results/wealth-075-release-ab/{baseline-no-profileinstaller.apk,r8-minified.apk}`.
