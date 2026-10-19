# ADR-009: Update delivery via Obtainium delegation, not in-app polling

- **Status:** Accepted
- **Date:** 2026-05-27
- **Supersedes:** —
- **Superseded by:** —

## Context

Through beta.22 the user testing Athar as their daily driver had no in-app indication that a new release was available. Each new beta required them to either remember to check `github.com/wa1939/athar/releases` manually or to have already heard about it elsewhere. With releases shipping at a rate of one to three per day in the late-Tier-1 / patch series (beta.11 → beta.22) and users explicitly asking for an in-app nudge, this became a real friction point — but the obvious solution (add `INTERNET` permission, poll the GitHub releases API on launch) directly contradicts Athar's foundational privacy commitment.

## The constraints

Athar's `app/src/main/AndroidManifest.xml` declares **no `android.permission.INTERNET`** and has never declared one. This isn't an oversight; it is documented in Master Brief §2.2 as one of the three things Athar will never do:

> 1. Sell, share, or transmit financial data without explicit user action.

…and reinforced in the README's "Privacy" section. The absence of `INTERNET` in the manifest is a *verifiable* claim: anyone can decompile a released APK with `aapt dump permissions` and see for themselves that the app is incapable of making outbound network calls, regardless of what the app's UI claims. Adding `INTERNET` — even for the most innocent reason — destroys that verifiability for every future release. The user can no longer prove to themselves that Athar isn't quietly exfiltrating their transaction history.

Per the brief's own escalation rule ("the brief and reality must agree — edit the brief first, then the code"), if we wanted to add `INTERNET` we would first need to amend §2.2. That was the explicit decision point of this ADR.

## Decision

**Do not add `INTERNET` permission. Delegate update tracking to the user's chosen sideload manager (default: Obtainium) via a deep-link intent.**

Concretely:

1. Settings exposes a card titled "تابع التحديثات / Stay up to date" with three actions:
   - **"Add to Obtainium"** — opens `obtainium://app/{percent-encoded-config}` where the config JSON points at `https://github.com/wa1939/athar`. Obtainium parses the deep link, pre-fills its "Add app" form, the user taps Confirm once, and from then on Obtainium is responsible for polling the releases page and notifying the user when a new tag ships.
   - **"Install Obtainium"** — opens `https://obtainium.imranr.dev` in the user's default browser. Used as the fallback path when the deep-link intent has no handler (Obtainium isn't installed).
   - **"Releases page"** — opens `https://github.com/wa1939/athar/releases` for users who want a fully manual workflow.
2. The fallback chain in `feature/settings/.../SettingsScreen.kt::launchUrl` is:
   ```kotlin
   val ok = launchUrl(context, OBTAINIUM_DEEP_LINK)
   if (!ok) {
       Toast.makeText(context, "Obtainium isn't installed — opening its site", LONG).show()
       launchUrl(context, OBTAINIUM_INSTALL_URL)
   }
   ```
   `launchUrl` is a `runCatching` wrapper that returns `true` iff `startActivity` succeeded. The `ActivityNotFoundException` thrown by the platform when no app handles `obtainium://` becomes a clean `false`, which the caller routes to the install-page fallback.
3. **No Athar process ever makes a network call.** All polling, comparison, and notification logic runs inside Obtainium, in Obtainium's process, under Obtainium's permissions. The `aapt dump permissions` audit on a beta.23 APK is identical to a beta.22 APK.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **In-app HTTP poll of `api.github.com/repos/wa1939/athar/releases/latest`** | Requires `INTERNET`. Permanently breaks the offline-only verifiability of every future release. Master Brief §2.2 amendment would be required and was rejected. |
| **Bundle the latest known version in `BuildConfig` and compare to installed** | Tells the user nothing — they're always running exactly the version that knows itself as latest. The only way to learn about a *new* version is still to look outside the app. |
| **Firebase Cloud Messaging push** | Requires a backend, an account, a notification token. Massive surveillance surface added to a single-purpose feature. Hard no. |
| **In-app browser embedded view of the releases page** | Still requires `INTERNET`. Adds WebView surface area. Same trade-off as the GitHub API poll. |
| **Ship a `WorkManager` job that uses a custom DNS-over-TLS resolver** | Cute, still requires `INTERNET`, same trade-off. |
| **No update mechanism at all** | The user asked for one and gave a concrete reason ("how will I know when there's a new build?"). Doing nothing is also a choice but the worse one. |

