package com.courieriot.stellargallery

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.courieriot.stellargallery.data.Prefs
import com.courieriot.stellargallery.ui.StellarRoot
import com.courieriot.stellargallery.ui.theme.ThemeState
import com.courieriot.stellargallery.ui.theme.StellarTheme

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.get(this).let { ThemeState.apply(it.accent, it.pureBlack) }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            StellarTheme {
                StellarRoot(vm, onCloseExternal = {
                    // Opened from another app → go back to that app.
                    if (vm.launchedExternally) finish()
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            vm.openExternal(uri, intent.type ?: contentResolver.getType(uri))
        }
    }

    override fun onStop() {
        super.onStop()
        // Lock the vault whenever the app leaves the screen.
        if (!isChangingConfigurations) vm.lockVault()
    }
}
