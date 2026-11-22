@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.courieriot.stellargallery.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel
import com.courieriot.stellargallery.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun showBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock vault")
        .setSubtitle("Stellar Gallery")
        .setNegativeButtonText("Use PIN")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    runCatching { prompt.authenticate(info) }
}

@Composable
fun VaultScreen(vm: MainViewModel, nav: NavController) {
    val unlocked by vm.vaultUnlocked.collectAsStateWithLifecycle()
    val hasPin = remember(unlocked) { vm.vaultHasPin() }
    when {
        !hasPin -> VaultFrame(onBack = { nav.popBackStack() }) {
            PinEntry(
                setup = true,
                onSubmit = { pin, _ -> vm.setupVaultPin(pin) },
            )
        }
        !unlocked -> PinUnlock(vm, nav)
        else -> VaultContent(vm, nav)
    }
}

@Composable
private fun VaultFrame(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}

/**
 * PIN pad. In setup mode it asks twice. [onSubmit] gets the PIN and a callback to report a wrong PIN.
 */
@Composable
private fun PinEntry(
    setup: Boolean,
    onSubmit: (pin: String, onWrong: () -> Unit) -> Unit,
    onBiometric: (() -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val title = when {
        !setup -> "Vault locked"
        first == null -> "Create a vault PIN"
        else -> "Confirm your PIN"
    }
    val hint = error ?: if (setup) "4–8 digits. Hidden photos are encrypted on this phone." else "Enter your PIN"

    Icon(Icons.Outlined.Lock, null, tint = Neon, modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        hint, textAlign = TextAlign.Center,
        color = if (error != null) Magenta else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(maxOf(4, pin.length)) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (i < pin.length) Neon else Color.Transparent)
                    .border(1.5.dp, Neon.copy(alpha = 0.7f), CircleShape),
            )
        }
    }
    Spacer(Modifier.height(28.dp))

    fun submit() {
        if (checking) return
        if (pin.length < 4) { error = "Use at least 4 digits"; return }
        if (setup) {
            val f = first
            when {
                f == null -> { first = pin; pin = ""; error = null }
                f == pin -> onSubmit(pin) {}
                else -> { error = "PINs didn't match — try again"; first = null; pin = "" }
            }
        } else {
            checking = true
            val entered = pin
            onSubmit(entered) { checking = false; error = "Wrong PIN"; pin = "" }
        }
    }

    val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { d -> PinKey(d) { if (pin.length < 8) { pin += d; error = null } } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            PinKey("⌫") { pin = pin.dropLast(1) }
            PinKey("0") { if (pin.length < 8) { pin += "0"; error = null } }
            PinKey(null, highlight = true, onClick = ::submit)
        }
        if (onBiometric != null) {
            TextButton(onClick = onBiometric) {
                Icon(Icons.Outlined.Fingerprint, null)
                Spacer(Modifier.size(8.dp))
                Text("Use fingerprint")
            }
        }
    }
    if (checking) {
        Spacer(Modifier.height(12.dp))
        CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun PinKey(label: String?, highlight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(70.dp)
            .clip(CircleShape)
            .background(if (highlight) Neon.copy(alpha = 0.18f) else Panel)
            .border(1.dp, if (highlight) Neon else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) Icon(Icons.Filled.Check, "OK", tint = Neon)
        else Text(label, fontSize = 24.sp)
    }
}

