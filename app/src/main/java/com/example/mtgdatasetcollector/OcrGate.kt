package com.example.mtgdatasetcollector

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OcrGate(
    private val periodMs: Long,
    private val staleMs: Long,
    private val minChars: Int,
    private val minLines: Int
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val busy = AtomicBoolean(false)

    @Volatile private var lastKickMs: Long = 0L
    @Volatile private var lastOkMs: Long = 0L

    @Volatile private var lastTextNorm: String = ""
    @Volatile private var lastChars: Int = 0
    @Volatile private var lastLines: Int = 0
    @Volatile private var lastDeckish: Boolean = false

    fun close() {
        try { recognizer.close() } catch (_: Throwable) {}
    }

    fun maybeKick(image: ImageProxy, nowMs: Long) {
        if (nowMs - lastKickMs < periodMs) return
        if (!busy.compareAndSet(false, true)) return
        lastKickMs = nowMs

        val (nv21, w, h, rot) = try {
            val bytes = yuv420ToNv21(image)
            Quad(bytes, image.width, image.height, image.imageInfo.rotationDegrees)
        } catch (_: Throwable) {
            busy.set(false)
            return
        }

        val input = try {
            InputImage.fromByteArray(nv21, w, h, rot, InputImage.IMAGE_FORMAT_NV21)
        } catch (_: Throwable) {
            busy.set(false)
            return
        }

        recognizer.process(input)
            .addOnSuccessListener { res ->
                val raw = res.text ?: ""
                val lines = res.textBlocks.sumOf { it.lines.size }.coerceAtLeast(0)
                val norm = normalize(raw)
                val chars = norm.length

                lastTextNorm = norm
                lastChars = chars
                lastLines = lines
                lastOkMs = android.os.SystemClock.elapsedRealtime()

                val deck = isDeckish(norm)
                lastDeckish = deck
            }
            .addOnCompleteListener {
                busy.set(false)
            }
    }

    fun textPresentFresh(nowMs: Long): Boolean {
        val age = nowMs - lastOkMs
        if (age > staleMs) return false
        // Igual ao espírito do antigo: OU chars suficientes OU linhas suficientes
        return (lastChars >= minChars) || (lastLines >= minLines)
    }

    fun deckishFresh(nowMs: Long): Boolean {
        val age = nowMs - lastOkMs
        if (age > staleMs) return false
        return lastDeckish
    }

    // Debug helpers (igual engine antigo imprime)
    fun debugHasDeckish(): Boolean = lastDeckish
    fun debugChars(): Int = lastChars
    fun debugLines(): Int = lastLines
    fun debugAgeMs(nowMs: Long): Long = (nowMs - lastOkMs).coerceAtLeast(0)
    fun debugTextNorm(): String = lastTextNorm

    private fun normalize(s: String): String {
        val lower = s.lowercase(Locale.US)
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            if (ch in 'a'..'z') sb.append(ch)
        }
        return sb.toString()
    }

    private fun isDeckish(norm: String): Boolean {
        // tolerante: "deck", "deckmaster", truncados comuns do OCR
        return norm.contains("deckmaster") ||
                norm.contains("deckmaste") ||
                norm.contains("deckmast") ||
                norm.contains("deck")
    }

    // ---- YUV_420_888 -> NV21 (robusto com pixelStride/rowStride)
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val uvSize = w * h / 2
        val out = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride

        var outPos = 0
        for (row in 0 until h) {
            val rowStart = row * yRowStride
            if (yPixStride == 1) {
                yBuf.position(rowStart)
                yBuf.get(out, outPos, w)
                outPos += w
            } else {
                for (col in 0 until w) {
                    val yIndex = rowStart + col * yPixStride
                    out[outPos++] = yBuf.get(yIndex)
                }
            }
        }

        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride
        val chromaH = h / 2
        val chromaW = w / 2

        // NV21 = VU interleaved
        var uvOutPos = ySize
        for (row in 0 until chromaH) {
            val rowStart = row * uvRowStride
            for (col in 0 until chromaW) {
                val uvIndex = rowStart + col * uvPixStride
                out[uvOutPos++] = vBuf.get(uvIndex)
                out[uvOutPos++] = uBuf.get(uvIndex)
            }
        }

        return out
    }

    private data class Quad(val a: ByteArray, val b: Int, val c: Int, val d: Int)
}
