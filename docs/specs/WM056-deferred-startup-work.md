# WM056 — Deferred startup work (cold-start readiness)

## Goal

Remove the evidence-backed eager/blocking work from the cold-start critical path with the
smallest application-level change, preserving encrypted-data security (fail-closed
`DbKeyManager`), truthful loading (existing `StartupLoading` frame in `AtharApp`), saved
money, locale behavior, seeding, and cancellation semantics.

## Observed evidence (parent, API 35 real app)

- Warm start ≈ 5150 ms.
- `am force-stop` → `am start -W`: `Status: timeout`, `LaunchState: UNKNOWN`,
  `WaitTime: 13057 ms`, then Today and the saved portfolio reopen correctly.

This proves a slow-readiness counterexample, not its root cause.

## Source-level root cause

`AtharApplication` declared `@Inject lateinit var dataInitializer: AppDataInitializer`.
With `@HiltAndroidApp`, Hilt's generated `inject()` runs on the **main thread inside
`super.onCreate()`**, before the app's own `onCreate` body. Constructing
`AppDataInitializer` constructs its constructor-injected `CategorySeed/AccountSeed/
RuleSeed/TmoapSeed` → Room DAOs → the `AtharDatabase` singleton via
`DataModule.provideDatabase`, which synchronously executes
`System.loadLibrary("sqlcipher")`, `DbKeyManager.getOrCreateDbKey()` (Android Keystore)
and `SupportOpenHelperFactory(...)` — all before the first frame. The existing
`scope.launch { dataInitializer.initialize(...) }` only deferred the seeding *call*;
the dependency *construction* had already happened at injection time.

Secondary, same shape: `MainActivity` field-injected `MerchantCatalogRepository`
directly, constructing the catalog graph (JCA verifier, DataStore, transport) during
activity injection, ahead of first content. This chain does **not** touch Room.

## Change (app capsule only)

1. `AtharApplication.kt`: `dataInitializer` is now `dagger.Lazy<AppDataInitializer>`;
   the seeding+scheduling block moved verbatim into an internal, unit-testable
   `runDeferredStartupJobs(...)` invoked from the existing background scope
   (SupervisorJob + `Dispatchers.Default` + `CoroutineExceptionHandler`), with the
   actual lazy dependency request and seeding inside `withContext(Dispatchers.IO)`.
2. `MainActivity.kt`: `merchantCatalog` is now `dagger.Lazy<MerchantCatalogRepository>`;
   `.get()`, initialization and refresh run inside `runDeferredCatalogJobs` on IO.
   Merely putting `.get()` inside `lifecycleScope.launch` is insufficient: that
   coroutine normally begins on Main before the first frame. Parent regression
   tests verify construction and calls happen off the calling thread.

No DI modules, database, crypto, repository, seeding or Gradle changes. The singleton
is still constructed exactly once; `DbKeyManager` fail-closed behavior is untouched
(this application-level dependency request moves to IO). Seeding, TMOAP flag, catalog
opt-in fence and StoreSafe offline guarantees are unchanged.

## Tests

`app/src/test/kotlin/com/athar/startup/DeferredStartup056Test.kt` pins the fix:
structural guards (both fields must stay `dagger.Lazy`, no eager field may reappear)
and behavioral guards (construct-once → seed → schedule order; failure and
cancellation propagate, never scheduling after failure).

Invocation (the one allowed): `android-env.sh ./gradlew
:app:testPersonalFullSmsDebugUnitTest --tests '*Startup056*' --offline
-Pathar.seed=false --max-workers=1 --no-daemon …` — actual XML under
`app/build/test-results/testPersonalFullSmsDebugUnitTest/`.

## Limitations (deliberate, honest)

- JVM tests prove wiring and semantics, **not** device latency. Actual cold start is
  verified by the parent on the emulator.
- If a UI path (e.g. Today's ViewModel construction) requests DAOs before the
  background job finishes, Dagger's singleton lock makes the main thread wait only
  for the remainder; this slice does not guarantee every consumer's first database
  request is off-main. Warm-start
  cost is unaffected by this change (process already alive); residual warm cost lives
  in composition/first-query paths outside this capsule.
