package com.athar.core.designsystem.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** Resource locale is reactive and survives configuration changes; process defaults do not. */
@Composable
fun currentUiLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * App-wide display flags hoisted at the AtharApp scaffold so any screen can read them
 * without threading the preference repository through each feature ViewModel.
 *
 * Default: Hijri off (Gregorian only). The wiring lives in app/AtharApp.
 */
val LocalHijriEnabled = staticCompositionLocalOf { false }

/**
 * The user's chosen display currency (ISO-4217). Defaults to SAR (Saudi-first) so the
 * design system renders correctly in previews and tests without the app wiring it in.
 * The real app overrides this from [com.athar.core.domain.repo.UserPreferencesRepository]
 * at [com.athar.ui.AtharApp].
 */
val LocalDisplayCurrency = staticCompositionLocalOf { "SAR" }
