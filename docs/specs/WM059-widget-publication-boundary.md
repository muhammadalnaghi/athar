# WM059 — Widget publication boundary at refreshNow

## Goal
Close the restore-publication gap at `updateAtharWidgets` (used by `WidgetRefresher.refreshNow()`): when a strong refresh returns, the latest encrypted snapshot must already be **published to the widget host**, not merely scheduled. Verified root cause: `GlanceAppWidget.updateAll` (pinned `androidx.glance:glance-appwidget:1.1.1`) returns after session scheduling — `AppWidgetSession` composes asynchronously, so process teardown in that window leaves the host on stale frames after an authenticated restore.

## Scope (verified from the pinned cached artifact)
Strong one-shot publication covers **Monthly and Today only**. Verified simplification: both render trees use solely `actionStartActivity(appLaunchComponent(context))` — one-shot safe. **Pending is excluded by policy**: its rows use `actionRunCallback`, whose callback identity/routing is not guaranteed from one-shot `GlanceRemoteViews` frames; it keeps the original session scheduling path, untouched, and is reported `DeferredByPolicy` — never forged.

## Approach
`GlanceRemoteViews` (public, `@ExperimentalGlanceRemoteViewsApi`, verified in the cached 1.1.1 AAR) `compose(context, size, …, content)` runs the same RemoteViewsTranslator pipeline synchronously and returns `RemoteViewsCompositionResult.remoteViews`. Per provider:
1. Enumerate the exact ids via `AppWidgetManager.getAppWidgetIds(ComponentName(receiver))`.
2. Load the snapshot once through `loadWidgetState` around the **raw** `WidgetDataLoader` loader (single wrap; fail-closed).
3. Compose the provider's **full shared content wrapper** — theme, background, padding, launch action — invoked as internal members on typed `MonthlyWidget`/`TodayWidget` instances (no star-projected `Spec<*>`, no double `WidgetLoad` wrap), pinned to `Ready`.
4. Resolve each id's size honestly: per-ID `getAppWidgetOptions` `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` are **dp**; provider-info `minWidth/minHeight` are client-converted **pixels** and are only used after dividing by `displayMetrics.density`. If neither resolves, that id fails truthfully — no guessed size. Then write via `AppWidgetManager.updateAppWidget`, a synchronous binder write, before returning.

Serialize whole refresh passes so a snapshot captured by an earlier pass cannot publish after the restored pass. Do not enqueue a session redraw after a provider is fully published: it can race with and replace the ready frame during teardown. Use session fallback only for failed/partial providers; keep Pending on its original session path. Scheduling never counts as publication.

## Outcomes / telemetry
`WidgetPublicationReport` (monthly/today/pending) with typed `WidgetPublicationOutcome`: `FullyPublished`, `PublishedWithFailures` (per-id lists — one successful id never hides another id's failure), `LoadFailed`, `RenderFailed`, `EnumerationFailed`, `NoInstances`, `DeferredByPolicy`. Recorded in `WidgetPublicationTelemetry`; ids and status only, never financial data.

## Edges
Fail-closed: load failure publishes nothing (host keeps last-good; session renders honest `Unavailable`). Per-id failure (options/provider-info missing, translation or binder failure) keeps that id's last-good frame and is reported. `CancellationException` propagates everywhere. Empty ids → `NoInstances`. No `updateAll`, delay, or scheduling is reported as publication.

## Acceptance
Parent admission preserves the original spec-first sequence. A focused `:feature:widgets:testDebugUnitTest` (offline) proves load-before-publish ordering, fail-closed defer, placeholder/unavailable defer, empty-id defer, id identity, typed per-provider reporting, per-id continuation, complete failure identities and cancellation. Placed-instance host frames and immediate-teardown acceptance are parent-owned on the verified disposable emulator; scheduling alone is not acceptance. Pending, all race scenarios, all sizes and physical-phone acceptance remain out of this slice.
