# أثر — ATHAR
### Master Brief v1.0 · for Claude Code handoff

> **One sentence.** Athar is a local-first Android app that traces every riyal you spend by silently reading your bank notifications, auto-categorizing each transaction, and showing you the shape of your financial life — replacing a complex Excel sheet with three calm screens.

> **One Arabic line.** أثر يُري الإنسان أين يذهب ماله، بهدوء، بصدق، ودون عناء.

---

## How to use this document

This is the single source of truth for Athar. Hand it to Claude Code as the project's `MASTER_BRIEF.md`. Every other artifact (PRD, ADRs, sprint notes) should defer to this. When this document and reality disagree, **update this document first, then write code**.

- §1 explains the *why*.
- §2–3 are the *brand* (do not improvise on naming, palette, or voice).
- §4 is the *PRD* — feature parity with the Excel sheet plus the SMS layer.
- §5 is the *technical architecture* — locked-in decisions.
- §6 is the *AI-driven development workflow* — how to actually build this with Claude.
- §7–8 are the *roadmap and backlog*.
- §9 is the *risks register*.
- §10 is the *appendix* with reference data (sample SMS, sample rules).

---

## §1. Why this exists

### The Situation
You currently track personal finances in *The Measure of a Plan* budget tracking tool — a sophisticated Excel workbook with 17 sheets, ~80 categories, budget targets, wishlist capacity logic, period comparisons, and a family investments pool. It works. But:

- Every transaction is a manual entry. You spend → you remember → you sit at a laptop → you type. Most of the time you don't.
- The sheet is not on your phone in any usable way.
- It cannot read what your bank is already telling you.
- It does not know that "ستاربكس" is a coffee shop.
- It is one tap from being lost (one corrupted file, one wrong sync).

### The Complication
You spend in two languages, across multiple banks (Al Rajhi the primary), with bank SMS arriving in mixed Arabic/English within seconds of the transaction. The information is already in your pocket — it just isn't structured. Meanwhile, the Excel's nuanced features (Wishlist capacity, Family Investments pool, budget variance) cannot be replaced by a generic third-party app like Mint or YNAB. So the off-the-shelf choices are wrong, and the manual choice is exhausting.

### The Question
How do you get a finance tracker that is (a) *automatic* — the work happens while you sleep, (b) *honest* — every detail of the Excel preserved, (c) *quiet* — Apple-grade minimal, no clutter, no anxiety, (d) *yours* — local-first, no creepy cloud, fully owned by you?

### The Resolution
Build **Athar** — a native Android app, Kotlin + Jetpack Compose, that listens to bank SMS, parses each transaction with a rules-first engine, auto-categorizes against a Saudi merchant dictionary, and exposes three primary screens (Today, Trends, Plan) plus deeper layers (Investments, Wishlist, Settings). Local-first, fully offline-capable, encrypted on device, optional encrypted backup. Built using AI-driven development (Claude Code + Maestro + MCP) so that one person ships it in ~12 weeks of evenings.

---

## §2. Brand identity

### 2.1 Name

**Primary: أثر — Athar**

| Property | Value |
|---|---|
| Arabic | أثر |
| Romanization | Athar |
| Pronunciation (EN) | /ˈæ.θɑr/ |
| Pronunciation (AR) | /ʔa.θar/ |
| Meaning | Trace · mark · footprint · impact · the visible thing left behind by a thing that has moved |
| Why it fits | A transaction is a trace. The app makes the trace visible. The point of seeing the trace is to change the path. The word is short, single-morpheme, instantly memorable, dignified, secular, modern Arabic — works in formal and casual register. |
| Domain check (TODO) | athar.app · athar.sa · athar.io · use-athar.com — verify before committing |
| Trademark check (TODO) | Saudi Authority for Intellectual Property (SAIP) class 9 (software) and class 36 (financial services). Note: there is an Athar tourism brand in KSA; finance category is likely clear, confirm with SAIP search. |

**Backup names if Athar is unavailable:**
1. **مسار — Misar** (path / trajectory) — your money has a trajectory; we plot it.
2. **صراحة — Saraha** (frankness, honesty) — the app that tells you the truth about your spending.
3. **خلاصة — Khulasa** (essence, bottom line) — the summary distilled.

**Names rejected and why:**
- *Mizan / Riyaal / Hesabi / Khazna / Daftar* — all live brands in MENA fintech.
- *Najm* — strong association with Saudi traffic-accident insurance.
- *Bayan* — already in personal use as a Hermes agent name.

### 2.2 Vision, mission, positioning

**Vision (10-year):** A world where everyone can see, in calm clarity, what their money is doing — without spreadsheets, without spreadsheet anxiety, without surrendering their data to anyone.

**Mission (now):** Replace the spreadsheet for one Saudi household. Then for ten. Then for a country.

**Positioning statement:**
> For Saudi professionals who track their finances seriously but no longer want to do it manually, Athar is a quiet personal-finance app that reads your bank messages and shows you the shape of your money. Unlike Mint, YNAB, or PocketGuard, Athar is built for the Saudi banking reality (Al Rajhi SMS, SAR, Hijri calendar awareness, Arabic-first), stays entirely on your device, and preserves the depth of a real budgeting model — without ever asking you to enter a transaction by hand.

**Category:** Personal finance · Budgeting · Money tracking (premium / privacy-first segment).

**Three things Athar will never do:**
1. Sell, share, or transmit your financial data anywhere without your explicit, granular action.
2. Show ads, "offers," cashback partnerships, or affiliate links.
3. Make you feel bad. (No red bars that scream. No notifications that shame. No streak loss.)

