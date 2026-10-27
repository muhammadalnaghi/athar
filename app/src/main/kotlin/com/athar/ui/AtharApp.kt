package com.athar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import android.app.Activity
import android.content.ContextWrapper
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.athar.core.designsystem.component.AtharBottomBar
import com.athar.core.designsystem.component.AtharBottomBarItem
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.LocalDisplayCurrency
import com.athar.core.designsystem.display.LocalHijriEnabled
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.feature.plan.PlanScreen
import com.athar.feature.settings.AccountsScreen
import com.athar.feature.settings.ActivityLogScreen
import com.athar.feature.settings.CategoriesScreen
import com.athar.feature.settings.IncomeSourcesScreen
import com.athar.feature.settings.RecurringRulesScreen
import com.athar.feature.settings.SettingsScreen
import com.athar.feature.settings.SmsAuditScreen
import com.athar.feature.settings.SmsImportScreen
import com.athar.feature.settings.UserTemplatesScreen
import com.athar.feature.today.HistoryCategoryFilter
import com.athar.feature.today.HistoryScreen
import com.athar.feature.today.TodayScreen
import com.athar.feature.trends.TrendsScreen
import com.athar.ui.onboarding.OnboardingScreen
import com.athar.ui.onboarding.OnboardingViewModel

@Composable
fun AtharApp() {
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboarded by onboardingViewModel.onboardingComplete.collectAsStateWithLifecycle()

    val appPrefs: AppPrefsViewModel = hiltViewModel()
    val hijriEnabled by appPrefs.hijriEnabled.collectAsStateWithLifecycle()
    val displayCurrency by appPrefs.displayCurrency.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val onboardingSnapshot = onboarded
    val hijriSnapshot = hijriEnabled
    val currencySnapshot = displayCurrency
    if (onboardingSnapshot == null || hijriSnapshot == null || currencySnapshot == null) {
        StartupLoading()
        return
    }

    val startDestination = remember {
        if (onboardingSnapshot) Routes.Today else Routes.Onboarding
    }
    val navController = rememberNavController()
    val current by navController.currentBackStackEntryAsState()
    val routeName = current?.destination?.route.orEmpty()
    val isOnboarding = routeName.endsWith("Onboarding")
    val isSettingsArea = routeName.endsWith("Settings") ||
        routeName.endsWith("Categories") ||
        routeName.endsWith("SmsAudit") ||
        routeName.endsWith("ActivityLog") ||
        routeName.endsWith("UserTemplates") ||
        routeName.endsWith("RecurringRules") ||
        routeName.endsWith("Accounts") ||
        routeName.endsWith("History") ||
        routeName.endsWith("HistoryRepeatedBacklog")
    val theme = AtharTheme

    val layoutDirection = if (LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalHijriEnabled provides hijriSnapshot,
        LocalDisplayCurrency provides currencySnapshot,
        LocalLayoutDirection provides layoutDirection,
    ) {
        Scaffold(
            topBar = {
                if (!isOnboarding && !isSettingsArea) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = theme.spacing.s, vertical = theme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SettingsGear(onClick = { navController.navigate(Routes.Settings) })
                    }
                }
            },
            bottomBar = {
                if (!isOnboarding) {
                    AtharBottomBar(
                        items = TopLevelTabs,
                        selectedKey = current.topLevelTabKey().orEmpty(),
                        onSelect = { key ->
                            // Do not save Settings/details as the restored leaf of a main tab.
                            while (navController.currentBackStackEntry.topLevelTabKey() == null &&
                                navController.previousBackStackEntry != null
                            ) {
                                if (!navController.popBackStack()) break
                            }
                            val route: Any = when (key) {
                                "today" -> Routes.Today
                                "trends" -> Routes.Trends
                                "plan" -> Routes.Plan
                                else -> Routes.Today
                            }
                            navController.navigate(route) {
                                popUpTo(Routes.Today) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
        ) { padding: PaddingValues ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                composable<Routes.Onboarding> {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Routes.Today) {
                                popUpTo(Routes.Onboarding) { inclusive = true }
                            }
                        },
                    )
                }
                composable<Routes.Today> {
                    TodayScreen(
                        onOpenHistory = { navController.navigate(Routes.History) },
                        onOpenRepeatedBacklog = { navController.navigate(Routes.HistoryRepeatedBacklog) },
                    )
                }
                composable<Routes.Trends> { TrendsScreen() }
                composable<Routes.Plan> { PlanScreen() }
                composable<Routes.Settings> {
                    SettingsScreen(
                        onOpenCategories = { navController.navigate(Routes.Categories) },
                        onOpenSmsAudit = { navController.navigate(Routes.SmsAudit) },
                        onOpenActivityLog = { navController.navigate(Routes.ActivityLog) },
                        onOpenUserTemplates = { navController.navigate(Routes.UserTemplates) },
                        onOpenRecurringRules = { navController.navigate(Routes.RecurringRules) },
                        onOpenAccounts = { navController.navigate(Routes.Accounts) },
                        onOpenIncomeSources = { navController.navigate(Routes.IncomeSources) },
                        onOpenSmsImport = { navController.navigate(Routes.SmsImport) },
                        onOpenHistory = { navController.navigate(Routes.History) },
                        onApplyLocale = { tag ->
                            LocaleHelper.persist(context, tag)
                            activity?.recreate()
                        },
                    )
                }
                composable<Routes.UserTemplates> {
                    UserTemplatesScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.RecurringRules> {
                    RecurringRulesScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.Accounts> {
                    AccountsScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.IncomeSources> {
                    IncomeSourcesScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.SmsImport> {
                    SmsImportScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.History> {
                    HistoryScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.HistoryRepeatedBacklog> {
                    HistoryScreen(
                        onBack = { navController.popBackStack() },
                        initialCategoryFilter = HistoryCategoryFilter.REPEATED_UNCATEGORIZED,
                        initialSelectionMode = true,
                    )
                }
                composable<Routes.Categories> {
                    CategoriesScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.SmsAudit> {
                    SmsAuditScreen(onBack = { navController.popBackStack() })
                }
                composable<Routes.ActivityLog> {
                    ActivityLogScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun StartupLoading() {
    val theme = AtharTheme
    Box(
        modifier = Modifier.fillMaxSize().background(theme.colors.parchment),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = stringResource(com.athar.R.string.app_name),
            style = theme.typography.title,
            color = theme.colors.muted,
        )
    }
}

@Composable
private fun SettingsGear(onClick: () -> Unit) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .size(MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.parchment)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = "⋯", style = theme.typography.title, color = theme.colors.muted)
    }
}

private val TopLevelTabs: List<AtharBottomBarItem> = listOf(
    AtharBottomBarItem(key = "today", labelEn = "Today", labelAr = "اليوم"),
    AtharBottomBarItem(key = "trends", labelEn = "Trends", labelAr = "النمط"),
    AtharBottomBarItem(key = "plan", labelEn = "Plan", labelAr = "الخطة"),
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun NavBackStackEntry?.topLevelTabKey(): String? {
    val route = this?.destination?.route.orEmpty()
    return when {
        route.endsWith("Today") -> "today"
        route.endsWith("Trends") -> "trends"
        route.endsWith("Plan") -> "plan"
        else -> null
    }
}
