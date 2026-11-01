package com.adrestarter

import android.content.Context
import android.os.SystemClock
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

/** Ajustes, utilidades y límite de seguridad contra bucles de reinicio. */
object Cfg {
    const val SPOTIFY = "com.spotify.music"

    // Seguridad: como máximo 3 reinicios cada 5 min y 20 s mínimo entre uno y otro
    private const val COOLDOWN_MS = 20_000L
    private const val WINDOW_MS = 5 * 60_000L
    private const val MAX_IN_WINDOW = 3

    // Textos que Spotify muestra durante un anuncio (se comparan sin tildes ni mayúsculas)
    private val BUILTIN_AD_WORDS = setOf(
        "advertisement", "advertisment", "publicidad", "anuncio", "publicitat", "anunci",
        "werbung", "publicite", "annonce", "pubblicita", "reclame", "reklam", "reklama",
        "publicidade", "anuncio de spotify"
    )

    private val stamps = ArrayList<Long>()

    private fun prefs(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()

    fun extraWords(c: Context): String = prefs(c).getString("extra", "") ?: ""
    fun setExtraWords(c: Context, v: String) = prefs(c).edit().putString("extra", v).apply()

    fun lastSeen(c: Context): String = prefs(c).getString("seen", "—") ?: "—"
    fun setLastSeen(c: Context, v: String) {
        if (v != lastSeen(c)) prefs(c).edit().putString("seen", v).apply()
    }

    fun lastResult(c: Context): String = prefs(c).getString("result", "—") ?: "—"
    fun setResult(c: Context, v: String) {
        val t = DateFormat.getTimeInstance().format(Date())
        prefs(c).edit().putString("result", "[$t] $v").apply()
    }

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()
            .lowercase(Locale.ROOT)

    fun adWords(c: Context): Set<String> {
        val extra = extraWords(c).split(',').map { norm(it) }.filter { it.isNotEmpty() }
        return BUILTIN_AD_WORDS + extra
    }

    @Synchronized
    fun allowRestart(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (stamps.isNotEmpty() && now - stamps[0] > WINDOW_MS) stamps.removeAt(0)
        if (stamps.isNotEmpty() && now - stamps[stamps.size - 1] < COOLDOWN_MS) return false
        if (stamps.size >= MAX_IN_WINDOW) return false
        stamps.add(now)
        return true
    }
}
