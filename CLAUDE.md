# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product

**Athar (أثر)** — a local-first Android app that reads bank SMS, parses each transaction with a rules-first engine, auto-categorizes it, and replaces the user's existing Excel workbook (*The Measure of a Plan*) with three calm screens: **Today / Trends / Plan**. Saudi/SAR-first, Arabic-first, sideload-first, no cloud.

Single source of truth: `Athar_Master_Brief.md` v1.0. When the brief and reality disagree, **edit the brief first, then the code** (per its own instruction in §"How to use this document").

> Note: `qayd_android_budget_app_prd.md` is an earlier draft using the name "Qayd". The Master Brief supersedes it. Treat that file as historical context, not as spec.

Companion docs:
- `Athar_Backlog.md` — ticket-by-ticket execution order (Phases 0–5, ~12 weeks).
- `Budget Tracking Tool - TMOAP v5.xlsx` — the workbook Athar must reach feature parity with.
- `دليل جماليات خط ثمانية.pdf` — aesthetic guide for the Thmanyah typeface (see Typography below).
- `thmanyah typeface/` — font files (`thmanyahsans/`, `thmanyahserifdisplay/`, `thmanyahseriftext/`).

## Current repo state

Current version: **0.1.0-beta.23** (see `gradle.properties`). Tier 1 (G-1..G-14 from `docs/ROADMAP_GLOBAL.md`) is complete; the post-Tier-1 patch series (beta.11 → beta.23) is documented in the same roadmap and in `CHANGELOG.md`. Full multi-module Gradle project + signed APKs in GitHub releases.

Update delivery: Athar has no blanket `INTERNET` commitment anymore — since 2026-10-16 the **personalFullSms flavor only** declares `INTERNET` for one scoped, opt-in (off by default) capability: downloading a signed, data-only shared merchant catalog from a fixed public endpoint (see `Athar_Master_Brief.md` §2.2 amendment and `docs/adr/ADR-002`). The storeSafe flavor remains fully offline. App-update delivery is unchanged: new releases are tagged on `main` via `gh release create`; users are notified via **Obtainium**, which they subscribe to once from Settings → "تابع التحديثات / Stay up to date" (shipped beta.23). When working on update-related logic, never reach for HTTP polling — extend the Obtainium delegation pattern instead.

## MANDATORY: load a KMP skill before writing code

