@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

private enum class EditTab(val label: String) {
    CROP("Crop"), FILTERS("Filters"), ADJUST("Adjust"), DRAW("Draw"), TEXT("Text"), BLUR("Blur"),
}

enum class PhotoFilter(val label: String) {
    NONE("Original"), VIVID("Vivid"), NEON("Neon"), MONO("Mono"), NOIR("Noir"),
    SEPIA("Sepia"), COOL("Cool"), WARM("Warm"), FADE("Fade"),
}

// ---------- Overlay layers (positions are 0..1 of the image; sizes are fractions of the short side) ----------

private sealed interface Layer
private data class StrokeLayer(val points: List<Offset>, val color: Color, val width: Float) : Layer
private data class TextLayer(val id: Long, val text: String, val pos: Offset, val color: Color, val size: Float) : Layer

private fun Offset.rotated(clockwise: Boolean) = if (clockwise) Offset(1f - y, x) else Offset(y, 1f - x)
private fun Offset.flipped() = Offset(1f - x, y)

private fun Layer.mapPoints(f: (Offset) -> Offset): Layer = when (this) {
    is StrokeLayer -> copy(points = points.map(f))
    is TextLayer -> copy(pos = f(pos))
}

private val palette = listOf(
    Color.White, Color(0xFF3CF2FF), Color(0xFFFF3CAC), Color(0xFFA67CFF),
    Color(0xFFB6FF3C), Color(0xFFFFB13C), Color(0xFFFF4D4D), Color.Black,
)
private val stickers = listOf("⭐", "✨", "🚀", "🪐", "🌙", "☄️", "👽", "🛸", "💫", "🔥", "❤️", "😎", "😂", "👍", "🎉", "🌈")

// ---------- Color math ----------

private fun contrastMatrix(c: Float, extraOffset: Float = 0f): ColorMatrix {
    val t = (1f - c) * 128f + extraOffset
    return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
}

private fun PhotoFilter.matrix(): ColorMatrix {
    val m = ColorMatrix()
    when (this) {
        PhotoFilter.NONE -> Unit
        PhotoFilter.VIVID -> m.setSaturation(1.45f)
        PhotoFilter.NEON -> {
            m.setSaturation(1.6f)
            m.postConcat(ColorMatrix().apply { setScale(1.08f, 0.92f, 1.25f, 1f) })
            m.postConcat(contrastMatrix(1.1f))
        }
        PhotoFilter.MONO -> m.setSaturation(0f)
        PhotoFilter.NOIR -> { m.setSaturation(0f); m.postConcat(contrastMatrix(1.35f)) }
        PhotoFilter.SEPIA -> { m.setSaturation(0f); m.postConcat(ColorMatrix().apply { setScale(1.07f, 0.94f, 0.78f, 1f) }) }
        PhotoFilter.COOL -> m.setScale(0.92f, 1f, 1.12f, 1f)
        PhotoFilter.WARM -> m.setScale(1.1f, 1.02f, 0.88f, 1f)
        PhotoFilter.FADE -> { m.setSaturation(0.8f); m.postConcat(contrastMatrix(0.82f, 14f)) }
    }
    return m
}

private fun buildMatrix(f: PhotoFilter, brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
    val m = f.matrix()
    m.postConcat(ColorMatrix().apply { setSaturation(saturation) })
    m.postConcat(contrastMatrix(contrast, brightness * 100f))
    return m
}

private fun ColorMatrix.toComposeFilter(): ColorFilter = ColorFilter.colorMatrix(ComposeColorMatrix(array.copyOf()))

// ---------- Bitmap ops ----------

private fun decodeForEdit(context: Context, uri: Uri): Bitmap {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val maxEdge = 4000
        if (w > maxEdge || h > maxEdge) {
            val s = maxEdge.toFloat() / max(w, h)
            decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
        }
    }
}

private fun scaleDown(b: Bitmap, maxEdge: Int): Bitmap {
    val s = maxEdge.toFloat() / max(b.width, b.height)
    if (s >= 1f) return b
    return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
}

private fun transform(b: Bitmap, block: Matrix.() -> Unit): Bitmap =
    Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply(block), true)

