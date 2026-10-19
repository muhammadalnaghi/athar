# Changelog

All notable changes to Athar will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- Nothing yet.

## [0.1.0-beta.25] — 2026-10-19

- Personal category choices now take precedence over downloaded merchant knowledge, and explicit corrections retain USER provenance without training on catalog-only suggestions.
- Added an off-by-default signed public merchant catalog for the FullSMS flavor, with version/digest rollback protection, durable opt-out, cancellation, offline cache, safe retry and reset. It downloads one fixed merchant-only file; no transactions or merchant queries are uploaded. StoreSafe remains without INTERNET or SMS permissions.
- Replaced receipt-image ML Kit OCR with bundled offline English Tesseract assistance, including model integrity checks and notices. No runtime model downloads, Arabic OCR or real-photo accuracy claim.
- Today, Trends and widgets use the same confirmed operating-transaction selection; transfers stay visible without inflating operating income or spending.
- Verified English, Arabic RTL and StoreSafe Settings flows, plus real disconnected app restart/cache/reset. Component native checks cover API28 and API35.
- Advanced Android versionCode to 10 for in-place updates from the previous public versionCode 9. Merchant AI remains research-only and is not included in this release.

## [0.1.0-beta.24] — 2026-06-20

The recovered best-in-class application stack release: notification ingestion, TMOAP import depth, safer categorization, recurring/bills polish, support diagnostics, and manual-entry upgrades.

