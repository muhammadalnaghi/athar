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
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var merchantCatalog: Lazy<MerchantCatalogRepository>

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
                runDeferredCatalogJobs(merchantCatalog)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Timber.w("foreground catalog refresh skipped")
            }
        }
    }
}

/** lifecycleScope launches on Main; move construction as well as calls off that thread. */
internal suspend fun runDeferredCatalogJobs(merchantCatalog: Lazy<MerchantCatalogRepository>) =
    withContext(Dispatchers.IO) {
        val catalog = merchantCatalog.get()
        catalog.initialize()
        catalog.refresh()
    }