**Amendment — 2026-10-16 — signed shared merchant catalog (scoped no-network exception).**
Supersedes the blanket no-network reading of "stays entirely on your device" for **one narrow capability only**: an opt-in (off by default), foreground-only, manual-or-stale-triggered whole-catalog download of a signed, merchant-only public library from one fixed HTTPS endpoint (`https://raw.githubusercontent.com/wa1939/athar/merchant-catalog/catalog-v1.json`, dedicated data-only branch). The download carries **no transactions, merchant queries, raw messages, account/card ids, dates, user identifiers, or telemetry of any kind** — the host sees only that an Athar instance requested one static public file (IP + request timing). Storage stays app-private (AtomicFile + nonfinancial DataStore metadata); financial data remains encrypted Room, never cached outside it. StoreSafe ships without `INTERNET` and without the capability. Obtainium remains the only app-update channel; key rotation happens only via a trusted app update. Already-shipped local rule auto-confirm behavior (a CONFIRMED-initial status when a valid local rule matches) is unchanged and is **not** extended to catalog or any model suggestions: a catalog category suggestion can never by itself flip an ingestion row into a financial posting.

### 2.3 Voice and tone

**Editorial voice principles:**
- **Quiet, not loud.** Period. Never exclamation. Never emoji in product copy.
- **Honest, not motivational.** "You spent 2,847 SAR on Groceries this month" — not "Way to go on staying under budget! 🎉"
- **Executive, not childish.** No "Oops!" No "Let's track that!" No anthropomorphizing money.
- **Bilingual-native.** Arabic strings written by an Arabic speaker, not translated. Saudi register, not Levantine. Executive-warm, never stiff MSA, never overly colloquial.
- **Numbers first.** Lead with the figure. Explain after. "2,847 ر.س — مصاريف بقالة هذا الشهر."

**Example copy comparisons:**

| Bad (generic finance app) | Good (Athar) |
|---|---|
| "Great job staying on budget! 🎉" | "تحت الحد بـ 412 ر.س." |
| "You spent $200 at Starbucks!" | "200 ر.س · ستاربكس · قهوة" |
| "Oops! That category is over budget." | "البقالة تجاوزت الحد بـ 318 ر.س." |
| "Add a transaction" | "إضافة" |
| "Welcome to MyApp!" | "أثر." |

### 2.4 Visual identity

#### Palette — locked (revised 2026-05-24 for brand mark)

The accent color is **brand gold** — matching the dots motif and "ATHAR" wordmark in the
finalized logo (`authar logo.png`). The previous `ember` value of `#C2541C` (orange copper)
was replaced once the brand mark was finalized. The token name `ember` is retained in code
to avoid churn; the rendered color is now gold.

| Token | Hex | HSL | Use |
|---|---|---|---|
| `ink` | `#0E0F12` | 225 13% 6% | Primary text, primary surfaces in dark mode |
| `parchment` | `#F4F1EB` | 39 23% 93% | Primary background — warm off-white, premium paper feel |
| `surface` | `#FFFFFF` | 0 0% 100% | Cards on parchment |
| `muted` | `#6B6B68` | 60 2% 41% | Secondary text, metadata |
| `divider` | `#E5E2DB` | 39 17% 87% | Hairline rules — 0.5px, never thicker |
| `ember` | `#B8893C` | 39 51% 48% | Brand gold — the single accent. Primary action, balance highlight, "over-budget" subtle pip |
| `olive` | `#5C6B3A` | 76 30% 32% | Income / positive — muted on purpose, no bright green |
| `dust` | `#B58A2C` | 41 62% 44% | Caution — gentler than amber |
| `crimson` | `#8E1F1F` | 0 64% 34% | Danger — only for destructive confirms, never decorative |

**Rules:**
- One accent per screen. Brand gold (`ember`) only. Olive/dust/crimson are state colors, not decorative.
- Never use both ember and olive in the same data visualization without a reason.
- Dark mode flips ink↔parchment but preserves the ember.
- No gradients. No shadows except a single 1px hairline divider where needed.

#### Typography — locked (revised 2026-05-24)

The app uses the **Thmanyah typeface family** (by Thmanyah, Saudi Arabia) for its bilingual coverage and editorial register. Font files live in `thmanyah typeface/` at the repo root and ship as Android `res/font` assets under `core:design-system`. Aesthetic guide: `دليل جماليات خط ثمانية.pdf`.

| Role | Font | Weights shipped | Note |
|---|---|---|---|
| Arabic + Latin UI body | **Thmanyah Sans** (`thmanyahsans`) | Light 300, Regular 400, Medium 500, Bold 700, Black 900 | Default for every screen surface — Arabic-native, Latin-balanced. Default weight: Regular. Buttons: Medium. |
| Long-form Latin reading | **Thmanyah Serif Text** (`thmanyahseriftext`) | Light 300, Regular 400, Medium 500, Bold 700, Black 900 | Reserved for activity log, ADRs in-app, anything paragraph-shaped. Not for UI chrome. |
| Numerals (landmark) | **Thmanyah Serif Display** (`thmanyahserifdisplay`) | Regular 400, Medium 500 | The single big number on Today and any "hero" figure in Trends. Editorial flourish; never used at body size. |
| Numerals (body / inline) | **Thmanyah Sans** with tabular figures | Regular/Medium | Apply `FontFeature.tabularFigures()` and `FontFeature.liningFigures()` in Compose. **Verify the OTF includes `tnum`/`lnum` opentype features on first ship** — if not, fall back to a system tabular font (e.g., `Roboto Mono`) for money columns only. |

**Why Thmanyah over IBM Plex Sans Arabic + Inter** (the previous lock): unified bilingual rendering from one family, Saudi-designed register, editorial Display cut available for the Today landmark. Decision recorded by user 2026-05-24. Original Plex+Inter pairing left in git history.

**Sizing scale (Compose `sp`):** 11 · 13 · 15 (body) · 17 · 22 · 28 · 40 · 64 (landmark). No other sizes.