The Obtainium delegation pattern wasn't invented for Athar — it's the standard mechanism in the privacy-respecting Android sideload ecosystem (used by every F-Droid front-end, by Obtainium itself for self-update, by FFUpdater, by the IzzyOnDroid clients). Athar adopting it is just plugging into existing infrastructure that the target audience already trusts.

## Consequences

**Positive:**
- Master Brief §2.2 stays intact. The "no INTERNET" claim is still externally verifiable for every release.
- Zero net new code in Athar's data layer; zero new modules; zero new dependencies. The entire feature is one Compose card + a deep-link launcher with a try/catch.
- Update-tracking responsibility lives in the user's sideload manager forever. We never have to worry about GitHub API rate limits, token rotation, breaking-change responses, or a malicious mirror.
- For the ~all users who reach Athar via Obtainium in the first place (the recommended install path documented in every release's notes), the "Add to Obtainium" tap is a no-op — they're already subscribed. The card is essentially educational.

**Negative:**
- Users who refuse to install Obtainium have a slightly worse experience: they must manually visit the releases page. The "Releases page" button + browser bookmark covers this; it's not worse than every other sideloaded-only app on the platform.
- We rely on Obtainium continuing to support the `obtainium://app/` URI scheme. If Obtainium ever ships a breaking change to that scheme we'd need to update the deep-link payload. Risk: low — the scheme is stable, documented in Obtainium's README, and used by many client tools.

**Neutral:**
- We don't get usage analytics for free (we wouldn't anyway, since we don't ship telemetry).

## Validation

End-to-end test on a Pixel 6 / Android 14 emulator without Obtainium installed (beta.23 verification, `test-screens-beta22/11_tap_y600.png`):

1. Settings → tap "Add to Obtainium".
2. `adb logcat` records `ActivityTaskManager: START dat=obtainium://app/... result code=-91` (`ActivityNotFoundException`).
3. Athar's `runCatching` returns `false`; the Toast `"Obtainium isn't installed — opening its site so you can grab it"` is shown.
4. `launchUrl(OBTAINIUM_INSTALL_URL)` succeeds; Chrome opens `obtainium.imranr.dev`.

For users who already have Obtainium, the same tap dispatches directly into Obtainium's "Add app" form pre-filled with Athar's GitHub URL — no toast, no Chrome detour.

## References

- `feature/settings/src/main/kotlin/com/athar/feature/settings/SettingsScreen.kt::UpdatesCard` — implementation.
- `Athar_Master_Brief.md` §2.2 — "Three things Athar will never do".
- README "Privacy" section — externally-facing version of the same commitment.
- `https://github.com/ImranR98/Obtainium/wiki` — Obtainium's deep-link scheme reference.

## Amendment — 2026-10-16 — scoped catalog networking does not touch update delivery

The 2026-10-16 merchant-catalog amendment (Master Brief §2.2, ADR-002) supersedes this ADR's blanket "no `INTERNET` ever" premise **only** for a personalFullSms opt-in download of a signed, data-only public merchant catalog from a fixed raw-GitHub endpoint on the separate `merchant-catalog` branch. This ADR's decision is otherwise fully preserved: Obtainium remains the only **app-update** channel; releases still tag `main`; the catalog branch is data-only and is never read as an update feed, never published as a release asset, and never polled on a schedule. The catalog endpoint is versioned public data fetched by explicit/stale-gated foreground checks while opted in — not app-update polling. `INTERNET` is declared only in `app/src/personalFullSms/AndroidManifest.xml`; the storeSafe flavor stays verifiably network-free, and signature/key rotation still requires a trusted Obtainium-delivered app update.