- Store-safe notification parsing now normalizes at-symbol merchant hints such as `USD 8.50 @ Starbucks` and compact `Purchase @Starbucks USD 8.50`, while numeric-only `@ 123456789` values stay rejected.
- Store-safe notification parsing now preserves legitimate merchant names that start with cleanup words such as `Card Factory` and `Via Roma Cafe`, while still stripping suffixes such as `with Apple Pay`, `via Apple Pay`, and `using card ending 1234`.
- Store-safe notification parsing now preserves digit-starting income and transfer counterparties from peer-payment and structured notification hints such as `3M Payroll paid you`, `Sender: 3M Payroll`, and `Recipient: 401K Savings`, while numeric-only counterparty values stay rejected.
- Store-safe notification parsing now preserves digit-starting merchants from explicit notification hints such as `at 7-Eleven`, `Merchant: 7-Eleven`, and `Payment to 3M`, while numeric-only hint values stay rejected.
- Store-safe notification parsing now preserves digit-starting merchants such as `7-Eleven` and `6th Street` from title-first, body-line, and trailing amount notification hints while still rejecting numeric-only identifier lines.
- Store-safe notification parsing now skips label-only body lines such as `Merchant` or `Location` after posted amount lines, then uses the following real merchant line when present.
- Store-safe notification parsing now preserves merchants from body lines after posted amount/title lines, such as `Card purchase SAR 99.00` followed by `Noon`, while avoiding bank-app titles as false merchants.
- Store-safe notification parsing now preserves merchant names from title-first notification payloads such as Noon, Carrefour, and Toast Box while avoiding bank-app titles as false merchants.
- Store-safe notification parsing now treats posted `processed` and `settled` status-prefixed payment/transaction/purchase notifications as posted transactions and preserves merchants such as Noon, Carrefour, and Toast Box.
- Store-safe notification parsing now treats posted subscription or membership renewal copy as recurring expense charges, preserving merchants such as Netflix/Spotify when present while keeping future renewal reminders ignored.
- Existing recurring rules now show their account/category routing and can edit those two fields without deleting the rule; future materialized pending rows use the corrected routing.
- Recurring-rule creation now lets users choose the active account when adding a manual rule or confirming an auto-detected suggestion; explicit choice wins over a safe inferred suggestion account and the manual-account fallback remains for empty/missing account state.
- The copied bulk-categorization AI prompt now includes seed-backed label guidance for exact parser labels such as ATM withdrawals, bank fees, debt payments, tax refunds, reimbursements, cashback, deposits, work expenses, life-admin payments, travel, retail, and entertainment rows.
- Built-in seed refresh now compares bundled rule content (`pattern`, `patternType`, `categoryId`, and `priority`) instead of only rule count, so metadata-only seed updates reach existing installs while preserving user-learned and `auto-local-*` rules.
- Bundled seed rules now support optional `patternType`, and generic-prefix shared labels such as `cash deposit`, `bank fees`, and `كاش باك بطاقة ائتمانية` are exact matches instead of broad substring rules. Asset tests now reject future generic-prefix substring seeds.
- Community-rule exports now reuse the shared specific-merchant guard, so legacy explicit substring rules for generic labels such as `payment`, `cash`, `bank`, `كاش`, and `دفع` are not exported or proposed as public seed rules.
- Today and History edit flows now guard explicit "Always categorize" rule learning with the shared specific-merchant check, so generic labels such as `payment`, `cash`, `bank`, `كاش`, and `دفع` save as one-row edits instead of prompting for or creating broad substring rules/backfills.
- Manual Add Transaction quick-add/autocomplete chips now reuse the shared specific-merchant guard, so generic history labels such as `payment`, `cash`, `bank`, `كاش`, and `دفع` do not appear as routine merchant shortcuts while real repeated merchants still do.
- Recurring auto-detected suggestions now reuse the shared specific-merchant guard, so repeated generic labels such as `payment`, `cash`, `bank`, `كاش`, and `دفع` cannot become subscription/rule suggestions while real merchants such as gyms, rent, utilities, and salary sources still surface when they repeat.
- Support diagnostics now reuse the shared specific-merchant guard for hashed uncategorized merchant groups and category-backlog recommendations, so repeated generic labels such as `payment`, `cash`, `bank`, `كاش`, and `دفع` count toward total backlog but no longer trigger repeated-backlog cleanup guidance.
- Specific-merchant safety checks now use one shared domain guard across bulk categorization, Today pending suggestions, History repeated-backlog cleanup, and local auto-learning, keeping English and Arabic generic labels such as `payment`, `cash`, `bank`, `كاش`, and `دفع` as row-by-row work that cannot drive same-merchant shortcuts or exact learned rules.
- Bulk categorization now treats generic merchant labels such as `payment`, `purchase`, `cash`, `merchant`, and `bank` as single-row cleanup work: explicit filled rows still update, but generic keys no longer drive repeated-group inheritance or exact learned rules.
- Support diagnostics now include a redacted `category_backlog_recommendation` block derived only from aggregate backlog size and hashed repeated-merchant group sizes, pointing support to History repeated-backlog cleanup, bulk CSV categorization, manual cleanup, or no action without exposing raw merchants, amounts, SMS bodies, notes, or transaction rows.
- Settings now imports the TMOAP workbook's Family Investments sheet through a separate preview-confirm flow, creating the investment pool or replacing matched pool contributors after confirmation so repeat imports do not duplicate rows.
- Settings now imports the TMOAP workbook's Wishlist sheet through a separate preview-confirm flow, creating new wishes or updating matching existing wishes by item name only after confirmation.
- Settings now imports the TMOAP workbook's Budget Targets sheet through a separate preview-confirm flow, applying matched monthly expense/income category targets only after confirmation and reusing the TMOAP-aware category aliases from statement import.
- Bundled categories now match the concrete TMOAP workbook category setup more closely by adding Condo fees, Work, Tax refund, Expense reimbursement, Bonus, and Other income. Statement CSV/XLSX category lookup is now transaction-type aware and resolves TMOAP labels such as Public transportation, Wife, Job, Side project, and income Other without crossing expense/income category kinds.
- Built-in category seeding is now upgrade-safe: app startup inserts missing bundled categories for existing installs while preserving user-edited names, archived state, targets, custom categories, and current sort order.
- Android library unit tests now run on JUnit Platform through the shared convention plugin. The newly active test gate exposed and fixed statement CSV header normalization, XLSX XML padding, support-diagnostics decimal redaction, and side-income seed guard edge cases.
- Store-safe notification parsing now normalizes posted furniture, home-goods, and appliance purchase pushes to exact seed-backed home/electronics labels while preserving named stores when present and ignoring quotes, carts/wishlists, delivery/order-status, installation/assembly, warranty, reminder, and offer copy with amounts; the active public seed is now 703 rules.
- Store-safe notification parsing now normalizes posted gift-card, gift, and flower-delivery payment pushes to exact seed-backed gift labels while preserving specific florists when present and ignoring free-gift promos, gift-card balances, expiry/reminder copy, and offers with amounts; the active public seed is now 700 rules.
- Store-safe notification parsing now normalizes posted mortgage, home-loan, housing-finance, and Arabic mortgage-instalment payment pushes to the exact seed-backed `Mortgage payment` label while ignoring mortgage offers, due reminders, schedules, refinance/rate estimates, pre-approvals, and eligibility copy with amounts; the active public seed is now 697 rules.
- Store-safe notification parsing now normalizes posted auto-loan, vehicle-finance, and Arabic car-instalment payment pushes to the exact seed-backed `Car payment` label while ignoring auto-finance offers, due reminders, schedules, and estimates with amounts; the active public seed is now 696 rules.
- Store-safe notification parsing now normalizes posted car-service, oil-change, car-wash, and tire-service payment pushes to exact seed-backed car-maintenance labels while preserving named providers when present and ignoring quotes, estimates, appointments, reminders, and offers with amounts; the active public seed is now 695 rules.
- Store-safe notification parsing now normalizes posted home-service, gym-membership, and childcare/daycare payment pushes to exact seed-backed labels while preserving named providers when present and ignoring offers, quotes, and reminders with amounts; the active public seed is now 691 rules.
- Private-corpus audit output now writes the rich redacted report to the repository-root `build/private-corpus-audit/latest.json` instead of a module-local build directory, keeping parser/categorization evidence in one predictable place.
- Bulk categorization imports now accept copied category option values such as `cat-coffee=Coffee / قهوة`, bilingual display labels, and case-variant category IDs while still type-checking the resolved category before updating rows or training exact local rules.
- Store-safe notification parsing now normalizes posted cinema-ticket, event-ticket, and game-purchase pushes to exact seed-backed entertainment labels while preserving named venues/events when present and ignoring entertainment promos, showtimes, presales, reservations, and game offers with amounts.
- Store-safe notification parsing now normalizes posted clothing, electronics, and online-shopping payment pushes to exact seed-backed retail labels while preserving named merchants when present and ignoring retail offers, cart reminders, shipping/order-status, and price-drop copy with amounts.
- Store-safe notification parsing now normalizes posted flight-ticket, hotel, travel-booking, and car-rental payment pushes to exact seed-backed travel labels while preserving named airlines/providers when present and ignoring travel offers, itineraries, reservations, quotes, and reminders with amounts.
- Store-safe notification parsing now normalizes posted fuel, grocery, restaurant, coffee, food-delivery, and taxi-ride payment pushes to exact seed-backed labels while preserving specific stations/ride merchants when present and ignoring everyday-commerce offers, estimates, reservations, and reminders with amounts.
- Store-safe notification parsing now normalizes posted medical, pharmacy, education, charity, and zakat payment pushes to exact seed-backed labels while preserving specific clinics/charities when present and ignoring essential-life due reminders or donation appeals with amounts.
- Store-safe notification package coverage now includes verified Canadian, German, and Saudi wallet/bank apps such as CIBC, BMO, Desjardins, Tangerine, Wealthsimple, EQ Bank, KOHO, Deutsche Bank, ING Deutschland, Sparkasse, SAB Mobile, urpay, and Mobily Pay; the ANB gate was tightened to exact package IDs so unrelated package names no longer enter the generic transaction parser through a broad `anb` substring.
- Bulk categorization imports now treat exact local merchant rules as upserts: re-importing the same decision does not duplicate a rule, and corrected imports replace stale exact local rules while leaving seed and explicit substring rules intact.
- Bulk categorization full-context exports now fill `raw_body` from the linked local ingestion-audit SMS/notification body when available, falling back to notes only for manual/imported rows; private exports still keep `raw_body` blank.
- Seed-rule hygiene now removes 51 legacy private-person or ambiguous local side-income patterns from the public seed, adds an asset guard so low-priority side-income rules must be public or institution-backed, and leaves the active seed at 667 rules while the redacted private audit still reports 0 parser failures, 0 missing-merchant parsed expenses, and `rawBodiesWritten = 0`.
- Bulk categorization imports now report why rows were skipped, separating unknown categories, wrong expense/income type, missing transactions, conflicting merchant groups, blank rows, and malformed rows in the Settings result summary.
- Bulk categorization Settings now includes a one-tap copy action for the external AI prompt, keeping the CSV roundtrip usable without API keys or cloud integration.
- Bulk categorization Settings now offers a private CSV export that keeps import-matching fields and category options but omits raw SMS bodies before sending the file to external AI.
- Bulk categorization imports now accept saved AI responses that contain a valid fenced `csv` block, so users do not need to strip ChatGPT/Claude prose before importing.
- Bulk categorization imports now reject categories whose kind does not match the matched transaction type, avoid propagating incompatible categories to blank repeated-merchant peers, and skip exact-rule training for mixed-type import groups.
- Store-safe notification parsing now preserves merchants from card-used-at labels such as `Your card was used at merchant for amount` and `Debit card used at merchant amount`.
- Store-safe notification parsing now preserves merchants from card-used status labels such as `Card used: merchant amount` and `Card ending in 1234 was used - merchant amount`.
- Store-safe notification parsing now uses structured `Recipient:`, `Receiver:`, and `Beneficiary:` labels as merchant hints for posted expense notifications after the body has already qualified as an expense, while keeping transfer recipient labels as transfer counterparties.
- Store-safe notification parsing now preserves merchants from status-prefixed posted transaction copy such as `Payment successful: merchant amount`, `Transaction completed - merchant amount`, and `Purchase approved: merchant amount`.
- Store-safe notification parsing now ignores amount-bearing payment/money request notifications before the generic payment action parser can turn them into fake expenses.
- Store-safe notification parsing now preserves structured location-style merchant labels such as `Location:`, `Merchant Name:`, `Outlet:`, and `Card acceptor:` from posted finance-app notifications.
- Store-safe notification parsing now handles generic POS/card transaction phrases such as `Transaction of ... at merchant was approved` and strips terminal approval/posting status words from merchant labels.
- Store-safe notification parsing now normalizes posted parking, road-toll, and transit-fare pushes to shared seed-backed mobility labels, preserves specific operators, ignores mobility due/unpaid reminders with amounts, and adds exact active seed rules for those labels, bringing `seed_rules.json` to 718 rules.
- Store-safe notification parsing now normalizes posted traffic-fine and government-service payment pushes to shared seed-backed public-service labels, preserves specific agencies, ignores public-service due reminders with amounts, and adds exact active seed rules for those labels, bringing `seed_rules.json` to 715 rules.
- Store-safe notification parsing now treats mobile, prepaid, and airtime recharge/top-up pushes as telecom expenses instead of generic `top up` income, preserves specific carriers, ignores recharge promo copy with amounts, and adds an exact active `mobile recharge` seed rule, bringing `seed_rules.json` to 713 rules.
- Store-safe notification parsing now normalizes posted subscription-payment, insurance-premium, and rent-payment pushes to shared seed-backed labels, ignores recurring-expense reminder/renewal notices with amounts, and adds exact active seed rules for those labels, bringing `seed_rules.json` to 712 rules.
- Store-safe notification parsing now normalizes explicit salary/payroll income pushes to shared salary labels while preserving specific payroll sources, and ignores salary-transfer financing offers with amounts.
- Store-safe notification parsing now normalizes generic electricity, water, and bill-payment pushes to shared utility labels and ignores utility bill reminder notices with amounts.
- Store-safe notification parsing now normalizes posted credit-card repayment and loan-instalment pushes to shared debt labels and ignores card/loan repayment reminder notices with amounts.
- Store-safe notification parsing now normalizes posted bank-fee pushes to the shared `Bank fees` merchant label and ignores fee-schedule/tariff notices with amounts.
- Store-safe notification parsing now normalizes ATM/cash-withdrawal pushes to the shared `ATM Withdrawal` merchant label and ignores ATM withdrawal-limit notices with amounts.
- Store-safe notification parsing now treats posted refund/reversal/chargeback push copy as income, preserving the source counterparty for shapes such as `refunded`, `purchase reversal`, and `chargeback`.
- Curated seed rules now include three additional public/reusable merchant labels from private-audit follow-up review, bringing active seed coverage to 709 rules and reducing the redacted private audit's uncategorized parsed expenses from 1,112 to 1,077 without committing raw SMS data.
- Store-safe notification parsing now preserves clear resulting balances from generic bank-app transaction pushes, such as trailing `Balance SAR ...` or Arabic `رصيدك ... بعد خصم ...`, while leaving ambiguous balance-before-spend copy unset and keeping balance-only notices ignored.
- Store-safe notification parsing now preserves regional currency symbols such as `S$`, `R$`, `RM`, `Rp`, `₱`, `₩`, `฿`, and `₫` as their correct ISO currencies instead of falling back to SAR or plain USD.
- Store-safe notification parsing now preserves compact merchant/biller labels that appear immediately after the transaction amount, such as POS purchase and Arabic bill-payment copy, while stripping trailing balance/card/status suffixes.
- Bulk categorization imports now train exact merchant rules instead of broad substring rules, and community-rule exports omit exact local rules so private/imported merchant labels stay on device unless the user explicitly creates an "Always categorize" substring rule.
- Bulk categorization imports now propagate one unambiguous category choice to blank rows for the same repeated merchant inside the imported CSV, while conflicting merchant-group categories update only the explicit rows and do not train a learned rule.
- The gated private SMS audit now reports top hashed uncategorized merchant groups, including merchant length/script buckets, sample counts, and parser template counts, so categorization backlog work can focus on repeated merchants without exposing raw private labels.
- SMS universal fallback parsing now detects Arabic action words without Latin word-boundary assumptions, so Arabic Al Rajhi transfer fallback rows no longer masquerade as uncategorized expenses. The gated private audit now reports redacted missing-merchant shape groups and the latest local audit is back to 0 parsed expense rows with missing merchants.
- Bulk categorization CSV exports now skip transfer rows, place repeated merchant groups first, include `merchant_group_count`, and add row-level `category_options` from the user's active category table so ChatGPT/Claude or a human can assign consistent categories faster without wasting rows on transfers that normally have no category.
- **Pending widget actions.** Each pending row now has Confirm / Dismiss / Categorize controls. Confirm and Dismiss enqueue a Hilt `CoroutineWorker` that calls `TransactionRepository.setStatus`; Categorize opens Athar so the user can use the full edit/category-learning flow.
- **Fresh widgets after ledger changes.** Transaction repository mutations now request a debounced widget refresh, and widget-originated actions refresh Month, Today, and Pending widgets after the status update.
- Manual Add Transaction now shows recent merchant quick-add/autocomplete chips. Tapping a chip fills merchant, amount when currency-safe, type, and category from the user's own confirmed history.
- Manual Add Transaction now has quick phrase and voice fill. Short English/Arabic phrases such as `spent 50 at Starbucks` or `دفعت 35 في كارفور` prefill amount, type, and merchant, and reuse a recent merchant's category when there is an exact local match.
- Manual Add Transaction can attach one local receipt image before save. The image is copied from Android's photo picker into a SQLCipher-backed receipt table and included in encrypted `.athar` backups.
- Saved receipt images can now be viewed, exported, or removed from the transaction edit sheet without deleting the transaction.
- Transactions can now keep multiple encrypted receipt images. The receipt table migrates to Room v7 with a non-unique transaction lookup index, Add Transaction appends several pending images before save, and the edit sheet lets each saved receipt be viewed, exported, or removed independently.
- Plan now has a Goals tab for savings-rate and emergency-fund targets. Progress is computed locally from confirmed history and liquid account balances, with targets persisted in DataStore.
- Today now shows a compact Goals check for savings-rate target and emergency-fund coverage, reusing the persisted Plan targets and excluding reconciliation adjustments.
- Plan → Bills first slice: upcoming recurring bills projected 60 days ahead, overdue rules surfaced once, uncategorized pending transactions folded into the same list, and a compact 14-day calendar strip for quick scanning.
- Plan → Bills now has opt-in bill reminders. The recurring worker sends quiet 2-days-before, due-today, and one-time missed-review notifications only after the user enables reminders, with DataStore sent-key pruning to avoid daily repeats.
- SMS sender matching now tolerates real sender casing and edge whitespace, and cross-bank ignore rules cover maintenance, fee/tariff, fraud-awareness, app-migration, and account-admin notices from known bank/wallet senders.
- SMS parsing now ignores more bank administration notices from known senders, including Arabic beneficiary add/activation variants, mobile-login/biometric notices, card-product notices, savings-account promos, app migration, document, and fraud-awareness copy.
- SMS parsing now ignores D360 card-service recovery notices and Jazira brand announcements from the private family-device corpus; a local audit of the private export now has zero failed known-bank messages without committing the raw file.
- SMS fallback parsing now preserves Arabic Al Rajhi purchase, transfer, and biller labels from the private family-device corpus instead of emitting bank-sender fallback merchants. A local aggregate audit reduced parsed expense rows with missing merchants from 3,800 to 474 and increased categorized expenses from 79 to 945 without committing raw SMS.
- SMS parsing now routes more private-corpus Arabic bank shapes correctly: Al Rajhi card settlements become transfers, salary/cashback rows stop inflating expenses, D360 own-account transfers become transfers, structured public-service payments keep their service merchant, and more account/card admin notices are ignored. A local app-equivalent aggregate audit reduced parsed expense rows with missing merchants from 474 to 20 without committing raw SMS.
- SMS parsing now ignores more private-corpus bank administration/account-state notices with numeric values, including compact beneficiary activation/status, card/mobile linking, digital-card issuance, username, daily-limit, reward-point balance, and card-terms notices. Structured wallet refunds are treated as income, and a local received-message aggregate audit reduced parsed expenses with missing merchants from 18 to 5 without committing raw SMS.
- SMS parsing now preserves Al Rajhi header-only electronic-payment and Ministry of Interior public-service labels as merchants. A local received-message aggregate audit now reports 0 failed known-bank messages and 0 parsed expenses with missing merchants without committing raw SMS.
- Private-corpus audit work now has a committed gated redacted aggregate test that writes counts only under `build/private-corpus-audit/`, skips when the private export is unavailable, and records `rawBodiesWritten = 0`. The same post-R16 audit promoted the public `buffet` restaurant seed rule, bringing active seed coverage to 706 rules and reducing uncategorized parsed expenses from 1,102 to 1,101.
- SMS parsing now avoids account-tail merchants in Arabic online purchase rows, labels Arabic ATM withdrawals as `ATM Withdrawal`, and labels account-only fee debits as `Bank fees`, so category rules and local learning attach to reusable merchant labels instead of card/account numbers.
- The built-in SMS parser registry now lives in the pure parser module and is shared by app DI plus corpus tests, preventing private-corpus audit/test drift as bank, wallet, and notification templates are added.
- Statement CSV/TSV import now offers manual column mapping when a bank uses unrecognized headers, then reuses the same preview/confirm and duplicate-safe import path.
- Store-safe notification ingestion now has a generic bank-app notification parser for common English/Arabic spend, income, and transfer pushes, plus broader bank-package matching for Saudi and global finance apps.
- Store-safe notification parsing now covers more wallet/card-app phrases such as card-used, card-payment, direct-debit, payment-from, paid-you, and ACH-credit notifications, while ignoring statement, minimum-payment, and transfer-limit notices with amounts.
- Store-safe notification parsing now handles peer-payment `sent you` and `got paid ... by` income copy, CAD/AUD/CHF notification currencies, Google Pay India and Samsung Pay package variants, and ignores peer-payment requests with amounts.
- Store-safe notification parsing now preserves merchants from more bank-specific expense copy such as `card was charged ... by`, `debit card transaction from ...`, and `paid merchant amount`, strips wallet suffixes like `with Apple Pay`, and lets more global bank apps reach the conservative parser.
- Store-safe notification parsing now covers merchant-first charge and compact card-transaction copy such as `Netflix charged your card` and `Card transaction Starbucks`, preserves more global ISO currencies, ignores scheduled payment/bill-due notices with amounts, and lets more global finance packages reach the conservative parser.
- Store-safe notification parsing now preserves merchants/counterparties from `for`, `on`, Arabic `في`, `new transaction: merchant amount`, and `credit of ... from` notification copy without broadening the package allow-list.
- Store-safe notification parsing now ignores balance-only bank notifications even when they include account/location hints such as `at your checking account` or Arabic `لدى حسابك`, while keeping balance-before-spend transaction copy working.
- Store-safe notification parsing now ignores available-credit, credit-limit, cash-advance-limit, and Arabic credit-state notifications with amounts, while preserving real credited-income notifications.
- Store-safe notification parsing now ignores non-transaction card/account administration notices from finance apps, including card activation, terms updates, linked-device notices, and Arabic card activation copy.
- Store-safe notification parsing now ignores spending summaries, weekly/monthly recaps, and Arabic spending-summary notices with amounts, so `you spent X this month` copy does not create fake expense rows.
- Store-safe notification parsing now lets more known finance app packages from MENA, Australia, India/Southeast Asia, and the US pass the Android notification listener filter and reach the existing conservative parser without allowing arbitrary notification packages.
- Store-safe notification parsing now preserves merchants and counterparties from structured notification fields such as `Merchant:`, `Sender:`, `Recipient:`, `Beneficiary:`, and colon-separated `From:` labels after the known-finance package gate.
- Store-safe notification parsing now also preserves structured party labels such as `Biller:`, `Service provider:`, `Payer:`, `Remitter:`, transfer `Receiver:`, transfer `Payee:`, and Arabic `المفوتر`, keeping more notification rows useful for category rules and local learning.
- Store-safe notification parsing now ignores temporary authorization holds, pre-authorizations, pending authorizations, and Arabic temporary-hold amounts before amount selection, while still parsing real posted card-transaction notifications.
- Today and History now show a localized feedback card after "Always categorize..." so users can see how many existing pending/dismissed rows were recategorized, or that future matching rows will auto-categorize when there were no existing matches.
- History now has All / Uncategorized / Categorized category-state chips, so users can isolate rows that still need cleanup after backfills, notification ingestion, or statement imports.
- Today and History transaction rows now show localized category names instead of raw internal IDs like `cat-coffee`, while still falling back to the ID if an old row references a missing category.
- Store-safe notification and generic fallback SMS parsing now support comma-decimal and European-grouped amounts such as `€18,50` and `EUR 1.234,56`, while preserving detected non-SAR currencies in the universal fallback path.
- Statement CSV import now auto-detects common bank export headers: `description`/`details`/`narrative`, signed `amount`, split `debit`/`credit`, optional ISO currency, and Arabic date/description/debit/credit headers. Original Athar/TMOAP CSV imports still work, with positive `date,vendor,amount` rows kept as expenses unless `type` says otherwise.
- Statement CSV import now auto-detects comma, semicolon, and tab delimiters, so European-style CSVs with comma decimals and TSV bank exports do not need manual preprocessing.
- Statement CSV import now recognizes debit/credit indicator columns such as `D/C`, `DBIT`/`CRDT`, and Arabic `مدين`/`دائن`, so bank files with positive amount columns no longer need spreadsheet preprocessing to preserve expense/income direction.
- CSV import now previews detected columns, importable/skipped row counts, sample parsed rows, and the first skipped row before the user confirms the import.
- Statement import now accepts OFX/QFX files through the same preview-confirm path, mapping `DTPOSTED`, `TRNAMT`, `NAME`/`MEMO`, `TRNTYPE`, `CURDEF`, and `FITID` while skipping malformed statement transactions instead of aborting the batch.
- Statement import now accepts MT940 files through the same preview-confirm path, mapping `:61:` date/debit-credit/amount/reference data with `:86:` merchant details and statement currency from balance records.
- Statement import confirmation now lets users choose the destination account, so XLSX/CSV/OFX/QFX/MT940 rows no longer have to land in the manual Cash account.
- Statement import now uses account-scoped stable row references and skips rows already imported for the selected account, preventing repeat CSV/OFX/MT940 imports from duplicating or replacing older imported ledger entries.
- Statement import preview rows can now be excluded before confirmation; excluded rows stay visible, count as skipped, and are not inserted.
- Statement import preview now summarizes importable rows by currency with expense, income, and transfer totals before confirmation.
- Statement import preview rows can now be edited before confirmation. Date, merchant, amount, currency, type, category, and notes overrides re-run preview immediately and are applied to the same XLSX/CSV/OFX/QFX/MT940 confirmation path.
- Statement import preview rows can now be assigned to a different active account before confirmation. Row-level account overrides are included in duplicate checks and stable import references, so split-account statements stay duplicate-safe.
- Statement import now accepts transaction-grid XLSX workbooks through the same preview-confirm path, including TMOAP-style Expenses and Income sheets that use positive Amount columns.
- Curated seed rules now include 158 more active high-confidence merchants from catalog, aggregate-only private-audit review, and public/common global labels, bringing `seed_rules.json` from 551 to 709 rules with asset tests for category validity, duplicate patterns, and the R-05 batches. The latest slices add concrete subscription/software, delivery, grocery, restaurant, gas, and medical labels such as ChatGPT, Notion, Canva, Figma, Dropbox, Hulu, Disney+, Paramount+, Prime Video, Audible, Deliveroo, Instacart, Costco, Whole Foods, Buffet, Albayan Station, and United Pharmacies.
- Redacted support diagnostics now include aggregate transaction categorization-backlog counts and hashed uncategorized-merchant groups, so maintainers can distinguish parser failures from repeated uncategorized merchants without raw merchant names, amounts, notes, or transaction rows.
- Settings now includes an annual **Export for taxes/accountant** PDF. The report includes income/expense totals, category totals, and the confirmed transaction list for the selected year, while excluding reconciliation adjustments from operating totals.
- Wishlist planning now uses the full TMOAP-style model: start month and desired-month horizon are editable, each wish shows remaining amount and needed monthly saving, target misses are flagged, and rows sort by practical priority.
- Redacted support diagnostics export from Settings. Users can save `athar-support-diagnostics.json` for parser and categorization triage; it contains SMS audit counts, pseudonymous sender hashes, body-shape fingerprints/flags, failed-template groups, redacted parser errors, aggregate transaction category-backlog counts, and hashed uncategorized-merchant groups without raw SMS bodies, raw merchant names, balances, amounts, transaction rows, notes, card numbers, or account numbers.
- Local category rule learning: after three confirmed non-transfer, non-reconciliation transactions for the exact same normalized merchant all share one category, Athar creates a private exact-match priority-150 rule so future ingests can auto-categorize that merchant. Ambiguous merchants remove/skip auto rules; explicit "Always categorize X" rules still win.
- Per-account ingestion routing: Accounts now accept SMS routing aliases (sender names or card/account tails), and SMS/notification ingestion links parsed transactions to the one active account that matches. Ambiguous or missing matches safely fall back to the manual seed account.
- First-run onboarding now includes an optional XLSX/CSV import step, so spreadsheet/TMOAP users can seed their transaction history before landing in the app.
- Bank template creation now shows a live parse preview and blocks saving templates that cannot read the pasted sample SMS.
- SMS audit now includes parse-rate and sender-health diagnostics so users can identify failing bank senders or spammy sources after a rescan. Status filters now apply to the full audit history before the 200-row display cap, so older failed/ignored rows remain reachable after large backfills.
- History now filters transactions by source (SMS, notification, manual, import, recurring, share) and shows each row's source in the subtitle.
- SMS rescan-and-clean now shows the current pending count and requires confirmation before clearing pending transactions and rereading the SMS log.
- Recurring rules now auto-materialize through a Hilt WorkManager worker on app startup and a daily periodic schedule, so due subscriptions appear in the pending tray without visiting Settings → Recurring transactions → Run now. When the worker creates rows, it refreshes all Athar home-screen widgets immediately.
- App-triggered widget refresh (`updateAll(context)`) after in-app Confirm/Dismiss so the widget syncs within seconds instead of the system's 30-minute cadence.
- Integrated the recovered dev stack (#11–#30) into `dev/integration-recovered-stack`; JVM tests, both debug APK builds, and both app lint variants pass. Device E2E remains pending until an Android device or accelerated emulator is available.

## [0.1.0-beta.23] — 2026-05-27

The "tell users a new release exists, without the internet" release.

### Added

- **Settings → "تابع التحديثات / Stay up to date" card.** Athar has no `INTERNET` permission (Master Brief §2.2 — local-first, offline-only), so it cannot poll GitHub for new releases. Instead the new card delegates update-tracking to [Obtainium](https://obtainium.imranr.dev), the sideload manager users already trust. One tap on **"Add to Obtainium"** opens the deep link `obtainium://app/{percent-encoded-config}` which pre-fills Athar's GitHub URL in Obtainium's "Add app" flow. From then on, Obtainium polls the releases page on the user's behalf and notifies them when a new build ships. No permissions added, no background work in Athar.
- **Fallback chain.** If Obtainium isn't installed (deep link has no handler), Athar catches the `ActivityNotFoundException`, shows a toast (`"Obtainium isn't installed — opening its site so you can grab it"`), and opens `obtainium.imranr.dev` in the default browser. Secondary buttons: **"Install Obtainium"** (same target, manual) and **"Releases page"** (opens `github.com/wa1939/athar/releases` for power users).
- New strings (AR + EN): `settings_updates_title`, `_body`, `_action_obtainium`, `_action_install_obtainium`, `_action_releases`, `_obtainium_missing`.

### Why delegation, not in-app polling

In-app polling would require `android.permission.INTERNET`, which broadens the trust surface and contradicts the offline-first product positioning that's a feature, not an oversight. Obtainium already polls release feeds in a process the user has explicitly trusted; tying into that path means update-checking responsibility lives in the sideload manager forever — no backend, no API rate limits, no GitHub-token leakage, no extra binary in the Athar process.

## [0.1.0-beta.22] — 2026-05-27

The "reconciliation no longer inflates expenses" hotfix.

### Fixed

- **Manual reconciliation adjustments (`تسوية يدوية`) no longer count toward monthly expense / income totals.** A user reconciling a 50k+ SAR balance gap was seeing the gap appear as a single 50k expense, inflating the displayed `المصروفات` to misleading numbers (e.g. −260,287 SAR). Reconciliation transactions still affect account balance + net worth (that's the entire purpose) but are now filtered out of every spend/income aggregation: Today landmark + month totals, Trends current/prior + 12-month series, Plan per-category actuals, and the Monthly/Today widgets.
- Detection is via a new `Transaction.isReconciliation()` extension that matches `source = MANUAL` + `sourceRefId` starting with the `reconcile-` sentinel that `AccountRepository.reconcile()` already sets. No schema migration; the existing field carries the signal.
- History list and per-account balance ledger continue to show reconciliation transactions — they are auditable, just non-operating.

## [0.1.0-beta.21] — 2026-05-27

The "home-screen widgets" release. Three Glance widgets ship in a new `feature:widgets` module:

### Added

- **Athar · Month** widget (3×2 cells) — landmark net-this-month + Income / Expense / Net-worth pills. Reads the live month period from `TransactionRepository.observeByPeriod` + `AccountRepository.observeNetWorth` via Hilt EntryPoint.
- **Athar · Today** widget (2×1 cells) — today's net flow as the landmark, plus a "N pending" ember caption when the pending tray is non-empty. Hides the caption if zero.
- **Athar · Pending** widget (3×3 cells) — top 3 pending transactions with merchant + amount; "+N more" footer when there are extras.
- All three render Athar's parchment + ember palette via a small `WidgetColors` constants file (the Compose `AtharTheme` is unavailable in Glance's separate composition tree — the constants mirror it 1:1).
- Tapping any widget opens the app via `actionStartActivity(componentName)` resolved flavor-agnostically through `PackageManager.getLaunchIntentForPackage()`.
- New Gradle module `feature/widgets/` registered in `settings.gradle.kts`; app picks it up via one new `implementation(project(":feature:widgets"))` line.
- New `WidgetDataLoader` exposes three one-shot suspend funcs (`loadMonthlySnapshot`, `loadTodaySnapshot`, `loadPendingHead`) backed by a Hilt `@EntryPoint` — needed because Glance widgets have no lifecycle owner and can't use `@Inject`.
- New deps in `gradle/libs.versions.toml`: `glance = "1.1.1"` + `androidx-glance-appwidget` + `androidx-glance-material3`.

### Deferred to next release

In-place Confirm / Dismiss / Categorize action buttons on the Pending widget require a HiltWorker + Hilt-WorkManager setup that doubled the change footprint. Pending widget currently opens the app on tap; the action buttons will land in a follow-up release.

## [0.1.0-beta.20] — 2026-05-27

The "share your rules, not your data" release. The privacy-preserving alternative to a centralized merchant categorization database.

### Added

- **"Help others · share your rules" Settings card.** Tap *Export my rules* → Athar writes a JSON file containing only `(pattern, categoryId, confidence)` tuples for every rule the user explicitly created via "Always categorize X as Y" (`learnedFromUser=true`). Tap *Open GitHub issue* → device browser opens to `github.com/wa1939/athar/issues/new` with a pre-filled title + body. User attaches the JSON manually as a comment.
- **Maintainer review path.** New `.github/ISSUE_TEMPLATE/community-rules.yml` issue template with explicit privacy checkboxes ("I confirm no PII is included"). Accepted rules get merged into `core/data/src/main/assets/seed_rules.json` at priority 60 or 80; next release ships the expanded seed to every user via the `RuleSeed.seedIfEmpty()` upgrade refresh mechanism.
- **`docs/COMMUNITY_RULES_WORKFLOW.md`** — user guide + maintainer review checklist + a comparison table showing why this beats a centralized backend on every dimension that matters (privacy, abuse vector, cost, audit trail, failure mode).
- Plumbing: new `CommunityRulesShareTrigger` interface in `core/domain`, `CommunityRulesShareExporter` impl in `core/data/csv` (uses `kotlinx.serialization` for the JSON output, deduped by `(pattern, categoryId)`), `CommunityShareStatus` sealed state machine in `SettingsViewModel`.

### Privacy contract (reaffirms Master Brief §2.2)

The exported JSON contains ONLY merchant pattern + categoryId + confidence. **No transaction amounts, no dates, no raw SMS bodies, no account IDs, no PII.** The file never leaves the device until the user explicitly taps *Open GitHub issue* and attaches it. No backend, no API, no upload, no telemetry. Same end result as a centralized database (first-time users get a growing curated seed), zero data collection.

## [0.1.0-beta.19] — 2026-05-27

The "match my bank balance" release. Replaces opening-balance editing (which silently rewrote history and was throwing opaque errors) with the reconciliation flow every modern budgeting tool uses.

### Added

- **Per-account "تسوية" / "Reconcile" chip in Settings → Accounts.** Tap → sheet shows the currently displayed balance + an editable target. On Apply, Athar inserts one manual transaction so the running balance equals the target. INCOME if you're adding, EXPENSE if you're removing. The merchant label is "تسوية يدوية" / "Manual adjustment"; the transaction is visible in standard History and tagged in the Activity Log.
- **A 4-second toast** shows the signed delta on success (`+1,234 ر.س added` or `−500 ر.س added`), a muted "balance already matches" note if zero, or the actual error string if the operation failed (using the `errorDetail` StateFlow added in beta.18).
- Plumbing: new `AccountRepository.reconcile(accountId, target, label, note)` method + `ReconcileResult` sealed (Done/Failed) in `core/domain`. Impl in `AccountRepositoryImpl` reads current balance via `observeBalances().first()`, computes `delta = target − current`, validates currency match, and calls `TransactionRepository.upsert` (which records an Activity Log entry).
- New ViewModel: `AccountsViewModel.reconcile` + `ReconcileEvent` sealed (Done/NoChange/Failed) + `reconcileEvent: StateFlow<ReconcileEvent?>` for the toast.
- New strings (AR + EN): `settings_accounts_action_reconcile`, `_reconcile_title`, `_reconcile_current`, `_reconcile_target_label`, `_reconcile_note_hint`, `_reconcile_default_merchant`, `_reconcile_body`, `_reconcile_confirm`, `_reconcile_done`, `_reconcile_nochange`.

### Rationale — why this, not opening-balance editing

The opening balance is the user's starting state when an account was first added to Athar. Letting users edit it after-the-fact silently rewrites the ledger — every transaction's "running balance" shifts retroactively without an audit trail. Reconciliation is the better abstraction: explicit, dated, reversible, visible in transaction history. The Edit form is intentionally left in place as a secondary path (e.g., correcting a typo during first setup); beta.18's `errorDetail` will surface its real exception the next time you hit it and we'll fix the root cause inline.

## [0.1.0-beta.18] — 2026-05-26

The "stop misleading me, AI-assist the unknowns" release. Five user-reported issues, all fixed in one wave:

### Fixed

- **#1 · Today landmark was lying.** The big number under "اليوم / Today" was rendering the *month* net (`netFlow = incomeSum − expenseSum`), not today's. If you spent 1,000 SAR today, the landmark would still read 903 (month) and you'd have to read the small caption to find `صافي اليوم · −1,000` below the pills. Landmark now renders `state.todayNet`; the month total demoted to a secondary `Row` underneath with a `today_caption_landmark_today` overline. Master Brief §3 explicitly calls for today's net flow as the landmark — this was a regression.
- **#2 · "تعذّر تحديث الحساب" with no detail.** Editing the cash account silently failed with a one-line generic error, no logcat output, no way to diagnose. `AccountsViewModel` now wraps each repo call in a `reportFailure(kind, op, throwable)` helper that:
   - calls `Log.e("AccountsViewModel", "$op failed", t)` so the stack trace lands in logcat,
   - captures `t.message?.take(280)` into a new `errorDetail: StateFlow<String?>`,
   - renders the detail string in the Accounts error card directly under the human-readable label.
  The user (and we) can now actually see what failed without rebuilding the app.
- **#4 · "Always categorize X as Y" was forward-only.** Picking "Always" on one Hemmah charge previously added a `CategoryRule` that would catch *future* SMS only — every dismissed Hemmah row stayed stranded with no category. Both `TodayViewModel.updateTransaction` and `HistoryViewModel.updateTransaction` now call `TransactionRepository.applyCategoryToMatching(pattern, categoryId)` immediately after `learnFromCorrection`. The DAO query:
  ```sql
  UPDATE transactions
  SET categoryId = :categoryId, status = 'CONFIRMED', updatedAt = :now
  WHERE status IN ('PENDING','DISMISSED')
    AND merchantNormalized LIKE '%' || :pattern || '%'
  ```
  CONFIRMED rows are deliberately untouched (the user may have intentionally chosen a different category for some). The backfill count is exposed via a new `lastBackfill: StateFlow<BackfillEvent?>` so the UI can toast "Applied to N other Hemmah charges".

### Added

- **#5a · Bulk-categorize via external AI.** New Settings card **"تصنيف بالذكاء الاصطناعي · مجمّع" / "Bulk categorize with AI"** with the three-tap pipeline:
  1. **Export uncategorized** — writes a CSV with columns `id,merchant,merchant_normalized,amount,currency,type,status,date,raw_body,category_id` for every transaction that is PENDING, DISMISSED, or CONFIRMED-without-category. `category_id` is left blank for the user (or AI) to fill.
  2. **External AI pass** — user opens the CSV in ChatGPT / Claude / Z.ai with the existing prompt at `docs/AI_SMS_TRIAGE_PROMPT.md` and saves the filled file.
  3. **Import categorized** — for every row with a non-blank `category_id`:
       - the transaction is updated (`categoryId` set + `status → CONFIRMED`),
       - a learned `CategoryRule` is recorded per unique `(merchant_normalized → category_id)` pair so future SMS for the same merchant auto-categorize.

  Why CSV instead of an in-app API call: no API keys to manage, no cloud round-trip, no surprise costs. Users already paying for ChatGPT/Claude get full leverage; the prompt has been honed against the user's own corpus already. Result: 829 dismissed rows from a 1,000-message backfill can be categorized in under a minute by pasting the CSV into a chat, then re-imported as actionable rules. Files:
    - `core/domain/repo/MerchantBulkCsv.kt` — domain interfaces + result types.
    - `core/data/csv/MerchantBulkExporter.kt` — write filter.
    - `core/data/csv/MerchantBulkImporter.kt` — RFC-4180 row parser, category resolution by id / English name / Arabic name, dedupe of merchant→category pairs into `learnFromCorrection` calls.
    - Hilt binding in `DataModule.kt`; new `BulkCategorizeStatus` sealed state in `SettingsViewModel`; new `BulkCategorizeCard` in `SettingsScreen.kt`; AR + EN strings.

### Carried forward

- **#3 · Backup-file analysis is gated on the user's passphrase.** The `messages sample/athar-backup.athar` file is AES-256-GCM encrypted; without the passphrase we can't decrypt it. Two paths offered to the user: (a) share the passphrase off-band, or (b) export an unencrypted CSV via Settings → CSV exchange → Export. Carried into the next session.

## [0.1.0-beta.17] — 2026-05-26

The "Subscriptions + 12× more merchants known" release. Splits R-03 (confirm before create) and R-04 (Subscriptions framing) — the two missing pieces between *suggest a rule* and *actually use the rule list to manage your recurring spend* — and ships an AI-derived merchant-rule expansion that boosts categorization coverage by ~12×.

### Added

- **R-03 · Confirm sheet before rule creation.** Tapping a recurring suggestion now opens a `ModalBottomSheet` where the user picks **cadence** (Monthly / Weekly / Yearly), edits the **day-of-month**, and assigns a **category** (filtered to INCOME / EXPENSE kinds) before the rule is upserted. The merchant name and amount appear as a read-only header. Replaces the previous silent one-tap create which gave zero feedback. Plumbing: `RecurringRulesViewModel.acceptSuggestion(...)`, `CategoryRepository` injected for the picker, `lastAccepted` StateFlow drives a 3-second olive toast on success.
- **R-04 · Subscriptions UI.** Recurring rules screen now partitions rules into **Active** and **Paused** sections; a **"N active subscription(s) · Monthly total · X ر.س"** card sits between Suggestions and Active. Each rule row has an `Active`/`Paused` pill (one-tap toggle) plus a `Delete` button. Reframes the screen from "rule manager" to "subscription manager" so users can say *"this is no longer active"* without losing the rule. New strings: `settings_recurring_active_section`, `_paused_section`, `_active_count`, `_monthly_total_label`.
- **AI-seeded merchant catalog · 44 → 551 rules.** `core/data/src/main/assets/seed_rules.json` expanded with **507 high-confidence (≥0.70) merchant→category mappings** extracted by running a 1,000-message AlRajhi SMS corpus (the developer's) plus a friend's SMS history through an AI categorizer. Confidence-to-priority mapping: ≥0.85 → priority 80, ≥0.70 → priority 60, <0.70 dropped as noise. 22 categories now have rules; top coverage: `cat-public-transport` (103), `cat-restaurant` (99), `cat-side-income` (69), `cat-groceries` (60), `cat-coffee` (43). Curated rules retain priority 100 and win over AI rules on conflict.
- **Self-refreshing seed mechanism.** `RuleSeed.seedIfEmpty()` now re-seeds when `seed_rules.json` count changes (was: "skip if any system rule exists"). New DAO methods `CategoryRuleDao.clearSystemRules()` and `countSystemRules()` allow refreshing the system catalog without touching user-learned rules (`learnedFromUser = true`). Upgraded users get the expanded catalog automatically on next launch.
- **`scripts/merge_ai_seed_rules.py`** — repeatable pipeline for merging future AI rule batches into the seed file with confidence-bucketing, dedupe (lowercased pattern key), and category-count reporting.
- **`scripts/push_sms_sample.py`** — helper for E2E testing: parses an SMS Exporter `.txt` export and bulk-inserts the messages into an Android emulator's `mmssms.db` via `adb root` + `sqlite3`. Used to verify the parser + categorizer + R-03 + R-04 against a real 1,000-message corpus inside the emulator.

### Verified end-to-end

Pushed all 1,000 AlRajhi messages into the emulator inbox, ran Rescan SMS, then walked the UI:

- **829 / 1000** SMS ingested as transactions (the rest filtered as balance alerts, OTPs, marketing, etc.)
- **551 system categorization rules seeded** (logged at startup: `I RuleSeed: Seeded 551 categorization rules`)
- **4 recurring patterns auto-detected** (Yaqoot · day 21 · 80 ر.س · 4 occurrences; Dallah Ho · day 22 · 33.35 ر.س · 3 occurrences; BADRIA MO · day 6 · 4 ر.س · 3 occurrences; SAUDI ELECTRIC COMPANY · day 6 · 443 ر.س · 3 occurrences)
- **R-03 confirm sheet** opens with `Monthly` preselected and the inferred day-of-month populated; the category picker scrolls Arabic + English labels with an in-sheet search box
- **R-04 Active section** appears after creation with a "1 active subscription(s) · Monthly total · 80 ر.س" header
- **Active → Paused toggle** moves the rule to a `Paused · 1` section; the monthly total drops to 0 and the `Active` header card disappears
- **Save accounts** caption "Saved · N account number stored" confirmed olive-color visible feedback (R-92 from beta.16)

## [0.1.0-beta.4] — 2026-05-25

The "no more 618-pending-entries" release. Six tightly-coupled fixes addressing the headaches a user with hundreds of historical SMS hits on day one.

### Added

- **Auto-confirm when the category is known.** The ingestion pipeline no longer puts every parsed transaction in the pending tray. If the categorizer assigned a category (i.e. Athar already knows McDonald's is a restaurant), the transaction goes straight to CONFIRMED. Low-confidence parses (< 0.50) are auto-DISMISSED. Self-transfers always go to PENDING so the user labels them as savings vs regular. No more triaging 600 obvious entries.
- **Bulk actions in the pending tray.** When the tray has ≥ 5 items, three buttons appear on Today: «تأكيد المؤكدة» (confirm all with confidence ≥ 0.85), «تجاهل المشكوك فيه» (dismiss everything < 0.70), «تجاهل الكل» (nuke the tray). Backed by new repository methods `confirmAllConfident`, `dismissAllLowConfidence`, `dismissAllPending`.
- **Real dates from SMS bodies.** Every bank's `Date:` / `On:` / `at:` line is now parsed (`DateExtraction.parseSmsDate`) — supports all seven shapes in the corpus (`YYYY-MM-DD HH:MM`, `DD-MM-YYYY HH:MM`, `YY-MM-DD HH:MM`, `DD/MM/YYYY HH:MM`, `DD/MM/YY HH:MM`, `YY-M-D`, `D\M\YY HH:MM`). Historical SMS now get historical dates instead of all landing as "today" — Today screen totals are real again.
- **Self-transfer detection (savings moves).** Settings → "حساباتك الخاصة" lets the user list their own account-number tails (`0930, 4268`). Any parsed transfer touching one of those numbers is tagged "تحويل داخلي · ادخار محتمل" with a confirm-this-is-savings prompt in the pending tray, instead of being filed as a generic outgoing payment that skews the budget.
- **Editable budget targets on Plan.** Tap any budget row → bottom sheet → enter monthly target → save. The infrastructure was already wired; the row click now opens the editor.
- **Investment delete + percentage-based return.** Each pool has a «حذف المجموعة» button with confirmation; each contributor row has a `✕` to remove. Tapping the total-return line opens a bottom sheet to enter return as a percentage of corpus instead of an absolute SAR amount (Athar computes `corpus × pct/100`).
- **Trends — TMOAP-equivalent analysis.** New `IncomeExpenseSavingsCard` shows income/expenses/savings side-by-side with a savings-rate %; new `CategoryComparisonTable` (visible when the user selects «مقارنة») lists every category with this-period vs last-period totals plus the SAR delta and % change, mirroring TMOAP's Historical Comparison sheet. Added a 4th period segment «مقارنة».
- **Settings → "إعادة فحص الرسائل" / "Re-scan messages"** — one-tap recovery from polluted pending trays. Wipes every PENDING transaction and re-runs the SMS backfill with the latest templates. Confirmed transactions are untouched.

## [0.1.0-beta.3] — 2026-05-25

### Fixed

- **Promotional SMS from unknown senders were being ingested as transactions.** The earlier `UniversalAmountTemplate` matched ANY sender via `Regex(".+")`, so a marketing shortcode shouting "Earn 10,000 SAR cashback!" became a pending transaction. The universal template is now restricted to `KnownBankSenders.builtIn`. Any sender ending in the Saudi-CITC `-AD` suffix (`AlRajhiB-AD`, `eXtra-AD`, `JARIR-AD`, …) is hard-blocked because that suffix is reserved for advertising channels by the regulator. Loyalty programs (`mokafaa`), OTP-only senders (`FoodicsOTP`), and prize-contest senders (`stcplay-AD`) are explicitly in `hardBlocked`.

### Added

- **Real-format bank templates from a 1,000-message corpus.** Rewrote the Al Rajhi / STC Bank / D360 / Barq parsers against actual SMS exports (`docs/sms-corpus-analysis.md`, `docs/all-senders-analysis.md`). New templates: `Online Purchase`, `PoS purchase`, `Reverse Transaction` (refund), `Debit Internal Transfer`, `Debit Transfer Local` (SARIE), `Credit Transfer Local` (salary inbound), `Bill Payment`, `Deposit: Saving Account Monthly Profit`, `Credit Card:Payment`, `Loan Instalment`, `Transfer Between Your Accounts` for Al Rajhi; `Internal incoming/outward transfer`, `Outward transfer (SARIE)`, `Online Purchase Transaction`, `Pay qattah` for STC Bank; `Online Purchase`, `International Purchase`, local `Purchase`, `Account Funding`, `Incoming Transfer`, `International transfer` for D360; `Online Purchases`, `POS International Purchase`, `ATM Withdrawal`, `Debit Transfer Internal`, `Credit transfer Local` for Barq. Multi-currency handled correctly — the SAR-in-parens value wins.
- **GlobalBankIgnoreTemplate.** Cross-bank content filter for OTP codes, beneficiary additions/activations, scheduled maintenance, card-activation notices, marketing language (`Tasaheal`, `Buy X Get Y`, `Shukrans`, `Earn X cashback`, prize draws, Arabic `جوائز`/`موافقة فورية`/`نقاط مكافأة`/`تطبق الشروط`/`تقسيط`). Anything that matches a pattern returns `Ignored` *before* template parsing runs, so wasted regex work is skipped and the audit log stays clean.
- **Default merchant catalog (`seed_merchant_catalog.json`).** ~200 substring→category mappings covering McDonald's / KFC / Albaik / Herfy / Kudu / Shawarmer / Pizza Hut / Starbucks / Dunkin / Tim Hortons / Roasting House / Barn's / %Arabica / Carrefour / Lulu / Othaim / Tamimi / Panda / Bindawood / Danube / Sarawat / Nahdi / Aldrees / Saso / Petromin / STC / Mobily / Zain / Uber / Careem / DiDi / TfL / Jarir / Extra / Saco / IKEA / Amazon / Noon / Shein / Zara / Uniqlo / H&M / Netflix / Spotify / Apple / iCloud / OpenAI / Anthropic / GitHub / Shahid / Starzplay / Airbnb / Booking.com / Agoda / Flynas / Saudia / Emirates / Saudi Electric / National Water / Tesco / Conad / Penny Market / and ~140 more. Each entry has a confidence so the user sees which auto-classifications are guesses vs certain.
- **ADR-006 (`docs/adr/ADR-006-spam-resistant-ingestion.md`).** Documents the layered filter pipeline (sender allow-list → `-AD` suffix block → content patterns → bank templates → user templates → universal fallback) and the sustainability guarantees for future users so this class of bug doesn't return.
- **Test suite expansion.** 39 new corpus-pinned tests in `SmsCorpusTest.kt` plus 6 sustainability tests (`-AD` suffix blocking, `mokafaa` blocked, OTP-only sender blocked, Tasaheal pitch ignored, Arabic financing offer ignored, prize contest ignored). Total parser tests: 59.

## [0.1.0-beta.2] — 2026-05-25

### Added

- **User-defined bank templates** — Settings → "قوالب البنوك" lets the user teach Athar new SMS formats from inside the app. Paste a sample, name the bank, point to the words that surround the amount/merchant/recipient, save. Stored locally in the encrypted Room DB (new `user_template` table, migration `2 → 3`). The parser observes the template list as a Flow and rebuilds itself live — no app restart. Each saved template's anchor strings, sender, and transaction type appear at the top of the screen with a delete button.
- **Multi-bank parser expansion** — `PoS purchase / Amount:X SAR / Card:Y / At: MERCHANT` Al-Rajhi format, internal-transfer Al-Rajhi format, STC Pay (outgoing/incoming/ignore), Alinma, D360, Barq, Riyad Bank, SNB, ANB. Plus a universal multi-currency / multi-language fallback (SAR/AED/USD/EUR/GBP/INR/PKR/TRY/EGP/KWD/QAR/BHD/OMR/JOD; Arabic/English/Spanish/French/Turkish/Urdu/Hindi). Messages from the screenshot's "552 failed" pile now parse cleanly.
- **Approach-limit warnings on Plan** — each budget row computes a `LimitState` from `actual ÷ target` (70% Watch / 90% Tight / >100% Over). A new strip near the top of Plan reads "X, Y, Z · اقتربت من الحد" calling out categories nearing their cap. The variance pill switches between olive/dust/ember accordingly.
- **`-Pathar.seed=true` build flag** — wires a `seeded/` source set + BuildConfig boolean for a private personal build seeded with the user's TMOAP workbook data (Categories / Expenses / Income / Budget Targets / Wishlist / Family Investments). Extracted via `scripts/extract_tmoap.py`. The seeded source set is gitignored — never ships to a public build.
- **ADR-005** — three-layer parser architecture (bank-specific templates → user-defined templates → universal heuristic). Documents the future on-device ML option (MobileBERT-NER INT8) without committing to it.

### Fixed

- **Number regex truncating long amounts** — the parser's number regex was matching `135` from `1350` because the first alternation didn't require a thousands separator. Fixed to `\d{1,3}(?:[ ,]\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?` — first alternation now requires at least one separator, so plain digit-runs fall to the second alternation and stay whole.
- **Plan → Wishlist crash** — `IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints` because `LazyColumn` was nested inside a `Column.verticalScroll`. Replaced with `Column { state.items.forEach }` since the count is small and the outer scroll already handles overflow.

## [0.1.0-beta.1] — 2026-05-24

### Fixed

- **Launch crash on personalFullSms release build** — release APKs default to `extractNativeLibs="false"`, which prevented SQLCipher's `libsqlcipher.so` from loading and killed the process during DI graph construction. Forced `extractNativeLibs="true"` in the manifest, wrapped Room/SQLCipher initialization in a `runCatching { … }.onFailure { write crash.log }` block, and planted Timber + a global `Thread.UncaughtExceptionHandler` in **all** builds (not just debug) so any future on-device crash is recoverable via `adb shell run-as com.athar.personal cat files/crash.log` or shared from the file picker.

### Added

- **Settings → About card** — credits Waleed Alhamed (walhamed.com) as developer, names TMOAP (The Measure of a Plan) as the spreadsheet that inspired Athar's Plan screen, and surfaces the build's `versionName`.
- **README credits section** — same attribution made public.

## [0.1.0-beta] — 2026-05-24

The first build that's actually usable as a daily driver. Built across 24 disciplined waves of spec → code → test → verify.

### Added

- **Foundations** — 15 Gradle modules with convention plugins, version catalog, included `build-logic` build. Multi-flavor: `personalFullSms` (sideload) + `storeSafe` (Play-Store-eligible).
- **Today screen** — landmark net-flow number with count-up animation, pending tray, FAB add manual transaction, recent feed with list animations.
- **Trends screen** — period selector (month/3m/year), comparison-to-previous arrow + delta %, top-8 category bar chart, **tap-to-drilldown 12-month sheet** for any category.
- **Plan screen** — segmented sub-tabs: Budget (target/actual/variance pills), Wishlist (savings-capacity math + NOW/WAIT/INFEASIBLE status), Family Investments (proportional share % + return).
- **Onboarding** — 3-page flow (welcome → privacy → SMS perm) with dot-indicator nav.
- **SMS ingestion** — BroadcastReceiver + NotificationListenerService, source-agnostic `RawIngestEvent` pipeline, 90-day historical backfill, idempotent re-delivery.
- **Al Rajhi parser** — Arabic + English templates for purchase / transfer-out / deposit / balance-alert, Arabic-Indic digit normalization, confidence scoring.
- **Categorizer** — 5-tier rule engine (exact → substring → regex → classifier → unknown), 45 Saudi merchant seed rules, learn-from-correction loop with priority-200 user rules.
- **Encryption at rest** — SQLCipher 4.6.1 with 256-bit DB key wrapped by an `AndroidKeyStore`-resident AES-256-GCM master key (ADR-003).
- **Backup + restore** — AES-256-GCM file format, PBKDF2-HMAC-SHA256 600k-iteration KDF, JSON snapshot of all tables, gzip-compressed.
- **CSV import + export** — RFC-4180-quoting parser, category matching by Arabic or English name, multi-format date parsing.
- **Settings** — SMS permission card, backfill trigger, encrypted backup buttons, CSV exchange, Hijri date toggle, categories CRUD (rename / archive / reorder), SMS audit log, Activity log.
- **SMS audit log** — every parsed/failed/ignored SMS retained forever, filterable, never deleted (§4.6 Master Brief promise).
- **Activity log** — every transaction CREATE/UPDATE/DELETE/CONFIRM/DISMISS auto-recorded with timestamp.
- **Hijri date toggle** — landmark caption gains "· 1447/11 هـ" alongside Gregorian when enabled.
- **Brand** — Athar wordmark adaptive icon, Thmanyah typeface (sans + serif-display + serif-text), brand gold accent (#B8893C) replacing the placeholder ember.
- **Tests** — 53 pure-JVM unit tests across `MoneyTest`, `PeriodShiftTest`, `HijriDateTest`, `AtharCryptoTest`, `NormalizeTest`, `AlRajhiTemplatesTest`, `RuleEngineTest`, `WishlistCalcTest`, `InvestmentsCalcTest`, `BudgetCalcTest`.
- **E2E** — 10 Maestro flows covering onboarding, add-tx, edit-with-learn, set-target, add-wishlist, toggle-Hijri, sms-to-pending, backfill, export, smoke-nav.
- **Documentation** — `README.md`, `GETTING_STARTED.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, four ADRs.

### Known limitations

See [ADR-004](docs/adr/ADR-004-mvp-status.md). Notably:

- XLSX direct import was deferred in beta.21; transaction-grid XLSX import is now supported in beta.24.
- TFLite merchant classifier deferred (rule engine covers ~90% of cases).
- Locale toggle deferred (Arabic-first per brief).
- Paparazzi snapshot baselines need a first record run.
- Macrobenchmarks need a real device.

[Unreleased]: https://github.com/wa1939/athar/compare/v0.1.0-beta.24...HEAD
[0.1.0-beta.24]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.24
[0.1.0-beta.23]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.23
[0.1.0-beta.22]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.22
[0.1.0-beta.21]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.21
[0.1.0-beta.20]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.20
[0.1.0-beta.19]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.19
[0.1.0-beta.18]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.18
[0.1.0-beta.17]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.17
[0.1.0-beta.1]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta.1
[0.1.0-beta]: https://github.com/wa1939/athar/releases/tag/v0.1.0-beta