**Spacing scale (Compose `dp`):** 4 · 8 · 12 · 16 · 24 · 32 · 48 · 64. Nothing else.

**Density:** Loose. Touch targets ≥ 48dp. Line-height generous. White space is not waste.

#### Logo direction

The wordmark is the product. The icon is a discipline.

**Wordmark.** أثر set in a custom Arabic letterform based on IBM Plex Sans Arabic Bold, with one modification: the three dots of the letter **ث** (thaa) are rendered as a single broken trail — three diminishing dots — to literally depict a *trace*. The English "Athar" lockup sets below in Inter Medium, 70% size, parchment-warm-grey.

**App icon.** A single parchment-toned square. Centered: the same modified **ث** glyph in ember. No outer border. No drop shadow. iOS-style rounded square handled by Android adaptive icon spec.

**Loading mark.** Three dots, ember, fading in and out in sequence — the trace, alive.

**To generate the logo:** hand a brief PDF to a designer (or use Figma + a competent type tool). Do not vector-trace in code. The brief should specify: "Single-line modification of the ث dots into a trailing breadcrumb — diminishing in size 1.0 → 0.7 → 0.4. All other letterforms strictly from IBM Plex Sans Arabic Bold. Letter-spacing unchanged. Optical center the wordmark in the icon square."

### 2.5 Motion and sound

- **Animation.** All transitions ≤ 200ms, ease-out cubic. No bounce. No spring. Numbers animate by counting up/down, not crossfading.
- **Haptics.** One tick on a successful auto-categorization. One soft on a manual entry save. Nothing else.
- **Sound.** None. Ever.

---

## §3. Information architecture

Three primary destinations, in this exact order, accessed by a thin bottom bar.

```
┌──────────────────────────────────────────┐
│                                          │
│              [content]                   │
│                                          │
│                                          │
│   اليوم   ·   النمط   ·   الخطة          │
│   Today    Trends      Plan              │
└──────────────────────────────────────────┘
```

### Today (اليوم)
The single most important screen. Opens to:
- **One landmark number** — net flow this month, rendered in Source Serif 4 at 64sp.
- **Caption** — "إنفاق هذا الشهر · 12,847 ر.س من أصل 30,000 ر.س"
- **A vertical feed of today's traces** — each transaction as a single line: amount · merchant · category pip. Tap to edit.
- **A pending tray (collapsible)** — transactions auto-imported from SMS but unconfirmed. Swipe right to confirm, left to dismiss.

### Trends (النمط)
- **Period selector** — top, segmented control: شهر / 3 أشهر / سنة / مخصص.
- **One chart at a time.** Category breakdown is default. Tap a category to drill into trend-over-time for that category. No multi-axis charts. No pie charts (banned).
- **Comparison strip** — small numerical comparison to previous period at the bottom, no chart.

### Plan (الخطة)
- **Budget targets per category** — list, with current actual vs target.
- **Wishlist** — items, saving capacity, projected purchase month.
- **Investments** — Family Investments pool, owners, share %, returns. (One tap deep.)

### Settings (drawer)
- Accounts & SMS sources
- Categories & rules
- Backup & restore
- Display (language, currency, calendar)
- About / Privacy

---

## §4. PRD — Product Requirements

### 4.1 Goals (in priority order)

1. **Replace the Excel.** Every feature the workbook offers exists in Athar within 12 weeks of build.
2. **Make manual entry unnecessary for >80% of transactions.** Measured by: count of transactions auto-imported and auto-categorized correctly, divided by total transactions, over a 30-day sample.
3. **Be calm.** Subjective but enforceable: no more than 3 colors on any screen, no notifications by default, no streaks/scores/gamification.
4. **Be private.** No transaction data leaves the device unless the user explicitly exports.
5. **Be Saudi-first.** Arabic-first UI, Al Rajhi SMS as the first-class input source, SAR as default, Hijri date display optional.

**F09 receipt OCR boundary.** Receipt-image import already exists and remains a local-only English assistance path: the app uses bundled Tesseract4Android 4.9.0 and the official English tessdata_fast model on-device. No Arabic or real-photo accuracy is promised. The catalog-only network exception does not authorize receipt or financial uploads, runtime model downloads, diagnostic telemetry, or network/diagnostic ML Kit SDKs. Merchant/entity extraction, including the separately planned ML Kit Entity Extraction work, remains outside F09 and is not implemented or bundled here.

### 4.2 Non-goals (v1)

- iOS app. (You said Android. Locked.)
- Multi-user / family sharing. (One user, one device.)
- Cloud sync. (Local-first; export-only.)
- Investment performance tracking with live prices. (Family Investments table is static input; no market data.)
- Bill reminders, due-date tracking. (Defer.)
- Subscription detection. (Defer.)
- Loan amortization, FIRE projections. (Defer.)

### 4.3 Excel-to-app feature parity matrix

| # | Excel sheet | Excel function | Athar equivalent | Priority |
|---|---|---|---|---|
| 1 | Category Setup | 80 expense + income categories, notes | Settings → Categories (CRUD), seeded with current 80 | P0 |
| 2 | Expenses | Ledger (date, vendor, amount, category, notes) | Today feed + edit sheet, full CRUD, search | P0 |
| 3 | Income | Same as Expenses, income side | Same ledger model, type=income | P0 |
| 4 | Budget Targets | Monthly target per category, implied annual | Plan → Budget Targets screen | P0 |
| 5 | Dashboard | Period selector, totals, savings rate, category breakdown | Trends screen | P0 |
| 6 | Historical Comparison | Period 1 vs Period 2 deltas | Trends → Comparison strip + deeper "Compare periods" view | P1 |
| 7 | Comparison to Budget Targets | Actual vs target variance | Plan → Variance view (inline on each category card) | P0 |
| 8 | Wishlist | Items, cost, current saved, savings capacity, feasibility, projected month | Plan → Wishlist screen | P1 |
| 9 | استثمارات العائلة (Family Investments) | Pool, owner, share %, return, net | Plan → Investments screen with proportional calc | P1 |
| 10 | Selected Time Period Data / Total | Internal compute sheets | Database queries — no UI | P0 (internal) |
| 11 | Change Log | Audit history | Settings → Activity log (auto-generated) | P2 |
| 12 | Date Info / Chart Backup | Internal | Drop — DB handles | n/a |

