@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.RestartAlt
import com.courieriot.stellargallery.sorter.SearchModelState
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Widgets
import com.courieriot.stellargallery.ui.theme.Accent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.courieriot.stellargallery.BuildConfig
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.SortOrder
import com.courieriot.stellargallery.data.UpdateChecker
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel

@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val sort by vm.sortOrder.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    val group by vm.groupByDate.collectAsStateWithLifecycle()
    val updates by vm.checkUpdates.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    val black by vm.pureBlack.collectAsStateWithLifecycle()
    val memories by vm.showMemories.collectAsStateWithLifecycle()
    val slideSecs by vm.slideshowSeconds.collectAsStateWithLifecycle()
    val strip by vm.stripMetadata.collectAsStateWithLifecycle()
    val hideVault by vm.hideVaultEntry.collectAsStateWithLifecycle()
    val widgetFav by vm.widgetFavoritesOnly.collectAsStateWithLifecycle()
    val sorterOn by vm.sorterEnabled.collectAsStateWithLifecycle()
    val simplified by vm.sorterSimplified.collectAsStateWithLifecycle()
    val sorterState by vm.sorterState.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { Section("Appearance") }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Accent.entries.forEach { a ->
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(a.color)
                                .border(if (a == accent) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { vm.setAccent(a) },
                        )
                    }
                }
            }
            item {
                Text(
                    accent.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            item { SwitchRow(Icons.Outlined.DarkMode, "Pure black (AMOLED)", black, vm::setPureBlack) }

            item { Section("Gallery") }
            item { SettingRow(Icons.Outlined.SwapVert, "Sort order", sort.label) { sortDialog = true } }
            item {
                SettingRow(Icons.Outlined.GridView, "Grid size", "$columns per row (tap, or pinch the grid)") {
                    vm.setGridColumns(if (columns >= 6) 2 else columns + 1)
                }
            }
            item { SwitchRow(Icons.Outlined.CalendarMonth, "Group by date", group, vm::setGroupByDate) }
            item { SwitchRow(Icons.Outlined.History, "Show “On this day” memories", memories, vm::setShowMemories) }
            item {
                SettingRow(Icons.Outlined.Slideshow, "Slideshow speed", "$slideSecs seconds per photo (tap to change)") {
                    val opts = listOf(3, 5, 8, 12)
                    vm.setSlideshowSeconds(opts[(opts.indexOf(slideSecs) + 1).mod(opts.size)])
                }
            }

            item { Section("Photo sorter") }
            item {
                SwitchRow(Icons.Outlined.FolderSpecial, "Simplified folders (fewer, broader albums)", simplified, vm::setSorterSimplified)
            }
            item {
                SwitchRow(Icons.Outlined.AutoAwesome, "Photo sorter (on-device AI)", sorterOn, vm::setSorterEnabled)
            }
            if (sorterOn) {
                item {
                    val s = sorterState.search
                    val subtitle = when (s) {
                        SearchModelState.Ready -> "On · search by what's in a photo (e.g. “orange cat”). Tap to remove."
                        is SearchModelState.Downloading -> "Downloading… ${(s.progress * 100).toInt()}%"
                        is SearchModelState.Failed -> s.message + " · tap to try again"
                        SearchModelState.NotInstalled -> "Off · tap to download (~45 MB) from this app's GitHub releases"
                    }
                    SettingRow(Icons.Outlined.ManageSearch, "Search & sort", subtitle) {
                        when (s) {
                            SearchModelState.Ready -> vm.removeSearchModel()
                            is SearchModelState.Downloading -> Unit
                            else -> vm.installSearchModel()
                        }
                    }
                }
                item {
                    SettingRow(Icons.Outlined.RestartAlt, "Reset what the sorter learned", "Starts suggestions over. Your photos aren't touched.") {
                        confirmReset = true
                    }
                }
            }

            item { Section("Privacy") }
            item { SwitchRow(Icons.Outlined.LocationOff, "Remove location & camera info when sharing", strip, vm::setStripMetadata) }
            item { SwitchRow(Icons.Outlined.VisibilityOff, "Hide the vault icon (long-press the title to open)", hideVault, vm::setHideVaultEntry) }

            item { Section("Widget") }
            item { SwitchRow(Icons.Outlined.Widgets, "Photo frame shows favorites only", widgetFav, vm::setWidgetFavoritesOnly) }

            item { Section("Tools") }
            item { SettingRow(Icons.Outlined.FileCopy, "Find duplicates", "Spot look-alike photos and free up space") { nav.navigate("duplicates") } }
            item { SettingRow(Icons.Outlined.PieChart, "Storage", "What's using space on your phone") { nav.navigate("storage") } }
            item { SettingRow(Icons.Outlined.Map, "Photo map", "Your photos on OpenStreetMap") { nav.navigate("map") } }
            item { SettingRow(Icons.Outlined.CloudUpload, "Backup to PC / NAS", "Optional WebDAV backup on your own network") { nav.navigate("backup") } }
            item {
                SettingRow(Icons.Outlined.Image, "Photo access", "Change which photos the app can see") {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                }
            }

            item { Section("Updates") }
            item { SwitchRow(Icons.Outlined.Update, "Check for updates on launch", updates, vm::setCheckUpdates) }
            item {
                SettingRow(Icons.Outlined.SystemUpdate, "Check now", "You have v${BuildConfig.VERSION_NAME}") {
                    vm.checkForUpdates(manual = true)
                }
            }

            item { Section("About") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        BrandTitle()
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "\nA private gallery. No accounts, no tracking, no Google services. " +
                                "Your photos only leave this phone when you share them or send them over your own Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "\nBuilt with open-source software:\n" +
                                "• Jetpack Compose & AndroidX (Apache 2.0)\n" +
                                "• Media3 / ExoPlayer — video playback (Apache 2.0)\n" +
                                "• Coil — image loading (Apache 2.0)\n" +
                                "• AndroidX Biometric — vault unlock (Apache 2.0)\n" +
                                "• osmdroid — map, tiles © OpenStreetMap contributors (Apache 2.0 / ODbL)\n" +
                                "• ZXing & zxing-android-embedded — QR pairing (Apache 2.0)\n" +
                                "• OkHttp — WebDAV backup (Apache 2.0)\n" +
                                "• ONNX Runtime — photo sorter (MIT)\n" +
                                "• MobileCLIP-S0 model © Apple Inc. — photo sorter (Apple sample code license; " +
                                "copy included in the app). Not endorsed by Apple.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingRow(Icons.Outlined.Code, "Source & releases", UpdateChecker.REPO_URL.removePrefix("https://")) {
                    openUrl(context, UpdateChecker.REPO_URL)
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the sorter?") },
            text = { Text("Your smart albums and everything you taught it will be cleared. Photos stay where they are.") },
            confirmButton = { TextButton(onClick = { vm.sorterReset(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (sortDialog) {
        AlertDialog(
            onDismissRequest = { sortDialog = false },
            title = { Text("Sort order") },
            text = {
                Column {
                    SortOrder.entries.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.setSortOrder(s); sortDialog = false }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s == sort, onClick = { vm.setSortOrder(s); sortDialog = false })
                            Text(s.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sortDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Neon,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
