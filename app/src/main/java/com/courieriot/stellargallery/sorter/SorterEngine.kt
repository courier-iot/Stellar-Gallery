package com.courieriot.stellargallery.sorter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

class StarterCategory(val key: String, val name: String, val group: String, val emb: FloatArray)
class CategoryGroup(val key: String, val name: String)

/**
 * Runs the on-device MobileCLIP model. Everything is wrapped so a missing or broken model
 * shows a message instead of crashing the app.
 */
class SorterEngine(private val context: Context) {
    private var env: OrtEnvironment? = null
    private var vision: OrtSession? = null
    private var visionIn = ""
    private var visionOut = ""
    private var text: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null

    var categories: List<StarterCategory> = emptyList()
        private set
    var groups: List<CategoryGroup> = emptyList()
        private set
    var dim = 0
        private set
    var modelId = ""
        private set
    /** Decision rules tuned at build time. */
    var scale = 100f
        private set
    var minProb = 0.5f
        private set
    var minMargin = 0.15f
        private set
    private var inputSize = 256
    private var normalizeInput = false
    private var mean = floatArrayOf(0f, 0f, 0f)
    private var std = floatArrayOf(1f, 1f, 1f)

    val searchDir: File get() = File(context.filesDir, "sorter/search").apply { mkdirs() }
    val textModelFile: File get() = File(searchDir, "text.onnx")
    val tokenizerFile: File get() = File(searchDir, "tokenizer.json")

    fun searchModelInstalled(): Boolean =
        textModelFile.length() > 1_000_000 && tokenizerFile.length() > 100_000

    /** Loads the bundled image model. Returns an error message, or null when ready. */
    @Synchronized
    fun loadVision(): String? {
        if (vision != null) return null
        return runCatching {
            val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val catJson = context.assets.open("sorter/categories.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JSONObject(catJson)
            dim = root.getInt("dim")
            modelId = root.optString("model", "v1")
            root.optJSONObject("preprocess")?.let { p ->
                inputSize = p.optInt("size", 256)
                normalizeInput = p.optBoolean("normalize", false)
                p.optJSONArray("mean")?.let { a -> mean = FloatArray(3) { a.getDouble(it).toFloat() } }
                p.optJSONArray("std")?.let { a -> std = FloatArray(3) { a.getDouble(it).toFloat() } }
            }
            root.optJSONObject("decision")?.let { d ->
                scale = d.optDouble("scale", 100.0).toFloat()
                minProb = d.optDouble("min_prob", 0.5).toFloat()
                minMargin = d.optDouble("min_margin", 0.15).toFloat()
            }
            val arr = root.getJSONArray("categories")
            categories = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val ea = o.getJSONArray("emb")
                StarterCategory(
                    o.getString("key"), o.getString("name"), o.optString("group", o.getString("key")),
                    FloatArray(ea.length()) { j -> ea.getDouble(j).toFloat() },
                )
            }
            val ga = root.optJSONArray("groups")
            groups = if (ga == null) emptyList() else List(ga.length()) { i ->
                val o = ga.getJSONObject(i)
                CategoryGroup(o.getString("key"), o.getString("name"))
            }
            // Copy the model out of the APK once, so it loads from disk instead of filling memory.
            val modelFile = File(context.filesDir, "sorter/vision-$modelId.onnx")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                File(context.filesDir, "sorter").listFiles()?.filter { it.name.startsWith("vision-") }?.forEach { it.delete() }
                val tmp = File(modelFile.parentFile, modelFile.name + ".part")
                context.assets.open("sorter/vision.onnx").use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
                if (!tmp.renameTo(modelFile)) error("couldn't prepare the model file")
            }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val s = e.createSession(modelFile.absolutePath, opts)
            visionIn = s.inputNames.first()
            visionOut = if ("image_embeds" in s.outputNames) "image_embeds" else s.outputNames.first()
            vision = s
            null
        }.getOrElse { t ->
            vision = null
            if (t is java.io.FileNotFoundException) "This build doesn't include the sorter model."
            else "The sorter couldn't start (${t.javaClass.simpleName})."
        }
    }

    /** Image → unit-length embedding. Bitmap should be software, any size. */
    fun embedImage(src: Bitmap): FloatArray {
        val s = vision ?: error("Vision model not loaded")
        val e = env ?: error("No runtime")
        val size = inputSize
        // Resize shortest side, then center-crop (same steps the build verified on sample photos).
        val scale = size.toFloat() / minOf(src.width, src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(size)
        val sh = (src.height * scale).toInt().coerceAtLeast(size)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x0 = (sw - size) / 2
        val y0 = (sh - size) / 2
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, x0, y0, size, size)
        val buf = FloatBuffer.allocate(3 * size * size)
        val plane = size * size
        for (i in 0 until plane) {
            val c = px[i]
            var r = ((c shr 16) and 0xFF) / 255f
            var g = ((c shr 8) and 0xFF) / 255f
            var b = (c and 0xFF) / 255f
            if (normalizeInput) {
                r = (r - mean[0]) / std[0]; g = (g - mean[1]) / std[1]; b = (b - mean[2]) / std[2]
            }
            buf.put(i, r)
            buf.put(plane + i, g)
            buf.put(2 * plane + i, b)
        }
        OnnxTensor.createTensor(e, buf, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            s.run(mapOf(visionIn to t)).use { r ->
                val out = (r.get(visionOut).orElse(r.get(0)) as OnnxTensor).floatBuffer
                val v = FloatArray(out.remaining())
                out.get(v)
                return normalize(v)
            }
        }
    }

    /** Loads the optional text model (Search & sort). Returns true when ready. */
    @Synchronized
    fun loadText(): Boolean {
        if (text != null && tokenizer != null) return true
        if (!searchModelInstalled()) return false
        return runCatching {
            val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
            tokenizer = ClipTokenizer(tokenizerFile.readText())
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            text = e.createSession(textModelFile.absolutePath, opts)
            true
        }.getOrElse {
            text = null; tokenizer = null
            false
        }
    }

    @Synchronized
    fun unloadText() {
        runCatching { text?.close() }
        text = null
        tokenizer = null
    }

    /** Text → unit-length embedding, or null when the text model isn't available. */
    @Synchronized
    fun embedText(query: String): FloatArray? {
        if (!loadText()) return null
        val s = text ?: return null
        val tk = tokenizer ?: return null
        val e = env ?: return null
        return runCatching {
            val (ids, mask) = tk.encode(query)
            val tensors = HashMap<String, OnnxTensor>()
            try {
                for (name in s.inputNames) {
                    val data = if (name.contains("mask")) mask else ids
                    tensors[name] = OnnxTensor.createTensor(e, LongBuffer.wrap(data), longArrayOf(1, 77))
                }
                s.run(tensors).use { r ->
                    val outName = if ("text_embeds" in s.outputNames) "text_embeds" else s.outputNames.first()
                    val out = (r.get(outName).orElse(r.get(0)) as OnnxTensor).floatBuffer
                    val v = FloatArray(out.remaining())
                    out.get(v)
                    normalize(v)
                }
            } finally {
                tensors.values.forEach { runCatching { it.close() } }
            }
        }.getOrNull()
    }

    companion object {
        fun normalize(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            val inv = 1f / sqrt(n.coerceAtLeast(1e-12f))
            for (i in v.indices) v[i] *= inv
            return v
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) s += a[i] * b[i]
            return s
        }
    }
}