The `.claude/skills/` directory contains the [kotlin-kmp-claude-agent-skills](https://github.com/mmiani/kotlin-kmp-claude-agent-skills) catalog (15 skills). You **MUST** invoke the matching skill via the `Skill` tool before editing any Kotlin/Compose/Gradle/Android file in this folder. Note: the catalog is written for Kotlin Multiplatform; Athar is Android-only in v1, but every skill's architecture/state/test/Compose guidance still applies — treat KMP-specific source-set advice as "everything goes in `androidMain` equivalent (i.e., the single Android target) unless/until iOS is added in a future major version."

### Skill routing

| Task | Skill (in `.claude/skills/`) |
|---|---|
| Add/extend a feature | `kotlin-project-feature-implementation` |
| PR or architecture review | `kotlin-project-architecture-review`, `kotlin-kmp-code-review` |
| Design/evaluate module boundaries | `kotlin-project-modularization` |
| Pick a state-holder pattern (VM / MVI) | `kotlin-project-state-management` |
| Write/review shared Compose UI | `kotlin-ui-compose-multiplatform` |
| Adaptive / window-size layouts | `kotlin-ui-adaptive-resources` |
| Navigation, back stack, deep links | `kotlin-navigation-compose-multiplatform` |
| App Links / deep links / assetlinks.json | `kotlin-platform-app-links-and-deep-links` |
| `expect`/`actual`, platform bridges | `kotlin-platform-kmp-bridges` (if/when iOS is added) |
| Repos, data sources, source-of-truth | `kotlin-data-kmp-data-layer` |
| Tests (`kotlin.test`, Compose UI, screenshot) | `kotlin-testing-kmp` |
| Gradle, convention plugins, version catalogs | `kotlin-build-kmp-gradle-governance` |
| Bug fix (root cause + regression test) | `kotlin-project-bugfix` |
| Refactor with safety / rollback plan | `kotlin-kmp-refactor-safety` |

Hard rules: no new module without `kotlin-project-modularization`; no state holder without `kotlin-project-state-management`; no Gradle edit without `kotlin-build-kmp-gradle-governance`; no test without `kotlin-testing-kmp`. If a task does not match a skill, stop and ask.

## Architecture (locked in Master Brief §5)

Multi-module Gradle, layered, local-first:

```
athar/
├─ app/                          # entry point, navigation graph, theme wiring
├─ core/
│   ├─ design-system/            # tokens, typography, base components
│   ├─ data/                     # Room, repositories, DAOs (Hilt-bound)
│   ├─ domain/                   # use cases, pure-Kotlin models
│   ├─ common/                   # money (BigDecimal), dates (kotlinx-datetime)
│   └─ testing/                  # shared fixtures
├─ feature/{today,trends,plan,wishlist,investments,settings,onboarding}/
│   # MVI-lite: one screen → one ViewModel → one state class → one event sealed class
│   # Each feature depends only on core:* and ingestion/ml via interfaces — never on a sibling feature
├─ ingestion/
│   ├─ sms-parser/               # pure-Kotlin, no Android deps, JVM-testable
│   ├─ sms-listener/             # BroadcastReceiver bridge → RawIngestEvent
│   └─ notification-listener/    # NotificationListenerService bridge (Path B)
├─ ml/
│   ├─ categorizer/              # rule engine + TFLite classifier
│   └─ models/                   # tflite assets
└─ build-logic/                  # Gradle convention plugins
```

Architectural rules:
- **Functional core, imperative shell.** Pure Kotlin in `core:*`, `ingestion:sms-parser`, `ml:categorizer`. Side effects only at Android boundaries.
- **Single source of truth:** encrypted Room DB. No backend, no sync in v1.
- **Money = `java.math.BigDecimal`.** Never `Float`/`Double` for amounts.
- **Ingestion is source-agnostic.** SMS receiver / notification listener / future Open Banking adapter all emit the same `RawIngestEvent`. Parser/categorizer/persistence don't know which source produced an event.
- **Never auto-confirm a transaction.** SMS-parsed transactions land in a pending tray on Today; user confirms with one tap.
- **A file > 300 lines is a smell.** No god classes.

## Stack (locked)

| Layer | Choice |
|---|---|
| Language | Kotlin 2.x |
| UI | Jetpack Compose + Material 3 Expressive |
| Min SDK / Target SDK | 28 / latest stable (35 at time of brief) |
| DI | Hilt |
| Persistence | Room (KSP) + SQLCipher; key from Android Keystore |
| Async | Coroutines + Flow |
| Navigation | Compose Navigation 2.x (single-activity) |
| Charts | Vico (Compose-native). Pie charts banned. |
| Date/time | kotlinx-datetime; UmmalquraCalendar/HijrahDate for Hijri |
| ML (on-device) | TensorFlow Lite (text classifier) + ML Kit Entity Extraction |
| SMS | `BroadcastReceiver` on `android.provider.Telephony.SMS_RECEIVED`; `RECEIVE_SMS` + `READ_SMS` (sideload only) |
| Build | Gradle 8.x + version catalog (`libs.versions.toml`) |
| Logging | Timber. No Crashlytics (privacy commitment) — local crash log file only in v1. |
| Backup | Encrypted SQLite dump + JSON, AES-256-GCM, Argon2id passphrase |

## Information architecture (locked, Master Brief §3)

Three bottom-bar destinations, exactly: **Today (اليوم) · Trends (النمط) · Plan (الخطة)**, plus a Settings drawer. The earlier 5-tab IA in `qayd_android_budget_app_prd.md` is **obsolete** — do not implement it.

## Brand & design tokens (locked, Master Brief §2.4)

Palette tokens — use these names in code (`MaterialTheme.athar.ember`, etc.):

| Token | Hex | Use |
|---|---|---|
| `ink` | `#0E0F12` | Primary text; surfaces in dark mode |
| `parchment` | `#F4F1EB` | Primary background |
| `surface` | `#FFFFFF` | Cards on parchment |
| `muted` | `#6B6B68` | Secondary text |
| `divider` | `#E5E2DB` | 0.5px hairlines, never thicker |
| `ember` | `#C2541C` | The single accent. One accent per screen. |
| `olive` | `#5C6B3A` | Income / positive (muted, not bright green) |
| `dust` | `#B58A2C` | Caution |
| `crimson` | `#8E1F1F` | Danger — destructive confirms only |

Sizing scale (`sp`): **11 · 13 · 15 · 17 · 22 · 28 · 40 · 64**. Nothing else.
Spacing scale (`dp`): **4 · 8 · 12 · 16 · 24 · 32 · 48 · 64**. Nothing else.
Touch targets ≥ 48dp. No gradients. No shadows except 1px hairline divider. Animations ≤ 200ms ease-out cubic. No bounce, no spring. No sound, ever.

### Typography — IMPORTANT deviation from Master Brief

The user has chosen the **Thmanyah typeface** (`thmanyah typeface/` folder; aesthetic guide in `دليل جماليات خط ثمانية.pdf`) instead of the IBM Plex Sans Arabic + Inter pairing locked in Master Brief §2.4. This is a **brand decision**, not a technical one — before writing typography code:

1. Update `Athar_Master_Brief.md` §2.4 to lock in Thmanyah (the brief itself mandates "update this document first, then write code").
2. Decide which Thmanyah family maps to which role: `thmanyahsans` (UI body / Arabic UI?), `thmanyahserifdisplay` (landmark numbers on Today?), `thmanyahseriftext` (Latin UI?). Document the mapping.
3. Verify Thmanyah supports tabular figures (`tnum`, `lnum`) — money MUST use tabular numerals. If it does not, keep Inter or a fallback for numerals.
4. Add the font files to `app/src/main/assets/fonts/` (or `res/font/`) and register them in the design-system module.

## Distribution flavors

- **Sideload** (v1, "personalFullSms" flavor) — has `RECEIVE_SMS` + `READ_SMS`; installs via APK / Obtainium. **Not** Play-Store eligible.
- **Store-safe** (later, "storeSafe" flavor) — no SMS permissions; uses `NotificationListenerService` + manual + import only. Designed-for in `ingestion/` so the source layer is interchangeable.

Permissions in v1 sideload: `RECEIVE_SMS`, `READ_SMS`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` (`dataSync` type), optionally `BIND_NOTIFICATION_LISTENER_SERVICE`.

## Build & test commands

Standard commands (the Gradle project + version catalog have been in place since Phase 0; verify task names against `./gradlew tasks --all` if you suspect drift):

| Purpose | Command |
|---|---|
| Build everything | `./gradlew build` |
| Build a single module | `./gradlew :feature:today:build` |
| Unit tests (all) | `./gradlew test` |
| Unit tests (one module) | `./gradlew :ingestion:sms-parser:test` |
| Single test class/method | `./gradlew :ingestion:sms-parser:test --tests "AlRajhiParserTest.parsesPurchase"` |
| Lint | `./gradlew ktlintCheck detekt` |
| Auto-fix lint | `./gradlew ktlintFormat` |
| Paparazzi snapshots (verify) | `./gradlew verifyPaparazziDebug` |
| Paparazzi snapshots (record new baseline) | `./gradlew recordPaparazziDebug` |
| Instrumented tests | `./gradlew connectedAndroidTest` |
| Maestro E2E flows | `maestro test .maestro/flows/` |
| Install debug APK on emulator | `./gradlew installPersonalFullSmsDebug` (or `installStoreSafeDebug`) |
| Send test SMS to emulator | `adb emu sms send AlRajhiBank "شراء بمبلغ 200.00 ر.س ..."` |

Do not invent additional Gradle tasks — verify against the actual `build.gradle.kts` files before adding to this table.

### Release process

Established and used through beta.11 → beta.23. Each user-visible release:

1. Bump `athar.version` in `gradle.properties`.
2. Update `CHANGELOG.md` (new `## [0.1.0-beta.NN] — YYYY-MM-DD` section), `README.md` (bullet in the "What's working today" list), and `docs/ROADMAP_GLOBAL.md` (new row in the post-Tier-1 patches table with rationale).
3. `./gradlew :app:assemblePersonalFullSmsDebug :app:assemblePersonalFullSmsRelease`.
4. Copy the debug APK to `releases/athar-beta.NN-seeded.apk` for local archive.
5. Commit on `main` with a Conventional Commits message (`feat:`, `fix:`, `docs:` …).
6. `git tag -a v0.1.0-beta.NN -m "…" && git push origin main v0.1.0-beta.NN`.
7. `gh release create v0.1.0-beta.NN <release-apk> --title "…" --notes "…"`.

Users subscribed via Obtainium (Settings → "Stay up to date") get the new release automatically.

## Testing strategy (Master Brief §6.5)

Five layers, all author-able by Claude from a spec:

1. **Unit** — JUnit 5 + Truth + Turbine. Pure parsers, categorizer, use cases.
2. **Compose UI** — Compose UI Test framework. Screen rendering + basic interaction.
3. **Snapshot / pixel** — **Paparazzi** (JVM, no emulator). Every Compose preview snapshotted; visual diffs reviewed before merge.
4. **E2E** — **Maestro** YAML flows (e.g., send test SMS → confirm pending → see in trends).
5. **Visual QA** — emulator screenshots + Claude vision review against design tokens (single ember accent, palette compliance, type scale, spacing scale).

The `corpus/sms/` folder (to be created in Phase 2 / S-01) holds anonymized real SMS bodies with expected parse outputs — parser tests run against the entire corpus on every commit.

## Spec-first workflow (Master Brief §6.4)

Every ticket follows this loop:

1. Read the ticket row in `Athar_Backlog.md`.
2. Write `docs/specs/{ID}-{slug}.md` — 200–500 words covering goal, inputs, outputs, edges, acceptance.
3. Load the matching `.claude/skills/` skill (see routing table above).
4. Implement: code + unit tests + Compose previews + Paparazzi snapshots + (if flow-level) Maestro YAML.
5. PR description summarizes the spec. Use Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`).

**Definition of Done** (per Master Brief §6.7): spec exists, compiles with zero warnings, unit tests pass (happy + edge), Compose previews + Paparazzi approved, Maestro green if flow-level, visual QA approved, PR merged.

> If a ticket goes more than 3 review-iteration rounds, the spec was wrong — stop, rewrite the spec, restart.

## Three things Athar will never do (Master Brief §2.2)

1. Sell, share, or transmit financial data without explicit user action.
2. Show ads, "offers," cashback partnerships, or affiliate links.
3. Make the user feel bad — no red scream bars, no shaming notifications, no streak loss, no gamification.

## Voice & copy (Master Brief §2.3)

Arabic-first, executive-warm, numbers-led. No emoji in product copy. No exclamation points. No "Oops!" or anthropomorphized money. Example pairs:

| Reject | Prefer |
|---|---|
| "Great job staying on budget! 🎉" | "تحت الحد بـ 412 ر.س." |
| "Add a transaction" | "إضافة" |
| "Welcome to MyApp!" | "أثر." |