### 4.4 New capabilities (beyond the Excel)

| # | Capability | One-line spec | Priority |
|---|---|---|---|
| N1 | SMS auto-ingestion | App listens to incoming SMS from configured bank senders, parses each into a candidate transaction, queues for one-tap confirm. | P0 |
| N2 | Auto-categorization | Each parsed transaction is matched against a rules dictionary (merchant → category) and a tiny on-device classifier. Confidence shown. | P0 |
| N3 | Pending tray | Unconfirmed transactions sit in a tray on Today; swipe to confirm, swipe to dismiss. Never auto-commit. | P0 |
| N4 | Notification listener (fallback) | If bank moves to push-only, listen to bank app notifications. | P1 |
| N5 | Smart corrections | When user re-categorizes a transaction, app asks "Always categorize STARBUCKS as Coffee?" — learns. | P1 |
| N6 | Encrypted local backup | Export full DB as encrypted file (passphrase-protected) to user-chosen location (Drive, local). | P0 |
| N7 | Hijri date display toggle | Show Hijri alongside Gregorian on Today screen. | P2 |
| N8 | Quick add via share | Share text from any app → Athar parses as transaction. | P2 |

### 4.5 Data model

```
Account
  id : UUID
  name : String              # e.g. "Al Rajhi Visa"
  type : enum {DEBIT, CREDIT, CASH, OTHER}
  currency : String          # default "SAR"
  smsSenders : List<String>  # e.g. ["AlRajhiBank", "9999"]
  active : Boolean
  createdAt : Instant

Transaction
  id : UUID
  accountId : UUID
  type : enum {EXPENSE, INCOME, TRANSFER}
  amount : Decimal           # always positive
  currency : String
  date : LocalDate
  occurredAt : Instant       # full timestamp if known
  merchant : String          # raw from SMS or user-entered
  merchantNormalized : String # lowercase, stripped
  categoryId : UUID?
  notes : String?
  source : enum {MANUAL, SMS, NOTIFICATION, SHARE, IMPORT}
  sourceRefId : String?      # SMS id or notification key, for dedup
  status : enum {PENDING, CONFIRMED, DISMISSED}
  confidence : Float?        # 0..1 for auto-categorized
  createdAt : Instant
  updatedAt : Instant

Category
  id : UUID
  name : String              # English internal
  nameAr : String            # Arabic display
  kind : enum {EXPENSE, INCOME}
  icon : String              # icon key
  monthlyTarget : Decimal?   # null = no target
  archived : Boolean
  sortOrder : Int

CategoryRule
  id : UUID
  pattern : String           # substring or regex
  patternType : enum {SUBSTRING, REGEX, EXACT}
  categoryId : UUID
  priority : Int             # higher wins
  learnedFromUser : Boolean
  createdAt : Instant

WishlistItem
  id : UUID
  name : String
  cost : Decimal
  currentSaved : Decimal
  desiredMonths : Int?       # optional target timeframe
  startMonth : YearMonth
  status : String            # computed: NOW / WAIT until YYYY-MM / INFEASIBLE
  notes : String?

InvestmentPool
  id : UUID
  name : String              # "استثمارات العائلة"
  period : String            # "3 أشهر"
  totalReturn : Decimal      # 17500
  totalCorpus : Decimal      # 1,380,000 (auto-sum)

InvestmentContribution
  id : UUID
  poolId : UUID
  ownerName : String         # "وليد", "حامد", "هناء", "وضاح", "وافي"
  amount : Decimal
  # computed at query time:
  #   sharePct = amount / sum(amounts in pool)
  #   shareReturn = totalReturn * sharePct
  #   netTotal = amount + shareReturn

SmsMessage (audit / dedup)
  id : UUID
  sender : String
  body : String
  receivedAt : Instant
  parsedTransactionId : UUID?
  parseStatus : enum {PARSED, FAILED, IGNORED}
  parseError : String?
```

### 4.6 SMS parsing — specification

#### Input
Real Al Rajhi SMS samples (anonymized formats):
```
شراء بمبلغ 200.00 ر.س
البطاقة xxxx
من STARBUCKS 1234
الرصيد 4,521.30 ر.س
2026/02/14 14:32
```
```
Purchase SAR 200.00
Card xxxx
At STARBUCKS 1234
Balance SAR 4,521.30
2026/02/14 14:32
```
```
تحويل بمبلغ 1,000.00 ر.س
الى احمد محمد ع
الرصيد 3,521.30 ر.س
```
```
ايداع 27,700.00 ر.س
من ELM CO
الرصيد 31,221.30 ر.س
```

#### Output
A candidate `Transaction` with `status = PENDING`, `source = SMS`, `sourceRefId = smsMessageId`, plus a confidence score on the parse itself (separate from categorization confidence).

#### Pipeline
1. **Filter** — sender must be in any active `Account.smsSenders`. Otherwise ignore.
2. **Classify** — is it `purchase` / `transfer_out` / `deposit` / `reversal` / `balance_alert` / `other`? Regex on Arabic + English action verbs.
3. **Extract** — amount, currency, merchant/counterparty, balance, card-last-4, timestamp. Use named-capture regex per known template; fallback to ML extraction (ML Kit Entity Extraction) if regex fails.
4. **Dedup** — by `sourceRefId` and a hash of (amount + sender + minute-bucket).
5. **Categorize** — see §4.7.
6. **Persist** — write transaction as PENDING, write SMS audit record, raise local notification "1 جديد" (one new).