@Composable
private fun PinUnlock(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val bioEnabled by vm.vaultBiometric.collectAsStateWithLifecycle()
    val canBio = remember(activity) {
        activity != null &&
            BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    val bio: (() -> Unit)? = if (bioEnabled && canBio && activity != null) {
        { showBiometric(activity) { vm.unlockWithBiometric() } }
    } else null

    LaunchedEffect(Unit) { bio?.invoke() }

    VaultFrame(onBack = { nav.popBackStack() }) {
        PinEntry(
            setup = false,
            onSubmit = { pin, onWrong -> vm.unlockWithPin(pin) { ok -> if (!ok) onWrong() } },
            onBiometric = bio,
        )
    }
}

@Composable
private fun VaultContent(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val items by vm.vaultItems.collectAsStateWithLifecycle()
    val busy by vm.vaultBusy.collectAsStateWithLifecycle()
    val bio by vm.vaultBiometric.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var changePin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var decoyDialog by remember { mutableStateOf(false) }
    var logDialog by remember { mutableStateOf(false) }
    val decoy by vm.vaultDecoy.collectAsStateWithLifecycle()
    val canBio = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    val selected = items.filter { it.id in selection }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionTopBar(selection.size, { selection = emptySet() }, { selection = items.map { it.id }.toSet() }) {
                    IconButton(onClick = { vm.restoreFromVault(selected); selection = emptySet() }) {
                        Icon(Icons.Outlined.Restore, "Restore to gallery")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteForever, "Delete") }
                }
            } else {
                TopAppBar(
                    title = { Text("Vault") },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { vm.lockVault() }) { Icon(Icons.Outlined.Lock, "Lock vault") }
                        if (!decoy) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Change PIN") }, onClick = { menu = false; changePin = true })
                                DropdownMenuItem(
                                    text = { Text(if (vm.vaultHasDecoy()) "Decoy PIN (on)" else "Decoy PIN…") },
                                    onClick = { menu = false; decoyDialog = true },
                                )
                                DropdownMenuItem(text = { Text("Unlock log") }, onClick = { menu = false; logDialog = true })
                                if (canBio) {
                                    DropdownMenuItem(
                                        text = { Text("Unlock with fingerprint") },
                                        trailingIcon = { Switch(checked = bio, onCheckedChange = null) },
                                        onClick = { vm.setVaultBiometric(!bio) },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (items.isEmpty()) {
                EmptyState("Your vault is empty.\nIn the gallery, select photos and choose “Hide in vault”.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { _, v -> v.id }) { idx, v ->
                        val isSel = v.id in selection
                        VaultThumb(
                            vm = vm, item = v, selected = isSel, selectionMode = selection.isNotEmpty(),
                            onClick = {
                                if (selection.isNotEmpty()) selection = if (isSel) selection - v.id else selection + v.id
                                else nav.navigate("vaultViewer/$idx")
                            },
                            onLongClick = { selection = if (isSel) selection - v.id else selection + v.id },
                        )
                    }
                }
            }
            busy?.let { BusyCard(it, Modifier.align(Alignment.Center)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete from vault?") },
            text = { Text("${selected.size} items will be erased for good. This can't be undone.") },
            confirmButton = {
                Button(onClick = { vm.deleteFromVault(selected); selection = emptySet(); confirmDelete = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (changePin) ChangePinDialog(onDismiss = { changePin = false }) { vm.changeVaultPin(it); changePin = false }
    if (decoyDialog) {
        ChangePinDialog(
            title = "Decoy PIN",
            note = "Entering this PIN opens an empty vault, so nobody can tell your real photos are there.",
            onDismiss = { decoyDialog = false },
            onRemove = if (vm.vaultHasDecoy()) ({ vm.setDecoyPin(null); decoyDialog = false }) else null,
        ) { vm.setDecoyPin(it); decoyDialog = false }
    }
    if (logDialog) {
        val attempts = remember { vm.vaultAttempts() }
        AlertDialog(
            onDismissRequest = { logDialog = false },
            title = { Text("Unlock log") },
            text = {
                if (attempts.isEmpty()) {
                    Text("No failed or decoy attempts recorded.")
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(attempts.size) { i ->
                            val a = attempts[i]
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    if (a.kind == "decoy") "Decoy PIN used" else "Wrong PIN",
                                    color = if (a.kind == "decoy") Neon else Magenta,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(fullDate(a.time), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { logDialog = false }) { Text("Close") } },
            dismissButton = {
                if (attempts.isNotEmpty()) TextButton(onClick = { vm.clearVaultAttempts(); logDialog = false }) { Text("Clear") }
            },
        )
    }
}

@Composable
fun BusyCard(text: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.size(14.dp))
            Text(text)
        }
    }
}

@Composable
private fun ChangePinDialog(
    title: String = "Change vault PIN",
    note: String? = null,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onSave: (String) -> Unit,
) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val valid = a.length in 4..8 && a == b
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = a, onValueChange = { v -> a = v.filter(Char::isDigit).take(8) },
                    label = { Text("New PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = b, onValueChange = { v -> b = v.filter(Char::isDigit).take(8) },
                    label = { Text("Confirm PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = b.isNotEmpty() && a != b,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(a) }, enabled = valid) { Text("Save") } },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Remove") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun VaultThumb(
    vm: MainViewModel,
    item: VaultItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { vm.vault.thumbnail(item) }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Panel)
            .then(if (selected) Modifier.border(2.dp, Neon, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val t = thumb
        if (t != null) {
            Image(t.asImageBitmap(), item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image, null, tint = Neon.copy(alpha = 0.5f))
        }
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp),
            )
        }
        if (selectionMode) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (selected) Neon else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp),
            )
        }
    }
}

@Composable
fun VaultViewerScreen(vm: MainViewModel, nav: NavController, startIndex: Int) {
    val unlocked by vm.vaultUnlocked.collectAsStateWithLifecycle()
    val items by vm.vaultItems.collectAsStateWithLifecycle()
    if (!unlocked || items.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    var chrome by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { items.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { p ->
            val item = items.getOrNull(p) ?: return@HorizontalPager
            if (item.isVideo) {
                VaultVideoPage(vm, item, active = p == pager.currentPage, onChrome = { chrome = it })
            } else {
                val bmp by produceState<Bitmap?>(null, item.id) {
                    value = withContext(Dispatchers.IO) { runCatching { vm.vault.decodeImage(item, 3000) }.getOrNull() }
                }
                val b = bmp
                if (b == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    ZoomableImage(model = b, onTap = { chrome = !chrome })
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Lock, null, tint = Neon, modifier = Modifier.padding(12.dp).size(18.dp))
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ViewerAction(Icons.Outlined.Restore, "Restore") { vm.restoreFromVault(listOf(current)) }
                ViewerAction(Icons.Outlined.DeleteForever, "Delete") { confirmDelete = true }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete from vault?") },
            text = { Text("“${current.name}” will be erased for good.") },
            confirmButton = {
                Button(onClick = { vm.deleteFromVault(listOf(current)); confirmDelete = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VaultVideoPage(vm: MainViewModel, item: VaultItem, active: Boolean, onChrome: (Boolean) -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { vm.vault.thumbnail(item) }
    }
    val file by produceState<File?>(null, item.id, active) {
        value = if (active) withContext(Dispatchers.IO) { runCatching { vm.vault.decryptToTemp(item) }.getOrNull() } else null
    }
    val f = file
    DisposableEffect(f) { onDispose { f?.delete() } }
    if (!active || f == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            if (active) CircularProgressIndicator()
        }
    } else {
        VideoPage(
            uri = Uri.fromFile(f),
            placeholder = thumb,
            active = true,
            onChromeChange = onChrome,
            controlsBottomPadding = 76.dp,
        )
    }
}
