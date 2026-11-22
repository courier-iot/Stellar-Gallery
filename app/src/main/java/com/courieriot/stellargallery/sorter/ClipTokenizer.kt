package com.courieriot.stellargallery.sorter

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * CLIP byte-level BPE tokenizer, read from a Hugging Face tokenizer.json.
 * Only used for typed search ("orange cat"); always returns 77 token ids.
 */
class ClipTokenizer(json: String) {
    private val vocab = HashMap<String, Int>(60_000)
    private val ranks = HashMap<String, Int>(60_000)
    private val byteEncoder = CharArray(256)
    private val cache = HashMap<String, List<String>>()
    private val pattern = Regex(
        "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
    )
    val bos: Int
    val eos: Int
    val pad: Int

    init {
        val root = JSONObject(json)
        val model = root.getJSONObject("model")
        val v = model.getJSONObject("vocab")
        v.keys().forEach { k -> vocab[k] = v.getInt(k) }
        root.optJSONArray("added_tokens")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                vocab[t.getString("content")] = t.getInt("id")
            }
        }
        val merges: JSONArray = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            val m = merges.get(i)
            val key = if (m is JSONArray) m.getString(0) + " " + m.getString(1) else m.toString()
            ranks[key] = i
        }
        // GPT-2 / CLIP byte → unicode table
        val bs = ArrayList<Int>()
        bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
        for (i in bs.indices) byteEncoder[bs[i]] = cs[i].toChar()

        bos = vocab["<|startoftext|>"] ?: 49406
        eos = vocab["<|endoftext|>"] ?: 49407
        pad = vocab["!"] ?: 0
    }

    /** Returns (ids, mask), both 77 long. */
    fun encode(text: String): Pair<LongArray, LongArray> {
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ").trim().lowercase()
        val ids = ArrayList<Int>()
        ids += bos
        for (m in pattern.findAll(clean)) {
            val bytes = m.value.toByteArray(Charsets.UTF_8)
            val word = buildString { bytes.forEach { append(byteEncoder[it.toInt() and 0xFF]) } }
            for (piece in bpe(word)) ids += vocab[piece] ?: eos
            if (ids.size >= 76) break
        }
        while (ids.size > 76) ids.removeAt(ids.lastIndex)
        ids += eos
        val out = LongArray(77) { pad.toLong() }
        val mask = LongArray(77)
        ids.forEachIndexed { i, id -> out[i] = id.toLong(); mask[i] = 1 }
        return out to mask
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()
        var word: MutableList<String> = token.map { it.toString() }.toMutableList()
        word[word.lastIndex] = word.last() + "</w>"
        while (word.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] + " " + word[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            val first = word[best]
            val second = word[best + 1]
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged += first + second; i += 2
                } else {
                    merged += word[i]; i += 1
                }
            }
            word = merged
        }
        if (cache.size < 5000) cache[token] = word
        return word
    }
}