#### Robustness rules
- Never auto-confirm. Always PENDING until user touches it.
- Never delete a SMS. Audit log retains every parsed and unparsed message.
- Surface parse failures in Settings → "SMS messages we couldn't read (3)" — user can teach the app with a one-tap example.

### 4.7 Auto-categorization — specification

#### Tiered approach (in order)
1. **Exact merchant match.** If `merchantNormalized` exactly equals a known `CategoryRule.pattern` with `patternType = EXACT` → assign with confidence 1.0.
2. **Substring match.** Any `CategoryRule` with `patternType = SUBSTRING` whose pattern appears in `merchantNormalized` → assign with confidence = 0.85, highest priority wins.
3. **Regex match.** `patternType = REGEX` → confidence = 0.80.
4. **Classifier fallback.** On-device TFLite text classifier (small, ~2MB) trained on Saudi merchant → category. Output a single category with softmax probability → confidence = that probability, capped at 0.7.
5. **Unknown.** No category assigned. User must pick. Confidence = 0.

#### Learning loop
- When the user changes the category of a transaction whose merchant matches a rule with `learnedFromUser = true`, update the rule.
- When the user changes the category of a transaction with no rule, prompt: "Always categorize «STARBUCKS» as «قهوة»? (Yes / No / Just this one)". On Yes, insert a new `CategoryRule` with `learnedFromUser = true, priority = 100, patternType = SUBSTRING`.

#### Seed dictionary (ship with app)
~200 Saudi merchant patterns mapped to categories. Examples:
| Pattern (substring) | Category |
|---|---|
| starbucks · barn's · dunkin · costa · % arabica | Coffee |
| mcdonald · kfc · hardees · subway · jollibee · domino | Restaurants |
| panda · tamimi · carrefour · lulu · danube · othaim | Groceries |
| jarir · extra · ikea · amazon | Shopping |
| stc · mobily · zain · etisalat | Telecom |
| uber · careem · jeeny | Transport |
| saudia · flynas · flyadeal · booking · trivago | Travel |

Full seed list lives in `assets/seed_rules.json` and ships with the app.

### 4.8 UX principles (non-negotiable)

1. **Three taps to anything.** Open app → land on Today. Two taps reaches any feature.
2. **No empty states with cartoon illustrations.** Empty states say something useful in two lines.
3. **No modals stacked on modals.** One modal at a time. Confirmations inline where possible.
4. **Right-to-left first.** All screens designed RTL first, LTR is the mirror.
5. **No spinners > 1 second.** If a thing takes longer, show progress; otherwise hold the previous screen.
6. **Never block the user for a "let us calculate."** All aggregates are precomputed or cached.

---

## §5. Technical architecture

### 5.1 Stack — locked

| Layer | Choice | Reason |
|---|---|---|
| Language | **Kotlin 2.x** | Native, mainstream, heavily represented in LLM training data, JetBrains-stable. |
| UI | **Jetpack Compose** + **Material 3 Expressive** | LLM-friendly declarative model (React-like), animatable, RTL-first, premium look out of the box. |
| Min SDK | API 28 (Android 9) | Covers ~95% of active Saudi devices. SMS APIs stable from API 19, no issue. |
| Target SDK | latest stable (currently API 35 / Android 15) | Required for Play Store, also gives access to Material 3 Expressive. |
| DI | **Hilt** | Google-standard, Compose-friendly, LLM-trained. |
| Persistence | **Room** (SQLite + KSP) | First-class Android ORM, type-safe, migration tooling, encrypted via SQLCipher. |
| Encryption at rest | **SQLCipher for Android** | DB encrypted with key from Android Keystore. Passphrase optional for export. |
| Reactive | **Kotlin Coroutines + Flow** | Native, idiomatic, Compose-integrated. |
| Navigation | **Compose Navigation 2.x** | Single-activity app. |
| Charts | **Vico** | Compose-native, customizable, premium feel possible. (Reject MPAndroidChart — looks dated.) |
| Date/time | **kotlinx-datetime** | Pure Kotlin, multiplatform-safe for any future expansion. |
| Hijri | **UmmalquraCalendar** (Android built-in) + **HijrahDate** (java.time) | Native, no extra dep. |
| Numerics | **java.math.BigDecimal** for money. Never Float, never Double for amounts. | Industry standard. |
| ML on-device | **TensorFlow Lite** (text classifier) + **ML Kit Entity Extraction** (Saudi-locale-aware) | Both Google, stable, well-documented. |
| Notification listening | **NotificationListenerService** (system API) | Standard Android. |
| SMS reading | **BroadcastReceiver on android.provider.Telephony.SMS_RECEIVED** + **role-based RECEIVE_SMS / READ_SMS** | See §5.4 for Play-Store reality. |
| Build | **Gradle 8.x** with version catalogs (`libs.versions.toml`) | Modern, scaled. |
| Logging | **Timber** | Tiny, ubiquitous. |
| Crash | **Local crash log file** for v1. (Reject Crashlytics — sends data to Google.) | Privacy commitment. |
| Backup | **Encrypted SQLite dump** + JSON export | Both formats. |

### 5.2 Module structure

Multi-module Gradle for parallel build + clear boundaries.

