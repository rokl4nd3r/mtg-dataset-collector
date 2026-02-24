package com.example.mtgdatasetcollector.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.mtgdatasetcollector.model.CaptureStep
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object PhotoValidator {

    data class Result(
        val ok: Boolean,
        val reason: String,
        val debug: String = ""
    )

    private const val MAX_DIM = 720

    // Blur gate (bem permissivo)
    private const val BLUR_VAR_THR_FRONT = 110.0
    private const val BLUR_VAR_THR_BACK = 90.0

    // Rect detector (somente geométrico)
    private const val RECT_ASPECT_MIN = 0.55
    private const val RECT_ASPECT_MAX = 0.92

    // Quantos candidatos de pico tentamos em cada eixo (mais = mais robusto, ainda rápido em 720px)
    private const val PEAKS_K = 22

    // Min separação dos lados (em pixels do bitmap downsampled)
    private const val MIN_RECT_W = 90
    private const val MIN_RECT_H = 120

    // Edge density no verso (deckmaster) pra pegar dedo em cima do texto
    private const val EDGE_THR_MIN = 10
    private const val EDGE_P90_FACTOR = 0.55
    private const val BACK_ROI_EDGE_DENS_MIN = 0.012

    fun validateJpeg(file: File, step: CaptureStep): Result {
        val bmp = decodeDownsampled(file) ?: return Result(false, "DECODE_FAIL")
        try {
            val w = bmp.width
            val h = bmp.height
            if (w < 64 || h < 64) return Result(false, "TOO_SMALL", "w=$w h=$h")

            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)

            val gray = IntArray(px.size)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = (r * 3 + g * 4 + b) / 8
            }

            // 1) BLUR (hard fail)
            val varLap = laplacianVariance(gray, w, h)
            val blurThr = if (step == CaptureStep.BACK) BLUR_VAR_THR_BACK else BLUR_VAR_THR_FRONT
            if (varLap < blurThr) {
                return Result(false, "BLUR", "varLap=${"%.1f".format(varLap)} thr=$blurThr step=$step")
            }

            // 2) Detecta retângulo da carta por projeção de bordas orientadas (sem cor)
            val rect = detectCardRect(gray, w, h)
                ?: return Result(false, "RECT_NOT_FOUND", "step=$step varLap=${"%.1f".format(varLap)} (borda tampada? sombra? fora do quadro?)")

            val rw = rect.x1 - rect.x0 + 1
            val rh = rect.y1 - rect.y0 + 1
            val asp = rw.toDouble() / max(1, rh).toDouble()
            val inv = 1.0 / max(1e-6, asp)
            val okAsp = (asp in RECT_ASPECT_MIN..RECT_ASPECT_MAX) || (inv in RECT_ASPECT_MIN..RECT_ASPECT_MAX)
            if (!okAsp) {
                // não reprova por aspecto (só debug)
            }

            // 3) Verso: checa ROI do Deckmaster por edge-density (sem cor)
            if (step == CaptureStep.BACK) {
                val thrEdge = edgeThresholdFromP90(gray, w, h)
                val roi = roiBackDeckmaster(rect)
                val dens = edgeDensity(gray, w, h, thrEdge, roi)
                if (dens < BACK_ROI_EDGE_DENS_MIN) {
                    return Result(
                        false,
                        "OCCLUSION_BACK",
                        "roiEdgeDens=${"%.3f".format(dens)} min=$BACK_ROI_EDGE_DENS_MIN thrEdge=$thrEdge roi=${roi.x0},${roi.y0}..${roi.x1},${roi.y1}"
                    )
                }
            }

            val dbg = "OK step=$step varLap=${"%.1f".format(varLap)} rect=${rect.x0},${rect.y0}..${rect.x1},${rect.y1} asp=${"%.2f".format(asp)}"
            return Result(true, "OK", dbg)
        } finally {
            bmp.recycle()
        }
    }

    // ---------- Rect detector (grayscale only) ----------

    private data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun detectCardRect(gray: IntArray, w: Int, h: Int): Rect? {
        // usamos gradientes centrados:
        // dx = gray(x+1) - gray(x-1)
        // dy = gray(y+1) - gray(y-1)
        // e classificamos bordas verticais (|dx| > |dy|) e horizontais (|dy| >= |dx|)

        if (w < 16 || h < 16) return null

        // 1) hist do mag para achar p90
        val hist = IntArray(512)
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val dx = gray[i + 1] - gray[i - 1]
                val dy = gray[i + w] - gray[i - w]
                val mag = (abs(dx) + abs(dy)).coerceIn(0, 511)
                hist[mag]++
                n++
            }
        }
        if (n <= 0) return null
        val target = (n * 0.90).toInt().coerceAtLeast(1)
        var acc = 0
        var p90 = 8
        for (v in 0..511) {
            acc += hist[v]
            if (acc >= target) { p90 = v; break }
        }
        val thr = max(6, (p90 * 0.55).toInt())  // << sem “min 40”, isso era o inferno

        // 2) projeções orientadas
        val vproj = IntArray(w - 2) // para x=1..w-2 => idx=x-1
        val hproj = IntArray(h - 2) // para y=1..h-2 => idx=y-1

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val dx = gray[i + 1] - gray[i - 1]
                val dy = gray[i + w] - gray[i - w]
                val ax = abs(dx)
                val ay = abs(dy)
                val mag = ax + ay
                if (mag < thr) continue
                if (ax > ay) vproj[x - 1]++ else hproj[y - 1]++
            }
        }

        // 3) suaviza com moving average (barato e ajuda muito)
        val v = smooth(vproj, 17)
        val hp = smooth(hproj, 17)

        // 4) pega top-K picos (com poda por distância)
        val xs = topKPeaks(v, PEAKS_K, minSep = 10)
        val ys = topKPeaks(hp, PEAKS_K, minSep = 10)
        if (xs.size < 2 || ys.size < 2) return null

        // 5) escolhe o melhor retângulo que bate aspecto de carta
        var bestScore = -1.0
        var best: Rect? = null

        for (a in 0 until xs.size) {
            for (b in a + 1 until xs.size) {
                val xL = min(xs[a], xs[b])
                val xR = max(xs[a], xs[b])
                val bw = xR - xL
                if (bw < MIN_RECT_W) continue

                for (c in 0 until ys.size) {
                    for (d in c + 1 until ys.size) {
                        val yT = min(ys[c], ys[d])
                        val yB = max(ys[c], ys[d])
                        val bh = yB - yT
                        if (bh < MIN_RECT_H) continue

                        val asp = bw.toDouble() / max(1, bh).toDouble()
                        val inv = 1.0 / max(1e-6, asp)
                        val okAsp = (asp in RECT_ASPECT_MIN..RECT_ASPECT_MAX) || (inv in RECT_ASPECT_MIN..RECT_ASPECT_MAX)
                        if (!okAsp) continue

                        val score = (v[xL] + v[xR] + hp[yT] + hp[yB]).toDouble() + 0.0006 * (bw * bh).toDouble()
                        if (score > bestScore) {
                            bestScore = score
                            // converte para coords reais: +1 pq proj está em 1..w-2
                            best = Rect(x0 = xL + 1, y0 = yT + 1, x1 = xR + 1, y1 = yB + 1)
                        }
                    }
                }
            }
        }

        return best
    }

    private fun smooth(arr: IntArray, win: Int): IntArray {
        if (arr.size < win) return arr.clone()
        val out = IntArray(arr.size)
        val half = win / 2
        var sum = 0
        var cnt = 0

        // init
        for (i in 0 until min(arr.size, win)) { sum += arr[i]; cnt++ }
        for (i in arr.indices) {
            val l = i - half
            val r = i + half
            if (l - 1 >= 0) { sum -= arr[l - 1]; cnt-- }
            if (r < arr.size) { sum += arr[r]; cnt++ }
            out[i] = if (cnt > 0) (sum / cnt) else 0
        }
        return out
    }

    private fun topKPeaks(arr: IntArray, k: Int, minSep: Int): IntArray {
        val idx = arr.indices.sortedByDescending { arr[it] }
        val out = ArrayList<Int>(k)
        for (i in idx) {
            if (arr[i] <= 0) break
            if (out.all { abs(it - i) >= minSep }) {
                out.add(i)
                if (out.size >= k) break
            }
        }
        return out.toIntArray()
    }

    // ---------- Blur & edges (helpers) ----------

    private fun decodeDownsampled(file: File): Bitmap? {
        val o0 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o0)
        val srcW = o0.outWidth
        val srcH = o0.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sample = 1
        var w = srcW
        var h = srcH
        while (max(w, h) > MAX_DIM) {
            sample *= 2
            w /= 2
            h /= 2
        }

        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, o)
    }

    private fun laplacianVariance(gray: IntArray, w: Int, h: Int): Double {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = gray[i]
                val lap = 4 * c - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                val v = lap.toDouble()
                sum += v
                sumSq += v * v
                n++
            }
        }
        if (n <= 1) return 0.0
        val mean = sum / n
        return (sumSq / n) - (mean * mean)
    }

    private fun edgeThresholdFromP90(gray: IntArray, w: Int, h: Int): Int {
        val hist = IntArray(512)
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val dx = gray[i + 1] - gray[i - 1]
                val dy = gray[i + w] - gray[i - w]
                val g = (abs(dx) + abs(dy)).coerceIn(0, 511)
                hist[g]++
                n++
            }
        }
        val target = (n * 0.90).toInt().coerceAtLeast(1)
        var acc = 0
        var p90 = 18
        for (v in 0..511) {
            acc += hist[v]
            if (acc >= target) { p90 = v; break }
        }
        val thr = max(EDGE_THR_MIN, (p90 * EDGE_P90_FACTOR).toInt())
        return thr.coerceIn(EDGE_THR_MIN, 220)
    }

    private data class R(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun roiBackDeckmaster(b: Rect): R {
        val bw = b.x1 - b.x0 + 1
        val bh = b.y1 - b.y0 + 1

        val x0 = b.x0 + (bw * 0.25).toInt()
        val x1 = b.x0 + (bw * 0.75).toInt()
        val y0 = b.y0 + (bh * 0.62).toInt()
        val y1 = b.y0 + (bh * 0.88).toInt()

        return R(
            x0 = x0,
            y0 = y0,
            x1 = max(x0 + 1, x1),
            y1 = max(y0 + 1, y1)
        )
    }

    private fun edgeDensity(gray: IntArray, w: Int, h: Int, thr: Int, r: R): Double {
        val x0 = r.x0.coerceIn(1, w - 2)
        val x1 = r.x1.coerceIn(1, w - 2)
        val y0 = r.y0.coerceIn(1, h - 2)
        val y1 = r.y1.coerceIn(1, h - 2)

        var edges = 0
        var tot = 0
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val i = row + x
                val dx = gray[i + 1] - gray[i - 1]
                val dy = gray[i + w] - gray[i - w]
                val g = abs(dx) + abs(dy)
                if (g >= thr) edges++
                tot++
            }
        }
        if (tot <= 0) return 0.0
        return edges.toDouble() / tot.toDouble()
    }
}