/** Pixelates a rectangle (0..1 coords) so faces / plates can't be read. */
private fun pixelate(src: Bitmap, r: Rect): Bitmap {
    val l = (r.left * src.width).roundToInt().coerceIn(0, src.width - 1)
    val t = (r.top * src.height).roundToInt().coerceIn(0, src.height - 1)
    val rr = (r.right * src.width).roundToInt().coerceIn(l + 1, src.width)
    val b = (r.bottom * src.height).roundToInt().coerceIn(t + 1, src.height)
    val w = rr - l
    val h = b - t
    if (w < 4 || h < 4) return src
    val block = max(8, min(src.width, src.height) / 40)
    val region = Bitmap.createBitmap(src, l, t, w, h)
    val small = Bitmap.createScaledBitmap(region, max(1, w / block), max(1, h / block), true)
    val big = Bitmap.createScaledBitmap(small, w, h, false)
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawBitmap(big, l.toFloat(), t.toFloat(), null)
    return out
}

private fun renderEdit(src: Bitmap, crop: Rect, m: ColorMatrix, layers: List<Layer>, vignette: Float): Bitmap {
    val l = (crop.left * src.width).roundToInt().coerceIn(0, src.width - 1)
    val t = (crop.top * src.height).roundToInt().coerceIn(0, src.height - 1)
    val r = (crop.right * src.width).roundToInt().coerceIn(l + 1, src.width)
    val b = (crop.bottom * src.height).roundToInt().coerceIn(t + 1, src.height)
    val w = r - l
    val h = b - t
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(
        src, android.graphics.Rect(l, t, r, b), android.graphics.Rect(0, 0, w, h),
        Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) },
    )

    // Overlays are stored in full-image coordinates, so shift by the crop offset.
    val minDim = min(src.width, src.height).toFloat()
    canvas.save()
    canvas.translate(-l.toFloat(), -t.toFloat())
    layers.forEach { layer ->
        when (layer) {
            is StrokeLayer -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = layer.color.toArgb()
                    strokeWidth = layer.width * minDim
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val path = android.graphics.Path()
                layer.points.forEachIndexed { i, pt ->
                    val x = pt.x * src.width
                    val y = pt.y * src.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (layer.points.size == 1) path.lineTo(layer.points[0].x * src.width + 0.1f, layer.points[0].y * src.height)
                canvas.drawPath(path, p)
            }
            is TextLayer -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layer.color.toArgb()
                    textSize = layer.size * minDim
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, android.graphics.Color.argb(160, 0, 0, 0))
                }
                val cx = layer.pos.x * src.width
                val cy = layer.pos.y * src.height - (p.ascent() + p.descent()) / 2f
                canvas.drawText(layer.text, cx, cy, p)
            }
        }
    }
    canvas.restore()

    if (vignette > 0.01f) {
        val radius = hypot(w.toFloat(), h.toFloat()) / 2f
        val p = Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, radius,
                intArrayOf(0, 0, android.graphics.Color.argb((vignette * 230).toInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
    return out
}

private fun editedName(original: String): String {
    val base = original.substringBeforeLast('.').ifBlank { "photo" }.take(80)
    return "${base}_edit_${System.currentTimeMillis() / 1000}.jpg"
}

// ---------- Screen ----------

@Composable
fun EditorScreen(vm: MainViewModel, nav: NavController) {
    val target = vm.editTarget
    if (target == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var undoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var filter by remember { mutableStateOf(PhotoFilter.NONE) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var vignette by remember { mutableFloatStateOf(0f) }
    var layers by remember { mutableStateOf(listOf<Layer>()) }
    var penColor by remember { mutableStateOf(Neon) }
    var penWidth by remember { mutableFloatStateOf(0.012f) }
    var textSize by remember { mutableFloatStateOf(0.08f) }
    var addingText by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(EditTab.CROP) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(target.id) {
        val loaded = withContext(Dispatchers.IO) { runCatching { decodeForEdit(context, target.uri) }.getOrNull() }
        if (loaded == null) {
            error = "Couldn't open this image for editing."
        } else {
            bitmap = loaded
            preview = withContext(Dispatchers.Default) { scaleDown(loaded, 160) }
        }
    }

    val matrix = remember(filter, brightness, contrast, saturation) { buildMatrix(filter, brightness, contrast, saturation) }
    val colorFilter = remember(matrix) { matrix.toComposeFilter() }

    fun applyTransform(mapPoint: (Offset) -> Offset, block: Matrix.() -> Unit) {
        val b = bitmap ?: return
        if (working) return
        working = true
        scope.launch {
            val nb = withContext(Dispatchers.Default) { transform(b, block) }
            bitmap = nb
            undoBitmap = null
            preview = withContext(Dispatchers.Default) { scaleDown(nb, 160) }
            crop = Rect(0f, 0f, 1f, 1f)
            layers = layers.map { it.mapPoints(mapPoint) }
            working = false
        }
    }

    fun setAspect(ratio: Float?) {
        val b = bitmap ?: return
        if (ratio == null) { crop = Rect(0f, 0f, 1f, 1f); return }
        val imgAspect = b.width.toFloat() / b.height
        val (nw, nh) = if (ratio > imgAspect) 1f to imgAspect / ratio else ratio / imgAspect to 1f
        val l = (1f - nw) / 2f
        val t = (1f - nh) / 2f
        crop = Rect(l, t, l + nw, t + nh)
    }

    fun doPixelate(r: Rect) {
        val b = bitmap ?: return
        if (working) return
        working = true
        scope.launch {
            val nb = withContext(Dispatchers.Default) { runCatching { pixelate(b, r) }.getOrDefault(b) }
            if (nb !== b) { undoBitmap = b; bitmap = nb }
            working = false
        }
    }

    fun save() {
        val src = bitmap ?: return
        if (working) return
        working = true
        val m = matrix
        val c = crop
        val ls = layers
        val v = vignette
        scope.launch {
            val uri = runCatching {
                val out = withContext(Dispatchers.Default) { renderEdit(src, c, m, ls, v) }
                vm.repo.saveBitmap(out, editedName(target.name))
            }.getOrNull()
            working = false
            if (uri != null) {
                vm.toast("Saved a copy to Pictures/Stellar Gallery")
                vm.scheduleRefresh()
                nav.popBackStack()
            } else {
                vm.toast("Couldn't save the edit")
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF020309),
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = {
                    TextButton(onClick = { save() }, enabled = bitmap != null && !working) { Text("Save copy") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 8.dp),
            ) {
                when (tab) {
                    EditTab.CROP -> CropTools(
                        onRotateLeft = { applyTransform({ it.rotated(false) }) { postRotate(-90f) } },
                        onRotateRight = { applyTransform({ it.rotated(true) }) { postRotate(90f) } },
                        onFlip = { applyTransform({ it.flipped() }) { postScale(-1f, 1f) } },
                        onAspect = ::setAspect,
                    )
                    EditTab.FILTERS -> {
                        val p = preview
                        if (p != null) FilterStrip(p, filter) { filter = it }
                    }
                    EditTab.ADJUST -> Column(Modifier.padding(horizontal = 16.dp)) {
                        AdjustSlider("Brightness", brightness, -1f..1f) { brightness = it }
                        AdjustSlider("Contrast", contrast, 0.5f..1.6f) { contrast = it }
                        AdjustSlider("Saturation", saturation, 0f..2f) { saturation = it }
                        AdjustSlider("Vignette", vignette, 0f..1f) { vignette = it }
                        TextButton(onClick = { brightness = 0f; contrast = 1f; saturation = 1f; vignette = 0f }) { Text("Reset") }
                    }
                    EditTab.DRAW -> Column(Modifier.padding(horizontal = 12.dp)) {
                        ColorRow(penColor) { penColor = it }
                        AdjustSlider("Brush", penWidth, 0.003f..0.04f) { penWidth = it }
                        Row {
                            TextButton(
                                enabled = layers.any { it is StrokeLayer },
                                onClick = { layers = layers.dropLastWhereFirst { it is StrokeLayer } },
                            ) { Text("Undo stroke") }
                            TextButton(
                                enabled = layers.any { it is StrokeLayer },
                                onClick = { layers = layers.filterNot { it is StrokeLayer } },
                            ) { Text("Clear drawing") }
                        }
                    }
                    EditTab.TEXT -> Column(Modifier.padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { addingText = true }) { Text("Add text") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                enabled = layers.any { it is TextLayer },
                                onClick = { layers = layers.dropLastWhereFirst { it is TextLayer } },
                            ) { Text("Remove last") }
                        }
                        ColorRow(penColor) { penColor = it }
                        AdjustSlider("Size", textSize, 0.03f..0.25f) { textSize = it }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(stickers) { s ->
                                Text(
                                    s, fontSize = 26.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            layers = layers + TextLayer(System.nanoTime(), s, Offset(0.5f, 0.5f), Color.White, textSize * 1.4f)
                                        }
                                        .padding(6.dp),
                                )
                            }
                        }
                        Text(
                            "Drag text and stickers on the photo to move them.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EditTab.BLUR -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Drag a box over faces, plates or anything private to pixelate it.")
                        TextButton(
                            enabled = undoBitmap != null,
                            onClick = { undoBitmap?.let { bitmap = it }; undoBitmap = null },
                        ) { Text("Undo last blur") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EditTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(t.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Neon.copy(alpha = 0.18f),
                                selectedLabelColor = Neon,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val bmp = bitmap
            when {
                error != null -> Text(error ?: "", modifier = Modifier.align(Alignment.Center).padding(24.dp))
                bmp == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> EditCanvas(
                    bmp = bmp,
                    colorFilter = colorFilter,
                    crop = crop,
                    tab = tab,
                    layers = layers,
                    vignette = vignette,
                    penColor = penColor,
                    penWidth = penWidth,
                    onCrop = { crop = it },
                    onLayers = { layers = it },
                    onPixelate = ::doPixelate,
                )
            }
            if (working && bmp != null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (addingText) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingText = false },
            title = { Text("Add text") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, label = { Text("Text") })
            },
            confirmButton = {
                Button(enabled = text.isNotBlank(), onClick = {
                    layers = layers + TextLayer(System.nanoTime(), text.trim(), Offset(0.5f, 0.5f), penColor, textSize)
                    addingText = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingText = false }) { Text("Cancel") } },
        )
    }
}

/** Removes the last element matching [pred]. */
private fun <T> List<T>.dropLastWhereFirst(pred: (T) -> Boolean): List<T> {
    val i = indexOfLast(pred)
    return if (i < 0) this else take(i) + drop(i + 1)
}

@Composable
private fun ColorRow(selected: Color, onPick: (Color) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        palette.forEach { c ->
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Color.White else Color.Gray, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun CropTools(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlip: () -> Unit,
    onAspect: (Float?) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ViewerAction(Icons.Outlined.RotateLeft, "Left", onClick = onRotateLeft)
            ViewerAction(Icons.Outlined.RotateRight, "Right", onClick = onRotateRight)
            ViewerAction(Icons.Outlined.Flip, "Flip", onClick = onFlip)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf<Pair<String, Float?>>(
                "Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f,
            ).forEach { (label, ratio) ->
                FilterChip(selected = false, onClick = { onAspect(ratio) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun FilterStrip(preview: Bitmap, selected: PhotoFilter, onPick: (PhotoFilter) -> Unit) {
    val image = remember(preview) { preview.asImageBitmap() }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(PhotoFilter.entries) { f ->
            val cf = remember(f) { f.matrix().toComposeFilter() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onPick(f) },
            ) {
                Image(
                    bitmap = image,
                    contentDescription = f.label,
                    contentScale = ContentScale.Crop,
                    colorFilter = cf,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (f == selected) Modifier.border(2.dp, Neon, RoundedCornerShape(10.dp)) else Modifier),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    f.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (f == selected) Neon else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EditCanvas(
    bmp: Bitmap,
    colorFilter: ColorFilter,
    crop: Rect,
    tab: EditTab,
    layers: List<Layer>,
    vignette: Float,
    penColor: Color,
    penWidth: Float,
    onCrop: (Rect) -> Unit,
    onLayers: (List<Layer>) -> Unit,
    onPixelate: (Rect) -> Unit,
) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val ratio = bmp.width.toFloat() / bmp.height
        val dw: Float
        val dh: Float
        if (bw / bh > ratio) { dh = bh; dw = bh * ratio } else { dw = bw; dh = bw / ratio }
        val imgRect = Rect((bw - dw) / 2f, (bh - dh) / 2f, (bw + dw) / 2f, (bh + dh) / 2f)
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
        )
        EditOverlay(imgRect, crop, tab, layers, vignette, penColor, penWidth, onCrop, onLayers, onPixelate)
    }
}

private fun toScreen(c: Rect, img: Rect) = Rect(
    img.left + c.left * img.width,
    img.top + c.top * img.height,
    img.left + c.right * img.width,
    img.top + c.bottom * img.height,
)

@Composable
private fun EditOverlay(
    img: Rect,
    crop: Rect,
    tab: EditTab,
    layers: List<Layer>,
    vignette: Float,
    penColor: Color,
    penWidth: Float,
    onCrop: (Rect) -> Unit,
    onLayers: (List<Layer>) -> Unit,
    onPixelate: (Rect) -> Unit,
) {
    val currentCrop by rememberUpdatedState(crop)
    val currentImg by rememberUpdatedState(img)
    val currentLayers by rememberUpdatedState(layers)
    val currentPen by rememberUpdatedState(penColor)
    val currentWidth by rememberUpdatedState(penWidth)
    val cropChange by rememberUpdatedState(onCrop)
    val layersChange by rememberUpdatedState(onLayers)
    val pixelateNow by rememberUpdatedState(onPixelate)
    var liveStroke by remember { mutableStateOf<List<Offset>?>(null) }
    var blurBox by remember { mutableStateOf<Rect?>(null) }
    val measurer = rememberTextMeasurer()

    fun norm(p: Offset): Offset {
        val im = currentImg
        return Offset(((p.x - im.left) / im.width).coerceIn(0f, 1f), ((p.y - im.top) / im.height).coerceIn(0f, 1f))
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(tab) {
                when (tab) {
                    EditTab.CROP -> {
                        var mode = 0 // 1 TL, 2 TR, 3 BL, 4 BR, 5 move
                        val touch = 40.dp.toPx()
                        detectDragGestures(
                            onDragStart = { p ->
                                val r = toScreen(currentCrop, currentImg)
                                mode = when {
                                    (p - r.topLeft).getDistance() < touch -> 1
                                    (p - r.topRight).getDistance() < touch -> 2
                                    (p - r.bottomLeft).getDistance() < touch -> 3
                                    (p - r.bottomRight).getDistance() < touch -> 4
                                    r.contains(p) -> 5
                                    else -> 0
                                }
                            },
                            onDrag = { ch, drag ->
                                ch.consume()
                                val im = currentImg
                                if (im.width > 0f && im.height > 0f) {
                                    val dx = drag.x / im.width
                                    val dy = drag.y / im.height
                                    val c = currentCrop
                                    val minS = 0.08f
                                    val n = when (mode) {
                                        1 -> Rect((c.left + dx).coerceIn(0f, c.right - minS), (c.top + dy).coerceIn(0f, c.bottom - minS), c.right, c.bottom)
                                        2 -> Rect(c.left, (c.top + dy).coerceIn(0f, c.bottom - minS), (c.right + dx).coerceIn(c.left + minS, 1f), c.bottom)
                                        3 -> Rect((c.left + dx).coerceIn(0f, c.right - minS), c.top, c.right, (c.bottom + dy).coerceIn(c.top + minS, 1f))
                                        4 -> Rect(c.left, c.top, (c.right + dx).coerceIn(c.left + minS, 1f), (c.bottom + dy).coerceIn(c.top + minS, 1f))
                                        5 -> c.translate(dx.coerceIn(-c.left, 1f - c.right), dy.coerceIn(-c.top, 1f - c.bottom))
                                        else -> c
                                    }
                                    cropChange(n)
                                }
                            },
                        )
                    }
                    EditTab.DRAW -> detectDragGestures(
                        onDragStart = { p -> liveStroke = listOf(norm(p)) },
                        onDrag = { ch, _ -> ch.consume(); liveStroke = liveStroke.orEmpty() + norm(ch.position) },
                        onDragEnd = {
                            val pts = liveStroke
                            if (!pts.isNullOrEmpty()) layersChange(currentLayers + StrokeLayer(pts, currentPen, currentWidth))
                            liveStroke = null
                        },
                        onDragCancel = { liveStroke = null },
                    )
                    EditTab.TEXT -> {
                        var dragging: Long? = null
                        val grab = 56.dp.toPx()
                        detectDragGestures(
                            onDragStart = { p ->
                                val im = currentImg
                                dragging = currentLayers.filterIsInstance<TextLayer>()
                                    .minByOrNull { t -> (Offset(im.left + t.pos.x * im.width, im.top + t.pos.y * im.height) - p).getDistance() }
                                    ?.takeIf { t -> (Offset(im.left + t.pos.x * im.width, im.top + t.pos.y * im.height) - p).getDistance() < grab + t.size * min(im.width, im.height) }
                                    ?.id
                            },
                            onDrag = { ch, drag ->
                                val id = dragging
                                val im = currentImg
                                if (id != null && im.width > 0f) {
                                    ch.consume()
                                    layersChange(currentLayers.map { l ->
                                        if (l is TextLayer && l.id == id) {
                                            l.copy(pos = Offset((l.pos.x + drag.x / im.width).coerceIn(0f, 1f), (l.pos.y + drag.y / im.height).coerceIn(0f, 1f)))
                                        } else l
                                    })
                                }
                            },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                        )
                    }
                    EditTab.BLUR -> {
                        var start = Offset.Zero
                        detectDragGestures(
                            onDragStart = { p -> start = norm(p); blurBox = Rect(start, start) },
                            onDrag = { ch, _ ->
                                ch.consume()
                                val e = norm(ch.position)
                                blurBox = Rect(min(start.x, e.x), min(start.y, e.y), max(start.x, e.x), max(start.y, e.y))
                            },
                            onDragEnd = {
                                blurBox?.let { if (it.width > 0.01f && it.height > 0.01f) pixelateNow(it) }
                                blurBox = null
                            },
                            onDragCancel = { blurBox = null },
                        )
                    }
                    else -> Unit
                }
            },
    ) {
        val minDim = min(img.width, img.height)
        fun sx(x: Float) = img.left + x * img.width
        fun sy(y: Float) = img.top + y * img.height

        // Strokes + text
        val strokes = layers.filterIsInstance<StrokeLayer>() +
            listOfNotNull(liveStroke?.let { StrokeLayer(it, penColor, penWidth) })
        strokes.forEach { s ->
            val path = Path()
            s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y)) }
            if (s.points.size == 1) path.lineTo(sx(s.points[0].x) + 0.1f, sy(s.points[0].y))
            drawPath(path, s.color, style = Stroke(width = s.width * minDim, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        layers.filterIsInstance<TextLayer>().forEach { t ->
            val fontPx = t.size * minDim
            val laid = measurer.measure(
                t.text,
                TextStyle(
                    color = t.color,
                    fontSize = fontPx.toSp(),
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, fontPx * 0.04f), fontPx * 0.08f),
                ),
            )
            drawText(laid, topLeft = Offset(sx(t.pos.x) - laid.size.width / 2f, sy(t.pos.y) - laid.size.height / 2f))
        }

        // Vignette preview over the crop area
        val r = toScreen(crop, img)
        if (vignette > 0.01f) {
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = vignette * 0.9f),
                    center = r.center,
                    radius = hypot(r.width, r.height) / 2f,
                ),
                topLeft = r.topLeft,
                size = r.size,
            )
        }

        // Crop dimming + handles
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset(img.left, img.top), Size(img.width, (r.top - img.top).coerceAtLeast(0f)))
        drawRect(dim, Offset(img.left, r.bottom), Size(img.width, (img.bottom - r.bottom).coerceAtLeast(0f)))
        drawRect(dim, Offset(img.left, r.top), Size((r.left - img.left).coerceAtLeast(0f), r.height))
        drawRect(dim, Offset(r.right, r.top), Size((img.right - r.right).coerceAtLeast(0f), r.height))
        if (tab == EditTab.CROP) {
            val thin = 1.dp.toPx()
            for (i in 1..2) {
                val x = r.left + r.width * i / 3f
                val y = r.top + r.height * i / 3f
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, r.top), Offset(x, r.bottom), thin)
                drawLine(Color.White.copy(alpha = 0.35f), Offset(r.left, y), Offset(r.right, y), thin)
            }
            drawRect(Neon, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
            listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { drawCircle(Magenta, 7.dp.toPx(), it) }
        }

        // Blur box being drawn
        blurBox?.let { b ->
            val sr = toScreen(b, img)
            drawRect(Magenta.copy(alpha = 0.25f), sr.topLeft, sr.size)
            drawRect(Magenta, sr.topLeft, sr.size, style = Stroke(2.dp.toPx()))
        }
    }
}