```
athar/
├─ app/                          # entry point, navigation graph, theme
├─ core/
│   ├─ design-system/            # theme, tokens, typography, components
│   ├─ data/                     # Room, repositories, DAOs
│   ├─ domain/                   # use cases, model
│   ├─ common/                   # kotlin-only utils, money, dates
│   └─ testing/                  # shared test fixtures
├─ feature/
│   ├─ today/
│   ├─ trends/
│   ├─ plan/
│   ├─ wishlist/
│   ├─ investments/
│   ├─ settings/
│   └─ onboarding/
├─ ingestion/
│   ├─ sms-parser/               # pure-Kotlin parsing logic
│   ├─ sms-listener/             # Android receiver bridge
│   └─ notification-listener/
├─ ml/
│   ├─ categorizer/              # rule engine + classifier
│   └─ models/                   # tflite assets
└─ build-logic/                  # gradle convention plugins
```

Each `feature:*` depends only on `core:*` and `ingestion`/`ml` via interfaces — never sibling features.

### 5.3 Local-first architecture

- **Single source of truth:** Room database, encrypted.
- **No backend in v1.** Anywhere. Period.
- **Sync:** None in v1. v2 may add optional E2E-encrypted sync via Supabase Realtime + libsodium, only if user opts in.
- **Backup:** User-initiated export to encrypted `.athar` file (zipped JSON, AES-256-GCM, key from passphrase via Argon2id). User saves it where they want — Drive, Photos, AirDrop equivalent, anywhere.
- **Import:** Same `.athar` file, with replace / merge / append modes.

### 5.4 SMS ingestion — the Play Store reality

**The problem.** Google Play policy restricts apps that request `READ_SMS` or `RECEIVE_SMS` to a specific allow-list of use cases. Personal-finance auto-tracking is **not** on the allow-list. An app that requires SMS access to function will be rejected from Play.

**Three pragmatic paths, all designed-for in the architecture:**

**Path A — Sideload distribution (recommended for v1 / personal use).**
The app is built as a release APK signed by you. You install it on your phone(s) via direct APK install, not Play. Use Obtainium or a private F-Droid repo for clean version management. You skip Play review entirely. **You can use any SMS permission you need.** This is the right path for *you* given the brief is "replace my Excel for me."

**Path B — Notification listener (for any future public release).**
Use Android's `NotificationListenerService` to read bank-app push notifications. Fully Play-compliant. Limitation: only works when the bank pushes a notification (Al Rajhi does for most card transactions; SMS-only fallback transactions are missed).

**Path C — Saudi Open Banking (long-term).**
SAMA's Open Banking Framework went live progressively from 2022 onward; selected banks including Al Rajhi participate. Account-Information-Service (AIS) APIs would let Athar fetch transactions directly. Realistic timeline for consumer-third-party access: still maturing. Track and integrate when stable.

**The design.** `ingestion/` is split so the *source* (SMS receiver, notification listener, Open Banking adapter) is interchangeable. All sources emit the same `RawIngestEvent { source, sender, body, receivedAt, rawId }`. The parser, categorizer, and persistence layers are source-agnostic. **This means switching from A to B or adding C is a config change, not a rewrite.**

**Permissions in v1 (sideload):**
- `RECEIVE_SMS` — receive incoming SMS broadcasts.
- `READ_SMS` — read existing SMS history on first install for one-time backfill of the last 90 days.
- `POST_NOTIFICATIONS` — for the local "1 جديد" tray indicator.
- `FOREGROUND_SERVICE` — keep listener alive on Android 14+ (use `dataSync` foreground service type).
- `BIND_NOTIFICATION_LISTENER_SERVICE` — if Path B enabled.

### 5.5 Parser engine

Single pure-Kotlin module `ingestion:sms-parser`, no Android dependencies, so it's fully unit-testable on JVM.

```kotlin
interface SmsParser {
    fun parse(message: RawIngestEvent): ParseResult
}

sealed class ParseResult {
    data class Success(
        val type: TxType,
        val amount: BigDecimal,
        val currency: String,
        val merchant: String?,
        val counterparty: String?,
        val balanceAfter: BigDecimal?,
        val occurredAt: Instant?,
        val confidence: Float,
        val templateId: String,
    ) : ParseResult()
    data class Failed(val reason: String, val templateAttempts: List<String>) : ParseResult()
    object Ignored : ParseResult()
}
```

**Template-based dispatch.** A registry of `BankTemplate` objects, each with a `sender` matcher and a list of `regex` patterns ordered by specificity. Al Rajhi has ~6 templates (purchase, refund, transfer out, transfer in, deposit, balance alert). Each template tested independently.

**Test corpus.** Build a `corpus/` folder of real (anonymized) SMS bodies. Each commit runs the parser against all of them, asserts expected output. AI-generated synthetic SMS expand the corpus.

### 5.6 Categorizer engine

Single pure-Kotlin module `ml:categorizer`.

```kotlin
interface Categorizer {
    suspend fun categorize(merchant: String, amount: BigDecimal, type: TxType): CategorySuggestion
}

data class CategorySuggestion(
    val categoryId: UUID?,
    val confidence: Float,
    val source: enum { RULE_EXACT, RULE_SUBSTRING, RULE_REGEX, CLASSIFIER, UNKNOWN },
    val ruleId: UUID?,
)
```

Rules engine first, classifier second. Classifier is a tiny TFLite model (text → category) trained offline on a labeled dataset that you and Claude assemble. Training pipeline lives in `tools/categorizer-training/` (Python, run on demand, not part of the app build).

---

## §6. AI-driven development workflow

### 6.1 The premise

You will not write Athar by hand. You will write *specifications*. Claude Code will write the code. You will review, run, test, and iterate. This is a different posture from "use Copilot for autocomplete" — it requires discipline about *what you hand the AI* and *how you verify what it gives back*.

### 6.2 Repository conventions

