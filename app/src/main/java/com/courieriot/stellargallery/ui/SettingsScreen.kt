@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
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
            item { Section("Gallery") }
            item { SettingRow(Icons.Outlined.SwapVert, "Sort order", sort.label) { sortDialog = true } }
            item {
                SettingRow(Icons.Outlined.GridView, "Grid size", "$columns per row (tap to change)") {
                    vm.setGridColumns(if (columns >= 5) 3 else columns + 1)
                }
            }
            item { SwitchRow(Icons.Outlined.CalendarMonth, "Group by date", group, vm::setGroupByDate) }
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
                            "\nA private, offline gallery. No accounts, no tracking, no Google services. " +
                                "Your photos only leave this phone when you share them or send them over your own Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "\nBuilt with open-source software:\n" +
                                "• Jetpack Compose & AndroidX (Apache 2.0)\n" +
                                "• Media3 / ExoPlayer — video playback (Apache 2.0)\n" +
                                "• Coil — image loading (Apache 2.0)\n" +
                                "• AndroidX Biometric — vault unlock (Apache 2.0)",
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
