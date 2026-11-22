package com.courieriot.stellargallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    const val REPO = "courier-iot/Stellar-Gallery"
    const val REPO_URL = "https://github.com/$REPO"

    data class Release(val version: String, val url: String)

    /** Returns the latest GitHub release, or null if offline / not found. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            try {
                if (c.responseCode != 200) return@runCatching null
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                Release(j.getString("tag_name").removePrefix("v"), j.getString("html_url"))
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
