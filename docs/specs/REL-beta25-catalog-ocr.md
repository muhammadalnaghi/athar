# Release beta.25 — signed catalog, personal provenance and offline OCR

## Goal

Deliver the locally accepted Plan001/Plan002 source as a signed, unseeded Android update that existing personalFullSms users can obtain through the existing GitHub/Obtainium channel. The user explicitly authorized integration, source push, app build and update publication. This supersedes the earlier no-commit/no-release boundary for this release only; it does not authorize private-data uploads, signing-key replacement or merchant-model integration.

## Inputs and outputs

Use the reviewed isolated worktree at baseline `65c4ad4bbfea8e4f470e018a0b754d372ad00e89`, final acceptance evidence and the original checkout's existing ignored release-signing configuration. Public latest beta.23 has package `com.athar.personal`, versionCode 9, and APK SHA256 `859123f02a742d611bc5d1757039fd51b5dcb6ef09176b3869fcfb0ec4132c6b`; the retained original APK is byte-identical. The beta.24 source recovery is already on main but its version metadata was not advanced. Candidate beta.25 advances versionCode to 10 and versionName to `0.1.0-beta.25-personal`.

Build with `-Pathar.seed=false`. Keep the same application ID and existing release certificate. Publish the exact verified signed APK under the established asset name `app-personalFullSms-release.apk`, using a normal latest GitHub release so default Obtainium discovery can see it. Financial data remains encrypted on device; public signed merchant knowledge stays on the independent catalog branch. Contributions remain deliberate manual export/public issue review; warn that issue authors and attachments are public and private merchant names must be removed before posting.

## Acceptance and edges

Reconcile remote main/tag/releases before pushing; never force-push. Verify package, increasing versionCode, signing-certificate continuity, APK signature, absent private seed assets, exact bundled OCR assets, compiled permissions and full unit/build gates on integrated source. Preserve existing user data and all Room upgrade paths; exercise an actual old-public-to-new release update on a disposable QA AVD where possible. Do not install on or clear the user's phone. Read back remote main/tag/release and asset metadata after publication, then download/hash-check the exact uploaded APK before claiming Obtainium availability.

If GitHub network access prevents publishing, report that blocker honestly and retain the signed local artifact/evidence; a local tag or draft is not a published update. No completed merchant AI, Arabic/real-photo OCR quality, or zero-warning repository claim is permitted.