```
athar/
├─ MASTER_BRIEF.md              # this document
├─ BACKLOG.md                    # the prioritized ticket list
├─ docs/
│   ├─ adr/                      # architecture decision records, numbered
│   ├─ specs/                    # detailed feature specs (one per epic)
│   └─ prompts/                  # reusable prompt templates
├─ corpus/
│   ├─ sms/                      # SMS test corpus (anonymized)
│   └─ categorization/           # merchant → category training data
├─ CLAUDE.md                     # instructions for Claude Code agents in this repo
└─ ...
```

`CLAUDE.md` is the single file Claude Code reads first. It contains: the stack, the module structure, the coding conventions, where to find specs, and the test invocation commands. **Keep it under 300 lines.**

### 6.3 Coding conventions for AI agents

- **Functional core, imperative shell.** Pure-Kotlin logic in `core:*` and `ingestion:sms-parser` and `ml:categorizer`. Side effects only at Android-framework boundaries.
- **No god classes.** A file > 300 lines is a smell.
- **One screen, one ViewModel, one state, one event sealed class.** MVI-lite.
- **Compose previews mandatory.** Every composable has `@Preview` with at least one realistic state.
- **Tests live next to code.** `:foo/src/test/...` for unit, `:foo/src/androidTest/...` for instrumented.
- **No TODOs without an issue link.** `// TODO(#123)`.
- **Conventional Commits.** `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`.

### 6.4 The spec-first loop

For each ticket in the backlog:

1. **You write the spec.** One markdown file in `docs/specs/`. Sections: goal, inputs, outputs, edge cases, acceptance criteria, non-goals. ~200–500 words.
2. **Claude reads the spec + the relevant module's existing code.** Generates: implementation + unit tests + Compose previews (if UI) + a short PR description.
3. **You run the tests, view the previews, run on emulator.** Check it.
4. **Claude iterates from your feedback.** Commit.
5. **You review the diff.** Merge.

The spec is the contract. Lazy specs produce lazy code. Specs in your style — SCQR-shaped, MECE — produce production code.

### 6.5 Testing strategy

Five layers, all of which Claude can author and execute:

| Layer | Tool | What it covers | Who writes it |
|---|---|---|---|
| Unit | JUnit 5 + Truth + Turbine | Pure logic, parsers, categorizer | Claude writes from spec |
| Compose UI | Compose UI Test framework | Screen rendering, basic interactions | Claude writes from preview spec |
| Snapshot / pixel | **Paparazzi** (preferred, runs on JVM, no emulator) | Visual regression on Compose previews | Claude generates expected; you review diffs |
| E2E flow | **Maestro** (YAML flows) | Real user journeys: import SMS → confirm → see in trends | Claude writes from acceptance criteria |
| Manual visual QA | Emulator screenshots + Claude vision review | "Does this *look* premium?" | You run, Claude reviews |

#### Visual QA loop (the closest analog to "MCP Chrome" for Android)
1. You boot the emulator (`emulator -avd Pixel_8_API_34`).
2. Maestro runs the visual smoke flow, capturing screenshots at each step into `qa/screenshots/$(date)/`.
3. You point Claude (in Code or chat) at the folder. Claude visually inspects each shot against the design system rules (one accent, palette tokens, typography scale, spacing scale) and flags violations.
4. You fix or reject Claude's findings, commit the approved baseline.

This is your "Chrome MCP for Android" equivalent — it's not a single shrink-wrapped MCP server, it's a pipeline of standard tools that Claude can orchestrate. There are emerging Android-emulator MCP servers (community-maintained) but the screenshot-+-vision-review loop above is more robust today.

### 6.6 MCP servers to wire up

| MCP server | What it does for you |
|---|---|
| **Filesystem MCP** | Lets Claude read your repo, specs, screenshots directly. Essential. |
| **GitHub MCP** | Open PRs, comment on issues, read CI output. |
| **Linear / Notion MCP** (optional) | Sync `BACKLOG.md` with whatever you track tickets in. |
| **Custom Maestro MCP** (build one) | Wraps `maestro test` so Claude can run E2E flows on demand. ~50 lines. |
| **Custom adb MCP** (build one) | Wraps `adb shell` for emulator control: install APK, send SMS, capture screenshot. ~80 lines. |

The last two are small enough to build in one evening each. Pattern after the official Filesystem MCP server.

### 6.7 Definition of Done (per ticket)

A ticket is Done when **all** of:
- Spec exists in `docs/specs/`.
- Code compiles with zero warnings.
- Unit tests cover the happy path + at least one edge case, all passing.
- If UI: Compose preview exists and Paparazzi snapshot is approved.
- If flow-level: a Maestro flow exists and passes.
- Visual QA pass: Claude vision-review approves against design tokens.
- PR merged to `main`.

---

## §7. Roadmap

12 weeks of evenings. Adjust as life dictates.

### Phase 0 — Foundations (Week 1–2)
Set the project up so the AI loop is fast.
- Repo, modules, CI (GitHub Actions: build + lint + unit + Paparazzi).
- Design system module: tokens, typography, base components (`AtharText`, `AtharNumber`, `AtharCard`, `AtharListRow`, `AtharBottomBar`).
- Room schema for `Account`, `Category`, `Transaction`, `CategoryRule`. Seed migration.
- Hilt graph. App scaffold. Empty Today / Trends / Plan screens with the bottom bar.
- `CLAUDE.md` written. First two ADRs written.

### Phase 1 — MVP / Excel parity (Week 3–6)
Reach feature parity with the Excel sheet for **manual entry**. No SMS yet.
- Manual transaction add / edit / delete (Today + sheet).
- Category management (Settings).
- Budget targets per category (Plan).
- Trends screen: period selector, category breakdown, comparison strip.
- Historical comparison (Trends).
- Variance vs targets (Plan).
- CSV import of existing Excel data — one-time backfill.
- Encrypted local backup + import.

