@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel

@Composable
fun BackupScreen(vm: MainViewModel, nav: NavController) {
    val progress by vm.backup.state.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val onLaunch by vm.backupOnLaunch.collectAsStateWithLifecycle()
    val videos by vm.backupVideos.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(vm.backupUrl()) }
    var user by rememberSaveable { mutableStateOf(vm.backupUser()) }
    var pass by rememberSaveable { mutableStateOf(vm.backupPassword()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val pending = remember(media, progress, videos, confirmForget) { vm.backup.pendingCount(media) }

    fun save() = vm.setBackupConfig(url, user, pass)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Backup to your PC / NAS") },
                navigationIcon = {
                    IconButton(onClick = { save(); nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Optional and off by default. Copies new photos to a WebDAV folder on your own network — " +
                    "for example Nextcloud, a NAS, or “rclone serve webdav” on your PC. No cloud company involved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it.trim(); testResult = null },
                label = { Text("Folder address") },
                placeholder = { Text("http://192.168.1.20:8080/photos") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it; testResult = null },
                label = { Text("Username (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it; testResult = null },
                label = { Text("Password (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        save(); testing = true
                        vm.testBackup { err -> testing = false; testResult = err ?: "OK" }
                    },
                ) { Text(if (testing) "Testing…" else "Test connection") }
                testResult?.let {
                    Text(
                        if (it == "OK") "Connected ✓" else it,
                        color = if (it == "OK") Neon else Magenta,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            SwitchLine("Back up automatically when I open the app (Wi-Fi only)", onLaunch) { save(); vm.setBackupOnLaunch(it) }
            SwitchLine("Include videos", videos) { vm.setBackupVideos(it) }

            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    val p = progress
                    Text(
                        if (pending == 0) "Everything is backed up" else "$pending items not backed up yet",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (p != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(p.label, style = MaterialTheme.typography.labelMedium)
                        p.error?.let { Text(it, color = Magenta, style = MaterialTheme.typography.labelMedium) }
                        if (p.running) {
                            LinearProgressIndicator(
                                progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = url.startsWith("http") && p?.running != true && pending > 0,
                        onClick = { save(); vm.runBackup() },
                    ) { Text("Back up now") }
                }
            }
            TextButton(onClick = { confirmForget = true }) { Text("Forget backup history (upload everything again)") }
            Text(
                "Tip: albums become folders on the server. Your password is stored only on this phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmForget) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget backup history?") },
            text = { Text("The next backup will upload all photos again (the server will overwrite files with the same name).") },
            confirmButton = {
                Button(onClick = { vm.backup.forgetHistory(); confirmForget = false; testResult = null }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
