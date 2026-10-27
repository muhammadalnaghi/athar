# WM065 — Deferred onboarding import dependency and IO ownership

Related backlog: M-14 (existing-workbook import) and M-15 (onboarding first import). This is a narrow continuation of WM056's deferred startup work, not a new import format, onboarding redesign or financial-recording policy.

## Goal and observed cause
`AtharApp` creates `OnboardingViewModel` on every launch to read whether onboarding is complete. Its constructor currently requires `CsvImportTrigger`, whose implementation depends on transaction/category repositories and therefore the encrypted database graph. Fully onboarded users should not construct that unused import graph on Main. The existing `importCsv` coroutine also opens the Android document stream on Main. The parent measured the accepted main17b cold launch independently; that observation establishes a baseline, not attribution of all latency to this dependency.

## Inputs and outputs
Keep the ContentResolver/Uri entry point, four-page navigation, preference flow and null-until-read readiness unchanged. Change the injected dependency to `dagger.Lazy<CsvImportTrigger>`, retaining the existing direct-construction test seam. Only an actual import requests the dependency. A small suspending IO helper owns opening, importer construction/use and stream closure. The ViewModel continues to publish Working then the original imported/skipped counts or declared failure reason on its caller context. Missing/unopenable documents receive the existing fixed open-file error; unexpected failures receive fixed non-payload read-file copy, never an exception message.

## Edges
Use structured `viewModelScope` and `withContext(Dispatchers.IO)`, not detached work, sleeps or a fake ready state. Propagate cancellation. Standard InputStream.use closes even when importer construction or execution throws; the real importer already closes its input and Closeable closure is idempotent. Preserve byte-exact result values, original importer semantics, categories, account selection and stored financial state. Do not alter the existing unread-preference gate or force onboarding on retained data.

## Acceptance
Execute a structural regression before changing the eager field, then focused IO/open/failure/cancellation/closure controls and retained preference-readiness cases. Compile both unseeded flavors and bind all accepted prior identities to the new source. Parent alone performs the same read-only cold launch on the owned API35 emulator after a nondestructive identity-checked update, verifies retained financial content and rollout OFF, and reports timings without an invented device speedup. This slice does not establish first-frame/TTFU, full archive performance, every onboarding native import state, minimum API, phone, all 22 capabilities or release acceptance.
