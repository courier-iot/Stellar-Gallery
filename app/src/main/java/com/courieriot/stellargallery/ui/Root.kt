package com.courieriot.stellargallery.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.courieriot.stellargallery.BuildConfig
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Starfield

fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun hasMediaPermission(context: Context): Boolean {
    fun g(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 -> g(Manifest.permission.READ_MEDIA_IMAGES) ||
            g(Manifest.permission.READ_MEDIA_VIDEO) ||
            g(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> g(Manifest.permission.READ_MEDIA_IMAGES) || g(Manifest.permission.READ_MEDIA_VIDEO)
        else -> g(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
fun StellarRoot(vm: MainViewModel, onCloseExternal: () -> Unit = {}) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMediaPermission(context)
        if (granted) vm.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = hasMediaPermission(context)
        if (now != granted) granted = now
        if (now) vm.scheduleRefresh()
    }
    LaunchedEffect(granted) { if (granted) vm.refresh() }

    // System confirm dialogs (delete, favorite, move...)
    val pending by vm.pendingRequest.collectAsStateWithLifecycle()
    val requestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.onRequestResult(res.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        if (!p.launched) {
            p.launched = true
            runCatching { requestLauncher.launch(IntentSenderRequest.Builder(p.sender).build()) }
                .onFailure { vm.onRequestResult(false) }
        }
    }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Block screenshots / recents preview while inside the vault.
    val entry by nav.currentBackStackEntryAsState()
    val secure = entry?.destination?.route?.startsWith("vault") == true
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(secure) {
        val w = activity?.window ?: return@LaunchedEffect
        if (secure) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    val update by vm.updateAvailable.collectAsStateWithLifecycle()
    val external by vm.external.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Starfield()
        if (!granted && external == null) {
            PermissionScreen { permLauncher.launch(mediaPermissions()) }
        } else if (granted) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") { HomeScreen(vm, nav) }
                composable(
                    "album/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { e -> AlbumScreen(vm, nav, e.arguments?.getLong("id") ?: 0L) }
                composable(
                    "viewer/{source}/{index}",
                    arguments = listOf(
                        navArgument("source") { type = NavType.StringType },
                        navArgument("index") { type = NavType.IntType },
                    ),
                ) { e ->
                    ViewerScreen(
                        vm, nav,
                        source = e.arguments?.getString("source") ?: "all",
                        startIndex = e.arguments?.getInt("index") ?: 0,
                    )
                }
                composable("editor") { EditorScreen(vm, nav) }
                composable("vault") { VaultScreen(vm, nav) }
                composable(
                    "vaultViewer/{index}",
                    arguments = listOf(navArgument("index") { type = NavType.IntType }),
                ) { e -> VaultViewerScreen(vm, nav, e.arguments?.getInt("index") ?: 0) }
                composable("share") { ShareScreen(vm, nav) }
                composable("settings") { SettingsScreen(vm, nav) }
                composable("search") { SearchScreen(vm, nav) }
                composable(
                    "slideshow/{source}/{index}",
                    arguments = listOf(
                        navArgument("source") { type = NavType.StringType },
                        navArgument("index") { type = NavType.IntType },
                    ),
                ) { e ->
                    SlideshowScreen(
                        vm, nav,
                        source = e.arguments?.getString("source") ?: "all",
                        startIndex = e.arguments?.getInt("index") ?: 0,
                    )
                }
                composable("duplicates") { DuplicatesScreen(vm, nav) }
                composable("storage") { StorageScreen(vm, nav) }
                composable("map") { MapScreen(vm, nav) }
                composable("videoEdit") { VideoEditScreen(vm, nav) }
                composable("backup") { BackupScreen(vm, nav) }
            }
        }
        external?.let { ext ->
            ExternalViewer(ext, onClose = { vm.closeExternal(); onCloseExternal() })
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp),
        )
    }

    update?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Update available") },
            text = { Text("Stellar Gallery v${r.version} is out. You have v${BuildConfig.VERSION_NAME}.") },
            confirmButton = {
                Button(onClick = { openUrl(context, r.url); vm.dismissUpdate() }) { Text("Open release") }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Later") } },
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.PhotoLibrary, null, tint = Neon, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        BrandTitle()
        Spacer(Modifier.height(12.dp))
        Text(
            "Stellar Gallery needs access to your photos and videos to show them. Everything stays on this phone.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow access") }
    }
}