### Phase 2 — SMS magic (Week 7–9)
The reason this app exists.
- `RawIngestEvent` pipeline.
- SMS BroadcastReceiver wired to ingest queue.
- Al Rajhi parser templates (5–6 templates, 50+ corpus samples).
- Categorizer rules engine + seed dictionary (~200 rules).
- Pending tray on Today.
- One-tap confirm / dismiss / re-categorize.
- "Always categorize X as Y" learning.

### Phase 3 — Polish & analytics (Week 10–11)
- Charts (Vico): clean lines, no junk.
- Animations: number count-up, list enter/exit.
- Empty states with real copy.
- Dark mode parity check.
- RTL/LTR parity check.
- Hijri date display toggle.
- Performance: cold start < 800ms, warm Today render < 100ms.

### Phase 4 — Wishlist + Family Investments (Week 12)
- Wishlist with savings-capacity logic, projected purchase month.
- Family Investments pool with proportional return distribution.
- Both reachable from Plan tab, two taps from cold open.

### Phase 5 — Daily-driver beta (Week 13+)
- Switch off the Excel. Use Athar daily.
- Keep a `journal.md` of friction. One commit per Friday addressing one friction.
- Notification-listener fallback (Path B) for Play-compliance readiness.
- Stretch: subscription detection, receipt OCR, multi-account.

---

## §8. Backlog

See `Athar_Backlog.md` (companion file). Each row is ticketable directly.

---

## §9. Risks register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Al Rajhi changes SMS format silently | Medium | High (parser breaks for new TXs) | Audit log retains every SMS; weekly parse-success rate dashboard; corpus-driven regression tests |
| R2 | Google Play rejects app for SMS perms | Certain (if submitted as-is) | Medium | v1 is sideload-only; Path B (notification listener) designed in from day one |
| R3 | TFLite classifier underperforms on Arabic merchants | Medium | Medium | Ship without classifier; rules + user-correction loop is sufficient for 90%+ of cases |
| R4 | Encrypted backup file corrupts | Low | Catastrophic | Auto-snapshot every Sunday to two locations; integrity hash; restore tested in CI |
| R5 | Scope creep ("just add iOS" / "add web") | High (by you, to yourself) | High (kills v1) | This brief locks Android-only. v2 conversation happens after Phase 5. |
| R6 | Visual quality regresses without you noticing | Medium | High (the "Apple-like" promise) | Paparazzi snapshots + weekly Claude vision-review. |
| R7 | Performance regression as DB grows past 10k transactions | Medium | Medium | Indices on `date`, `categoryId`, `accountId`; pagination on Today feed; profile every release. |
| R8 | You stop using the app after week 4 | Medium | Total | Set a personal hard rule: when Phase 1 ends, the Excel is read-only. Athar is the system of record. |

---

## §10. Appendix

### 10.1 Sample SMS corpus (build out in `corpus/sms/`)

(Anonymized real bodies; build a Markdown table of `sender | body | expected: {type, amount, merchant, …}` and parse them in tests.)

```
sender: AlRajhiBank
body: "شراء بمبلغ 200.00 ر.س\nالبطاقة 1234\nمن STARBUCKS 1234\nالرصيد 4,521.30 ر.س\n2026/02/14 14:32"
expected:
  type: EXPENSE
  amount: 200.00
  currency: SAR
  merchant: STARBUCKS 1234
  balanceAfter: 4521.30
  occurredAt: 2026-02-14T14:32:00+03:00
```

### 10.2 Seed category list (88 categories, English + Arabic)

| # | English | Arabic |
|---|---|---|
| 1 | Car maintenance | صيانة سيارة |
| 2 | Car payment | قسط سيارة |
| 3 | Childcare | رعاية الأطفال |
| 4 | Clothing | ملابس |
| 5 | Coffee | قهوة |
| 6 | Debt | ديون |
| 7 | Education | تعليم |
| 8 | Electronics | إلكترونيات |
| 9 | Entertainment | ترفيه |
| 10 | Gas | وقود |
| 11 | Gifts | هدايا |
| 12 | Going out | خروج |
| 13 | Groceries | بقالة |
| 14 | Gym | نادي |
| 15 | Home maintenance | صيانة منزل |
| 16 | Insurance | تأمين |
| 17 | Medical | طبي |
| 18 | Mortgage | رهن عقاري |
| 19 | Other | متفرقات |
| 20 | Public transport | مواصلات |
| 21 | Rent | إيجار |
| 22 | Restaurant | مطاعم |
| 23 | Salary | راتب |
| 24 | Side income | دخل جانبي |
| 25 | Telecom | اتصالات |
| 26 | Travel | سفر |
| 27 | Utilities | فواتير |
| 28 | Wife allowance | مصروف الزوجة |
| 29 | Charity / Zakat | صدقة وزكاة |
| 30 | Subscriptions | اشتراكات |

(Round to 80 by adding personal/contextual ones from your Excel; ship as `assets/seed_categories.json`.)

### 10.3 Reading list

- *Material 3 Expressive guidelines* — google.com/material design site
- *Compose performance* — Android Developers · "Stability in Compose"
- *SQLCipher for Android* — Zetetic docs
- *Maestro by Mobile.dev* — maestro.mobile.dev
- *Refactoring UI* — Adam Wathan & Steve Schoger (for the calm-UI mindset)
- *SAMA Open Banking Framework* — sama.gov.sa

### 10.4 Glossary

- **Trace.** A single transaction record in Athar. Internally always `Transaction`; "trace" is the user-facing word in copy where helpful.
- **Pending.** A transaction auto-imported but not yet confirmed by the user.
- **Pool.** A `InvestmentPool` — a shared corpus across multiple owners.
- **Capacity.** Monthly remaining cash after fixed expenses and savings reserve, available for Wishlist items.

---

*End of Master Brief v1.0. Treat it as living. Edit it before you edit code.*
