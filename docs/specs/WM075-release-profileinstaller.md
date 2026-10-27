# WM075 — Release baseline-profile installation (profileinstaller)

**Goal.** The remaining cold-start floor is class-loading/JIT. Debug builds (what
the emulator QA chain installs) never carry baseline profiles, but the user's
real install path is the **release** APK via Obtainium. Compose UI 1.7.6 ships
`baseline.prof` inside its AAR (verified in the offline Gradle cache); AGP merges
library profiles into release artifacts, and `androidx.profileinstaller` is what
installs/verifies them on device so ART AOT-compiles the hot startup path.
Athar had no profileinstaller dependency — merged profiles were not being
reliably installed.

**Change.** Add `androidx.profileinstaller:profileinstaller:1.3.1` (present in
the offline cache) to the version catalog + app. One runtime dependency; no code.
Applies to both flavors; debug QA chain unaffected (debug never bundles profiles).

**Measurement (A/B, owned API35 emulator, release package `com.athar.personal`
installed side-by-side, first launch settled, then force-stop + 4 s + `am start
-W` twice).**
- Baseline: release build of the current worktree (product23 sources) without
  profileinstaller.
- After: same sources + profileinstaller.
- Emulator absolute numbers overstate device costs (software rendering); the A/B
  delta and the on-device installation guarantee are the claim, not phone SLAs.

**Not claimed.** No R8/minification change (release keeps symbols by recorded
alpha decision — a separate, riskier lane). No benchmark-module-generated
app-specific profile; library profiles only.

**Acceptance.** Release A/B measured on the emulator; profileinstaller verified
in the release APK's dex; both debug QA gates untouched; safe-signed-update chain
of the debug QA package unaffected (different applicationId).
