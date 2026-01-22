package com.example.mtgdatasetcollector.camera

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.example.mtgdatasetcollector.OcrGate
import com.example.mtgdatasetcollector.model.CaptureStep

class CaptureEngine(
    private val rt: CaptureRuntime,
    private val cfg: Config = Config(),
    private val listener: Listener
) {

    data class Config(
        val stepDownsample: Int = 16,

        val needPresentFrames: Int = 4,
        val needStable: Int = 4,
        val fallbackStable: Int = 14,
        val needAbsent: Int = 3,

        val motionStableThr: Double = 10.0,
        val sharpThr: Double = 10.0,

        val focusCooldownMs: Long = 2000,
        val captureCooldownMs: Long = 700,

        val uiDebugEveryMs: Long = 120,

        // OCR gate
        val ocrPeriodMs: Long = 320,
        val ocrStaleMs: Long = 1200,
        val ocrMinChars: Int = 28,
        val ocrMinLines: Int = 2,

        // quando vale tentar OCR
        val ocrMotionFactor: Double = 2.0,
        val ocrSharpFactorFront: Double = 0.55,
        val ocrSharpFactorBack: Double = 0.35
    )

    interface Listener {
        fun onDebug(line1: String, line2: String) {}
        fun onHint(text: String) {}
        fun onRequestFocus() {}
        fun onTriggerCapture(step: CaptureStep) {}
        fun onError(t: Throwable) {}
    }

    private val ocrGate = OcrGate(
        periodMs = cfg.ocrPeriodMs,
        staleMs = cfg.ocrStaleMs,
        minChars = cfg.ocrMinChars,
        minLines = cfg.ocrMinLines
    )

    fun close() = ocrGate.close()

    fun analyze(image: ImageProxy) {
        try {
            if (rt.analyzing.get()) return

            val now = SystemClock.elapsedRealtime()
            val m = frameMetricsWide(image, step = cfg.stepDownsample)

            val prev = rt.lastSmall
            rt.lastSmall = m.small
            val motion = if (prev == null) 999.0 else motionScore(prev, m.small)

            if (motion < cfg.motionStableThr) {
                updateBlankBaselineIfCandidate(m, rt)
            }

            val sharp = m.sharp
            val likelyPresent = isLikelyCardPresent(m, rt)

            // Dispara OCR só quando parece “carta” e não tá tremendo demais.
            val motionOkForOcr = motion < (cfg.motionStableThr * cfg.ocrMotionFactor)
            val sharpFactor = if (rt.step == CaptureStep.BACK) cfg.ocrSharpFactorBack else cfg.ocrSharpFactorFront
            val sharpOkForOcr = sharp > (cfg.sharpThr * sharpFactor)

            if (likelyPresent && motionOkForOcr && sharpOkForOcr) {
                ocrGate.maybeKick(image, now)
            }

            // FRONT: thresholds genéricos (qualquer texto suficiente)
            // BACK: "deck" tolerante (deckish)
            val textOk = if (rt.step == CaptureStep.BACK) {
                ocrGate.deckishFresh(now)
            } else {
                ocrGate.textPresentFresh(now)
            }

            val present = likelyPresent && textOk

            if (likelyPresent && sharp < cfg.sharpThr * 0.9 && now - rt.lastFocusAt > cfg.focusCooldownMs) {
                rt.lastFocusAt = now
                listener.onRequestFocus()
            }

            if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                rt.lastUiAt = now
                listener.onDebug(
                    "step=${rt.step} present=$present (likely=$likelyPresent textOk=$textOk deck=${ocrGate.debugHasDeckish()}) awaitSwap=${rt.awaitingSwap.get()} stable=${rt.stableFrames} presentFrames=${rt.presentFrames} absent=${rt.absentFrames}",
                    "mean=${fmt(m.mean)} std=${fmt(m.std)} range=${m.range} min=${m.minVal} max=${m.maxVal} edge=${fmt(m.edgeFrac)} sharp=${fmt(sharp)} mot=${fmt(motion)} | ocr(chars=${ocrGate.debugChars()} lines=${ocrGate.debugLines()} age=${ocrGate.debugAgeMs(now)}ms norm='${ocrGate.debugTextNorm().take(24)}') base(edge=${fmt(rt.baseEdge)} std=${fmt(rt.baseStd)} range=${fmt(rt.baseRange)})"
                )
            }

            if (rt.awaitingSwap.get()) {
                if (!present) rt.absentFrames++ else rt.absentFrames = 0

                if (rt.absentFrames >= cfg.needAbsent) {
                    rt.awaitingSwap.set(false)
                    rt.absentFrames = 0
                    rt.stableFrames = 0
                    rt.presentFrames = 0
                    rt.lastSmall = null

                    val hint = if (rt.step == CaptureStep.FRONT) "Aponte para a FRENTE" else "Agora o VERSO"
                    listener.onHint(hint)
                }
                return
            }

            rt.presentFrames = if (present) rt.presentFrames + 1 else 0
            if (rt.presentFrames < cfg.needPresentFrames) {
                rt.stableFrames = 0
                return
            }

            val motionOk = motion < cfg.motionStableThr
            val sharpOk = sharp > cfg.sharpThr

            rt.stableFrames = if (motionOk) rt.stableFrames + 1 else 0

            val shouldTrigger =
                (rt.stableFrames >= cfg.needStable && sharpOk) ||
                        (rt.stableFrames >= cfg.fallbackStable)

            if (shouldTrigger && canTrigger(now)) {
                rt.stableFrames = 0
                rt.lastCaptureAt = now
                rt.captureInProgress.set(true)
                listener.onTriggerCapture(rt.step)
            }
        } catch (t: Throwable) {
            listener.onError(t)
        } finally {
            image.close()
        }
    }

    fun markCaptureFinished() {
        rt.captureInProgress.set(false)
    }

    fun setAnalyzing(v: Boolean) {
        rt.analyzing.set(v)
    }

    private fun canTrigger(now: Long): Boolean {
        if (rt.analyzing.get()) return false
        if (rt.awaitingSwap.get()) return false
        if (rt.captureInProgress.get()) return false
        if (now - rt.lastCaptureAt < cfg.captureCooldownMs) return false
        return true
    }
}
