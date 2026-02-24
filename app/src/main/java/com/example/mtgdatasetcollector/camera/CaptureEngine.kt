package com.example.mtgdatasetcollector.camera

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.example.mtgdatasetcollector.OcrGate
import com.example.mtgdatasetcollector.model.CaptureStep
import kotlin.math.max
import kotlin.math.min

class CaptureEngine(
    private val rt: CaptureRuntime,
    private val cfg: Config = Config(),
    private val listener: Listener
) {

    data class Config(
        val stepDownsample: Int = 16,

        val needPresentFrames: Int = 5,

        // estabilidade por tempo (em ms)
        val needStableMs: Long = 800,

        // mantém um mínimo de frames pra não disparar por 1 frame “bonito”
        val needStableFrames: Int = 3,

        // >>> NOVO: depois de pedir foco, aguarda pelo menos isso antes de capturar
        val focusSettleMs: Long = 600,

        val needBgMatchFrames: Int = 6,
        val needBgMatchMs: Long = 420,

        val motionStableThr: Double = 10.0,
        val sharpThr: Double = 10.0,

        val focusCooldownMs: Long = 2000,
        val captureCooldownMs: Long = 700,

        val uiDebugEveryMs: Long = 120,
        val hintEveryMs: Long = 450,

        // ---- Background calibration
        val bgCalibNeedFrames: Int = 30,
        val bgCalibMotionThr: Double = 7.5,
        val bgDiffPresentThr: Double = 20.0,
        val bgDiffAbsentThr: Double = 17.0,

        // ---- OCR usado SOMENTE para calibração do fundo (pra não calibrar com carta)
        val ocrPeriodMs: Long = 260,
        val ocrStaleMs: Long = 1200,
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
        minChars = 6,
        minLines = 1
    )

    private var bgAcc: IntArray? = null
    private var bgAccCount: Int = 0

    private var bgMatchSinceMs: Long = 0L
    private var lastHintAtMs: Long = 0L

    private var bgCardPresent: Boolean = false

    // desde quando está estável (ms)
    private var stableSinceMs: Long = 0L

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
        stableSinceMs = 0L
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

            // “mão na lente / obstrução global” continua útil
            if (isLikelyObstructed(m)) {
                rt.presentFrames = 0
                rt.stableFrames = 0
                stableSinceMs = 0L
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
                        "sharp=${fmt(m.sharp)} mot=${fmt(motion)} mean=${fmt(m.mean)} std=${fmt(m.std)} range=${m.range}"
                    )
                }
                return
            }

            // ------------------------------------------------------------
            // CALIBRAÇÃO DO FUNDO (não calibrar com carta)
            // ------------------------------------------------------------
            if (!rt.bgReady || rt.calibratingBg.get()) {
                if (!rt.calibratingBg.get()) rt.calibratingBg.set(true)

                val motionOkForBg = motion < cfg.bgCalibMotionThr
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
                        stableSinceMs = 0L
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
                    if (now - lastHintAtMs > cfg.hintEveryMs) {
                        lastHintAtMs = now
                        listener.onHint("Remova a carta para calibrar o fundo (sem carta por 1 segundo)")
                    }
                    bgAcc = null
                    bgAccCount = 0
                }

                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "CALIB bgCount=$bgAccCount/${cfg.bgCalibNeedFrames} bgReady=${rt.bgReady} ocrCard=$ocrLooksLikeCardName",
                        "sharp=${fmt(m.sharp)} mot=${fmt(motion)} ocr(age=${ocrAge}ms chars=$ocrChars lines=$ocrLines)"
                    )
                }
                return
            }

            // ------------------------------------------------------------
            // RUN: detecta carta por diferença do fundo + estabilidade
            // ------------------------------------------------------------
            val bg = rt.bgSmall ?: run { requestRecalibrate(); return }

            val bgDiff = motionScore(bg, m.small)

            bgCardPresent = if (!bgCardPresent) {
                bgDiff >= cfg.bgDiffPresentThr
            } else {
                bgDiff > cfg.bgDiffAbsentThr
            }

            val motionOk = motion < cfg.motionStableThr
            val sharpOk = m.sharp > cfg.sharpThr

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
                    stableSinceMs = 0L
                    rt.lastSmall = null

                    val hint = if (rt.step == CaptureStep.FRONT) "Aponte para a FRENTE" else "Agora o VERSO"
                    listener.onHint(hint)
                }

                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "SWAP step=${rt.step} bgDiff=${fmt(bgDiff)} present=$bgCardPresent absentFrames=${rt.absentFrames}",
                        "sharp=${fmt(m.sharp)} mot=${fmt(motion)}"
                    )
                }
                return
            }

            rt.presentFrames = if (bgCardPresent) rt.presentFrames + 1 else 0
            if (rt.presentFrames < cfg.needPresentFrames) {
                rt.stableFrames = 0
                stableSinceMs = 0L
                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "WAIT step=${rt.step} bgDiff=${fmt(bgDiff)} present=$bgCardPresent presentFrames=${rt.presentFrames}/${cfg.needPresentFrames}",
                        "sharp=${fmt(m.sharp)} mot=${fmt(motion)}"
                    )
                }
                return
            }

            // pede foco quando está “quase lá”, mas sharp ainda não bateu
            if (bgCardPresent && motionOk && m.sharp < cfg.sharpThr * 0.92 && now - rt.lastFocusAt > cfg.focusCooldownMs) {
                rt.lastFocusAt = now
                listener.onRequestFocus()
            }

            val stableNow = bgCardPresent && motionOk && sharpOk
            if (stableNow) {
                if (stableSinceMs == 0L) stableSinceMs = now
                rt.stableFrames++
            } else {
                rt.stableFrames = 0
                stableSinceMs = 0L
            }

            val stableMs = if (stableSinceMs == 0L) 0L else (now - stableSinceMs)

            // >>> NOVO: bloqueia captura logo após pedir foco
            val sinceFocusMs = if (rt.lastFocusAt == 0L) 999_999L else (now - rt.lastFocusAt)
            val focusSettled = (rt.lastFocusAt == 0L) || (sinceFocusMs >= cfg.focusSettleMs)

            val shouldTrigger =
                bgCardPresent &&
                        (rt.stableFrames >= cfg.needStableFrames) &&
                        (stableMs >= cfg.needStableMs) &&
                        focusSettled

            if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                rt.lastUiAt = now
                listener.onDebug(
                    "RUN step=${rt.step} bgDiff=${fmt(bgDiff)} present=$bgCardPresent stable=${rt.stableFrames}/${cfg.needStableFrames} stableMs=${stableMs}/${cfg.needStableMs} focusMs=${sinceFocusMs}/${cfg.focusSettleMs}",
                    "sharp=${fmt(m.sharp)} mot=${fmt(motion)}"
                )
            }

            if (shouldTrigger && canTrigger(now)) {
                rt.stableFrames = 0
                stableSinceMs = 0L
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

    private fun canTrigger(now: Long): Boolean {
        if (rt.analyzing.get()) return false
        if (rt.calibratingBg.get()) return false
        if (rt.awaitingSwap.get()) return false
        if (rt.captureInProgress.get()) return false
        if (now - rt.lastCaptureAt < cfg.captureCooldownMs) return false
        return true
    }
}