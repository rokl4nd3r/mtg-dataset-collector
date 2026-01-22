package com.example.mtgdatasetcollector.util

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IdGenerator {
    private const val PREFS = "idgen"
    private const val KEY_SEQ = "seq6"

    // Ex: 20260122_235959_123
    private val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun nextImageFileName(
        context: Context,
        side: String,          // "front" | "back"
        ext: String = "jpg"
    ): String {
        val ts = sdf.format(Date())
        val device = sanitizeDeviceTag(Build.MODEL ?: "device")
        val seq6 = nextSeq6(context)
        val sideNorm = normalizeSide(side)

        return "${ts}_${device}_${seq6}_${sideNorm}.${ext}"
    }

    private fun nextSeq6(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // atomicidade suficiente pro nosso caso (UI thread). Se virar multi-thread depois, trocamos pra DataStore/Room.
        val current = prefs.getInt(KEY_SEQ, 0)
        val next = (current + 1) % 1_000_000

        prefs.edit().putInt(KEY_SEQ, next).apply()
        return next.toString().padStart(6, '0')
    }

    private fun normalizeSide(side: String): String {
        val s = side.trim().lowercase(Locale.US)
        return when (s) {
            "front", "frente" -> "front"
            "back", "verso" -> "back"
            else -> s.ifBlank { "front" }
        }
    }

    private fun sanitizeDeviceTag(raw: String): String {
        val s = raw.trim().lowercase(Locale.US)
        val cleaned = s.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return (if (cleaned.isBlank()) "device" else cleaned).take(20)
    }
}
