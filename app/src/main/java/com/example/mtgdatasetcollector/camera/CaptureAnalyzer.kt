package com.example.mtgdatasetcollector.camera

import androidx.camera.core.ImageProxy
import com.example.mtgdatasetcollector.model.CaptureStep
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FrameMetrics(
    val small: ByteArray,
    val mean: Double,
    val std: Double,
    val darkFrac: Double,
    val edgeFrac: Double,
    val sharp: Double,
    val minVal: Int,
    val maxVal: Int
) {
    val range: Int get() = maxVal - minVal
}

class CaptureRuntime {
    val captureInProgress = AtomicBoolean(false)
    val analyzing = AtomicBoolean(false)
    val awaitingSwap = AtomicBoolean(false)

    @Volatile var step: CaptureStep = CaptureStep.FRONT
    @Volatile var stableFrames: Int = 0
    @Volatile var presentFrames: Int = 0
    @Volatile var absentFrames: Int = 0
    @Volatile var lastCaptureAt: Long = 0L
    @Volatile var lastUiAt: Long = 0L
    @Volatile var lastFocusAt: Long = 0L

    @Volatile var baseEdge: Double = 0.0
    @Volatile var baseStd: Double = 0.0
    @Volatile var baseRange: Double = 0.0
    @Volatile var baseCount: Int = 0

    var lastSmall: ByteArray? = null
}

fun frameMetricsWide(image: ImageProxy, step: Int = 16): FrameMetrics {
    val plane = image.planes[0]
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val w = image.width
    val h = image.height

    val buf = plane.buffer.duplicate().apply { clear() }
    val cap = buf.capacity()

    val margin = 0.12
    val x0 = (w * margin).toInt()
    val x1 = (w * (1.0 - margin)).toInt()
    val y0 = (h * margin).toInt()
    val y1 = (h * (1.0 - margin)).toInt()

    val dx = max(1, x1 - x0)
    val dy = max(1, y1 - y0)

    val outW = max(1, (dx + step - 1) / step)
    val outH = max(1, (dy + step - 1) / step)
    val out = ByteArray(outW * outH)

    var idx = 0
    var sum = 0.0
    var sum2 = 0.0
    var dark = 0

    val darkThr = 80
    var minV = 255
    var maxV = 0

    var y = y0
    while (y < y1) {
        val rowBase = y * rowStride
        var x = x0
        while (x < x1) {
            val pos = rowBase + x * pixelStride
            val safePos = when {
                pos < 0 -> 0
                pos >= cap -> cap - 1
                else -> pos
            }

            val v = buf.get(safePos).toInt() and 0xFF

            if (idx < out.size) out[idx++] = v.toByte()

            sum += v
            sum2 += v.toDouble() * v.toDouble()
            if (v < darkThr) dark++
            if (v < minV) minV = v
            if (v > maxV) maxV = v

            x += step
        }
        y += step
    }

    val n = max(1, min(idx, out.size))
    val mean = sum / n
    val varr = max(0.0, (sum2 / n) - (mean * mean))
    val std = sqrt(varr)
    val darkFrac = dark.toDouble() / n.toDouble()

    val ew = outW
    val eh = outH
    fun px(ix: Int, iy: Int): Int = out[iy * ew + ix].toInt() and 0xFF

    var edges = 0
    var gradSum = 0.0
    if (ew >= 3 && eh >= 3) {
        for (yy in 1 until eh - 1) {
            for (xx in 1 until ew - 1) {
                val dxp = abs(px(xx + 1, yy) - px(xx - 1, yy))
                val dyp = abs(px(xx, yy + 1) - px(xx, yy - 1))
                val g = dxp + dyp
                gradSum += g
                if (g > 70) edges++
            }
        }
    }

    val denom = max(1.0, ((ew - 2).coerceAtLeast(1) * (eh - 2).coerceAtLeast(1)).toDouble())
    val edgeFrac = edges.toDouble() / denom
    val sharp = gradSum / denom

    return FrameMetrics(out, mean, std, darkFrac, edgeFrac, sharp, minV, maxV)
}

fun updateBlankBaselineIfCandidate(m: FrameMetrics, rt: CaptureRuntime) {
    val looksVeryBlank =
        (m.edgeFrac <= 0.007 && m.std <= 9.0 && m.range <= 26) ||
                (m.edgeFrac <= 0.006 && m.std <= 8.0)

    if (!looksVeryBlank) return

    val alpha = 0.15
    if (rt.baseCount == 0) {
        rt.baseEdge = m.edgeFrac
        rt.baseStd = m.std
        rt.baseRange = m.range.toDouble()
        rt.baseCount = 1
        return
    }

    rt.baseEdge = rt.baseEdge * (1.0 - alpha) + m.edgeFrac * alpha
    rt.baseStd = rt.baseStd * (1.0 - alpha) + m.std * alpha
    rt.baseRange = rt.baseRange * (1.0 - alpha) + m.range.toDouble() * alpha
    rt.baseCount = (rt.baseCount + 1).coerceAtMost(5000)
}

fun isLikelyCardPresent(m: FrameMetrics, rt: CaptureRuntime): Boolean {
    val baseEdge = if (rt.baseCount > 0) rt.baseEdge else 0.006
    val baseStd = if (rt.baseCount > 0) rt.baseStd else 8.0
    val baseRange = if (rt.baseCount > 0) rt.baseRange else 22.0

    val edgeBlankThr = max(0.010, baseEdge + 0.006)
    val stdBlankThr = max(11.0, baseStd + 4.0)
    val rangeBlankThr = max(30.0, baseRange + 12.0)

    val looksBlank =
        (m.edgeFrac <= edgeBlankThr && m.std <= stdBlankThr) ||
                (m.edgeFrac <= edgeBlankThr * 0.85 && m.range.toDouble() <= rangeBlankThr)

    if (looksBlank) return false

    val hasStructure =
        (m.edgeFrac >= max(0.015, edgeBlankThr + 0.006)) ||
                (m.std >= max(16.0, stdBlankThr + 4.0))

    val hasContrast =
        (m.range >= max(45, rangeBlankThr.toInt() + 10)) ||
                (m.std >= max(18.0, stdBlankThr + 6.0))

    val hasDarkInk = (m.minVal <= 70) || (m.darkFrac >= 0.04)

    return hasStructure && hasContrast && hasDarkInk
}

fun motionScore(a: ByteArray, b: ByteArray): Double {
    val n = min(a.size, b.size)
    if (n <= 0) return 999.0
    var sum = 0L
    for (i in 0 until n) {
        val da = a[i].toInt() and 0xFF
        val db = b[i].toInt() and 0xFF
        sum += abs(da - db)
    }
    return sum.toDouble() / n.toDouble()
}

fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
