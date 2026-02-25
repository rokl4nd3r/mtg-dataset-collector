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

    /**
     * Gera um ID BASE por carta:
     * 20260224_210046_540_SM-A736B_000004
     */
    fun nextBaseId(context: Context): String {
        val ts = sdf.format(Date())
        val device = sanitizeDeviceTag(Build.MODEL ?: "device")
        val seq6 = nextSeq6(context)
        return "${ts}_${device}_${seq6}"
    }

    fun imageFileNameFromBase(baseId: String, side: String, ext: String = "jpg"): String {
        val sideNorm = normalizeSide(side)
        return "${baseId}_${sideNorm}.${ext}"
    }

    /**
     * Mantido por compatibilidade (gera baseId novo a cada chamada).
     * Prefira: nextBaseId + imageFileNameFromBase.
     */
    fun nextImageFileName(context: Context, side: String, ext: String = "jpg"): String {
        val base = nextBaseId(context)
        return imageFileNameFromBase(base, side, ext)
    }

    private fun nextSeq6(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    /**
     * Mantém formato tipo "SM-A736B".
     * Permitidos: A-Z a-z 0-9 _ -
     */
    private fun sanitizeDeviceTag(raw: String): String {
        val s0 = raw.trim().replace(' ', '_')
        val cleaned = s0.replace(Regex("[^A-Za-z0-9_\\-]+"), "_").trim('_')
        return (if (cleaned.isBlank()) "device" else cleaned).take(32)
    }
}