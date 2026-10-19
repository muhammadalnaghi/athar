package com.athar

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.repo.MerchantCatalogRepository
import com.athar.ui.AtharApp
import com.athar.ui.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var merchantCatalog: MerchantCatalogRepository

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AtharTheme {
                AtharApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // ENH-009: coalesced foreground catalog check. Never blocks startup, UI,
        // or DB seeding, and is a no-op unless the user opted in and the cached
        // knowledge is stale — the repository's transport guard keeps StoreSafe
        // fully offline. No periodic job is created here.
        lifecycleScope.launch {
            try {
                merchantCatalog.initialize()
                merchantCatalog.refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Timber.w("foreground catalog refresh skipped")
            }
        }
    }
}
