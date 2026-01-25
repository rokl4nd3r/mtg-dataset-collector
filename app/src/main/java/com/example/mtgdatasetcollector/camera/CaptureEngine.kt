package com.example.mtgdatasetcollector.camera

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.example.mtgdatasetcollector.OcrGate
import com.example.mtgdatasetcollector.model.CaptureStep
import kotlin.math.max

class CaptureEngine(
    private val rt: CaptureRuntime,
    private val cfg: Config = Config(),
    private val listener: Listener
) {

    data class Config(
        val stepDownsample: Int = 16,

        val needPresentFrames: Int = 5,
        val needStable: Int = 5,

        val needBgMatchFrames: Int = 6,
        val needBgMatchMs: Long = 420,

        val motionStableThr: Double = 10.0,
        val sharpThr: Double = 10.0,

        val focusCooldownMs: Long = 2000,
        val captureCooldownMs: Long = 700,

        val uiDebugEveryMs: Long = 120,
        val hintEveryMs: Long = 450,

        // ---- Background calibration
        val bgCalibNeedFrames: Int = 12,
        val bgCalibMotionThr: Double = 7.5,
        val bgDiffPresentThr: Double = 12.0,
        val bgDiffAbsentThr: Double = 9.0,

        // ---- OCR gate (TRAVA de captura)
        val ocrPeriodMs: Long = 260,
        val ocrStaleMs: Long = 1200,
        val ocrMinChars: Int = 6,
        val ocrMinLines: Int = 1,

        val ocrMotionFactor: Double = 2.0,
        val ocrSharpFactorFront: Double = 0.55,
        val ocrSharpFactorBack: Double = 0.35,

        val ocrUseMaxAgeMs: Long = 450,

        // ---- NOVO: OCR mais “forte” para decidir se existe CARTA durante calibração
        val calibOcrMaxAgeMs: Long = 700,
        val calibOcrMinChars: Int = 12
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

    private var bgAcc: IntArray? = null
    private var bgAccCount: Int = 0

    private var bgMatchSinceMs: Long = 0L
    private var lastHintAtMs: Long = 0L

    private var bgCardPresent: Boolean = false

    fun close() = ocrGate.close()

    fun requestRecalibrate() {
        if (rt.captureInProgress.get()) return
        rt.clearBg()
        rt.calibratingBg.set(true)
        bgAcc = null
        bgAccCount = 0
        bgMatchSinceMs = 0L
        rt.absentFrames = 0
        rt.presentFrames = 0
        rt.stableFrames = 0
        rt.lastSmall = null
        bgCardPresent = false
        listener.onHint("Recalibrando fundo... mantenha SEM carta por 1 segundo")
    }

    fun analyze(image: ImageProxy) {
        try {
            if (rt.analyzing.get()) return

            val now = SystemClock.elapsedRealtime()
            val m = frameMetricsWide(image, step = cfg.stepDownsample)

            val prev = rt.lastSmall
            rt.lastSmall = m.small
            val motion = if (prev == null) 999.0 else motionScore(prev, m.small)

            if (isLikelyObstructed(m)) {
                rt.presentFrames = 0
                rt.stableFrames = 0
                rt.absentFrames = 0
                bgMatchSinceMs = 0L

                if (now - lastHintAtMs > cfg.hintEveryMs) {
                    lastHintAtMs = now
                    listener.onHint("Lente obstruída (mão na frente). Afaste e tente novamente.")
                }

                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "step=${rt.step} obstructed=true bgReady=${rt.bgReady} calibrating=${rt.calibratingBg.get()} awaitSwap=${rt.awaitingSwap.get()}",
                        "mean=${fmt(m.mean)} std=${fmt(m.std)} range=${m.range} min=${m.minVal} max=${m.maxVal} edge=${fmt(m.edgeFrac)} sharp=${fmt(m.sharp)} mot=${fmt(motion)}"
                    )
                }
                return
            }

            // baseline antigo (debug)
            if (motion < cfg.motionStableThr) {
                updateBlankBaselineIfCandidate(m, rt)
            }

            // ------------------------------------------------------------
            // CALIBRAÇÃO DO FUNDO: NÃO usa heurística visual (tripé engana).
            // Decide “tem carta” somente por OCR (nome).
            // ------------------------------------------------------------
            if (!rt.bgReady || rt.calibratingBg.get()) {
                if (!rt.calibratingBg.get()) rt.calibratingBg.set(true)

                val motionOkForBg = motion < cfg.bgCalibMotionThr

                // Durante calibração, chuta OCR sempre que estiver estável e sharp razoável.
                val sharpOkForCalibOcr = m.sharp > (cfg.sharpThr * 0.40)
                if (motionOkForBg && sharpOkForCalibOcr) {
                    ocrGate.maybeKick(image, now)
                }

                val ocrAge = ocrGate.debugAgeMs(now)
                val ocrChars = ocrGate.debugChars()
                val ocrLines = ocrGate.debugLines()

                val ocrLooksLikeCardName =
                    (ocrAge <= cfg.calibOcrMaxAgeMs) &&
                            (ocrChars >= cfg.calibOcrMinChars || ocrLines >= 2)

                if (motionOkForBg && !ocrLooksLikeCardName) {
                    val acc = bgAcc ?: IntArray(m.small.size).also { bgAcc = it }
                    if (acc.size != m.small.size) {
                        bgAcc = IntArray(m.small.size)
                        bgAccCount = 0
                    }

                    val a = bgAcc!!
                    for (i in m.small.indices) {
                        a[i] += (m.small[i].toInt() and 0xFF)
                    }
                    bgAccCount++

                    if (bgAccCount >= cfg.bgCalibNeedFrames) {
                        val out = ByteArray(a.size)
                        val div = bgAccCount
                        for (i in a.indices) {
                            out[i] = (a[i] / div).coerceIn(0, 255).toByte()
                        }
                        rt.bgSmall = out
                        rt.bgReady = true
                        rt.bgBuiltAtMs = now
                        rt.calibratingBg.set(false)

                        rt.awaitingSwap.set(false)
                        rt.step = CaptureStep.FRONT
                        rt.presentFrames = 0
                        rt.stableFrames = 0
                        rt.absentFrames = 0
                        rt.lastSmall = null
                        bgCardPresent = false
                        bgMatchSinceMs = 0L

                        listener.onHint("Fundo calibrado ✅ Aponte para a FRENTE")
                    } else {
                        if (now - lastHintAtMs > cfg.hintEveryMs) {
                            lastHintAtMs = now
                            listener.onHint("Calibrando fundo... mantenha SEM carta por 1 segundo")
                        }
                    }
                } else {
                    // Se OCR parece carta, não acumula fundo e ainda zera o acumulador para não contaminar.
                    if (ocrLooksLikeCardName) {
                        bgAcc = null
                        bgAccCount = 0
                        if (now - lastHintAtMs > cfg.hintEveryMs) {
                            lastHintAtMs = now
                            listener.onHint("Remova a carta para calibrar o fundo (sem carta por 1 segundo)")
                        }
                    } else {
                        // motion ruim: pede estabilidade
                        if (now - lastHintAtMs > cfg.hintEveryMs) {
                            lastHintAtMs = now
                            listener.onHint("Calibrando fundo... mantenha o celular parado por 1 segundo")
                        }
                    }
                }

                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "CALIB bgCount=$bgAccCount/${cfg.bgCalibNeedFrames} motionOk=${motionOkForBg} bgReady=${rt.bgReady} ocrCard=$ocrLooksLikeCardName",
                        "mean=${fmt(m.mean)} std=${fmt(m.std)} range=${m.range} min=${m.minVal} max=${m.maxVal} edge=${fmt(m.edgeFrac)} sharp=${fmt(m.sharp)} mot=${fmt(motion)} | ocr(age=${ocrAge}ms chars=$ocrChars lines=$ocrLines)"
                    )
                }
                return
            }

            // ---- daqui pra baixo mantém a lógica por diferença do fundo + OCR como trava de captura

            val bg = rt.bgSmall
            if (bg == null) {
                requestRecalibrate()
                return
            }

            val bgDiff = motionScore(bg, m.small)

            bgCardPresent = if (!bgCardPresent) {
                bgDiff >= cfg.bgDiffPresentThr
            } else {
                bgDiff > cfg.bgDiffAbsentThr
            }

            val motionOk = motion < cfg.motionStableThr
            val sharpOk = m.sharp > cfg.sharpThr

            val motionOkForOcr = motion < (cfg.motionStableThr * cfg.ocrMotionFactor)
            val sharpFactor = if (rt.step == CaptureStep.BACK) cfg.ocrSharpFactorBack else cfg.ocrSharpFactorFront
            val sharpOkForOcr = m.sharp > (cfg.sharpThr * sharpFactor)

            if (bgCardPresent && motionOkForOcr && sharpOkForOcr) {
                ocrGate.maybeKick(image, now)
            }

            val ocrAge = ocrGate.debugAgeMs(now)
            val ocrFreshEnough = ocrAge <= cfg.ocrUseMaxAgeMs
            val ocrOk = if (rt.step == CaptureStep.BACK) ocrGate.deckishFresh(now) else ocrGate.textPresentFresh(now)
            val ocrOkFresh = ocrOk && ocrFreshEnough

            if (rt.awaitingSwap.get()) {
                val bgMatch = (!bgCardPresent) && (bgDiff <= cfg.bgDiffAbsentThr) && (motion < cfg.motionStableThr * 1.5)

                if (bgMatch) {
                    rt.absentFrames++
                    if (bgMatchSinceMs == 0L) bgMatchSinceMs = now
                } else {
                    rt.absentFrames = 0
                    bgMatchSinceMs = 0L
                }

                val longEnough = bgMatchSinceMs != 0L && (now - bgMatchSinceMs) >= cfg.needBgMatchMs
                if (rt.absentFrames >= cfg.needBgMatchFrames && longEnough) {
                    rt.awaitingSwap.set(false)
                    rt.absentFrames = 0
                    bgMatchSinceMs = 0L
                    rt.presentFrames = 0
                    rt.stableFrames = 0
                    rt.lastSmall = null

                    val hint = if (rt.step == CaptureStep.FRONT) "Aponte para a FRENTE" else "Agora o VERSO"
                    listener.onHint(hint)
                }

                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "SWAP step=${rt.step} bgDiff=${fmt(bgDiff)} present=$bgCardPresent absentFrames=${rt.absentFrames}",
                        "sharp=${fmt(m.sharp)} mot=${fmt(motion)} | ocr(age=${ocrAge}ms okFresh=$ocrOkFresh)"
                    )
                }
                return
            }

            rt.presentFrames = if (bgCardPresent) rt.presentFrames + 1 else 0
            if (rt.presentFrames < cfg.needPresentFrames) {
                rt.stableFrames = 0
                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "WAIT step=${rt.step} bgDiff=${fmt(bgDiff)} present=$bgCardPresent presentFrames=${rt.presentFrames}/${cfg.needPresentFrames}",
                        "sharp=${fmt(m.sharp)} mot=${fmt(motion)} | ocr(age=${ocrAge}ms okFresh=$ocrOkFresh)"
                    )
                }
                return
            }

            if (bgCardPresent && motionOk && m.sharp < cfg.sharpThr * 0.92 && now - rt.lastFocusAt > cfg.focusCooldownMs) {
                rt.lastFocusAt = now
                listener.onRequestFocus()
            }

            rt.stableFrames = if (motionOk && sharpOk) rt.stableFrames + 1 else 0

            val shouldTrigger =
                (rt.stableFrames >= cfg.needStable) &&
                        bgCardPresent &&
                        ocrOkFresh

            if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                rt.lastUiAt = now
                listener.onDebug(
                    "RUN step=${rt.step} bgDiff=${fmt(bgDiff)} present=$bgCardPresent stable=${rt.stableFrames}/${cfg.needStable} ocrOkFresh=$ocrOkFresh",
                    "sharp=${fmt(m.sharp)} mot=${fmt(motion)} | ocr(age=${ocrAge}ms chars=${ocrGate.debugChars()} lines=${ocrGate.debugLines()} deck=${ocrGate.debugHasDeckish()} norm='${ocrGate.debugTextNorm().take(24)}')"
                )
            }

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
        if (rt.calibratingBg.get()) return false
        if (rt.awaitingSwap.get()) return false
        if (rt.captureInProgress.get()) return false
        if (now - rt.lastCaptureAt < cfg.captureCooldownMs) return false
        return true
    }
}
