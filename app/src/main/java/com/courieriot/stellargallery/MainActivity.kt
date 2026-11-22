package com.courieriot.stellargallery

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.courieriot.stellargallery.ui.StellarRoot
import com.courieriot.stellargallery.ui.theme.StellarTheme

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StellarTheme {
                StellarRoot(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Lock the vault whenever the app leaves the screen.
        if (!isChangingConfigurations) vm.lockVault()
    }
}
