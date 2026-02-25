package com.example.mtgdatasetcollector.camera

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.example.mtgdatasetcollector.OcrGate
import com.example.mtgdatasetcollector.model.CaptureStep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class CaptureEngine(
    private val rt: CaptureRuntime,
    private val cfg: Config = Config(),
    private val listener: Listener
) {

    data class RoiNorm(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun clamped(): RoiNorm {
            val l = left.coerceIn(0f, 1f)
            val t = top.coerceIn(0f, 1f)
            val r = right.coerceIn(0f, 1f)
            val b = bottom.coerceIn(0f, 1f)
            val rr = max(r, l + 0.01f).coerceIn(0f, 1f)
            val bb = max(b, t + 0.01f).coerceIn(0f, 1f)
            return RoiNorm(l, t, rr, bb)
        }
    }

    data class Config(
        val stepDownsample: Int = 16,

        // ---------------- UI / HINT ----------------
        val uiDebugEveryMs: Long = 120,
        val hintEveryMs: Long = 450,

        // ---------------- PRESENÇA / ESTABILIDADE ----------------
        // needPresentFrames: quantos frames "present=true" antes de começar a contar estabilidade
        // needStableMs / needStableFrames: quanto tempo/frames precisa ficar estável pra disparar
        val needPresentFrames: Int = 5,
        val needStableMs: Long = 800,
        val needStableFrames: Int = 3,

        // AJUSTE AQUI (1): estabilidade por movimento
        // motionStableThr = movimento dentro da ROI (grid)
        // motionStableThrFull = movimento no frame TODO (pega dedo fora da ROI)
        val motionStableThr: Double = 10.0,
        val motionStableThrFull: Double = 10.0, // <<< guard global (dedo em canto)

        // AJUSTE AQUI (2): nitidez mínima pra disparar
        val sharpThr: Double = 10.0,

        val focusSettleMs: Long = 600,
        val focusCooldownMs: Long = 2000,
        val captureCooldownMs: Long = 700,

        // ---------------- SWAP (REMOVER CARTA ENTRE FRENTE/VERSO) ----------------
        // OBS: no fluxo novo, a UI NÃO usa awaitingSwap. Mantemos aqui só como "modo legado".
        val swapSettleMs: Long = 650,
        val swapCoverageMax: Double = 0.35,
        val swapMotionThr: Double = 16.0,    // (tolerante) usado no SWAP

        val needBgMatchFrames: Int = 6,
        val needBgMatchMs: Long = 420,

        // ---------------- CALIBRAÇÃO DO FUNDO ----------------
        // bgCalibNeedFrames: quantos frames de fundo pra média
        // bgCalibMotionThr: trava se tiver movimento no frame todo (evita calibrar com mão)
        val bgCalibNeedFrames: Int = 30,
        val bgCalibMotionThr: Double = 7.5,

        // ---------------- PRESENÇA POR bgDiff (FRONT/BACK) ----------------
        // AJUSTE AQUI (3): se o VERSO não "presenta", baixa os thrs do BACK.
        // - presentThr: acima disso considera carta presente (com histerese)
        // - absentThr: abaixo disso considera carta ausente
        val bgDiffPresentThrFront: Double = 20.0,
        val bgDiffAbsentThrFront: Double = 17.0,

        val bgDiffPresentThrBack: Double = 12.0,
        val bgDiffAbsentThrBack: Double = 10.0,

        // ---------------- OCR (só pra evitar calibrar com carta) ----------------
        val ocrPeriodMs: Long = 260,
        val ocrStaleMs: Long = 1200,
        val calibOcrMaxAgeMs: Long = 700,
        val calibOcrMinChars: Int = 12,

        // ---------------- ROI ----------------
        val roi: RoiNorm = RoiNorm(
            left = 0.12f,
            top = 0.18f,
            right = 0.88f,
            bottom = 0.82f
        ),

        // ---------------- ANTI-TEXTURA (GRID) ----------------
        val roiGrid: Int = 12,

        // ---------------- PRESENÇA POR "PREENCHIMENTO" (COVERAGE) ----------------
        // AJUSTE AQUI (4): coverage evita "present=true" com coisa pequena/ruído.
        // - roiDiffThr: diferença de pixel (0-255) pra contar como "mudou"
        // - roiMinCoverageEnter: % mínimo de pixels mudados pra considerar presença
        val roiCoverageGuardEnabled: Boolean = true,
        val roiDiffThr: Int = 8,
        val roiMinCoverageEnter: Double = 0.70,

        // ---------------- SWAP FAILSAFE: limiar adaptativo por ruído do fundo ----------------
        // AJUSTE AQUI (5): swapBgK / swapBgMargin mexem na tolerância do "fundo bateu".
        val swapBgK: Double = 4.5,
        val swapBgMargin: Double = 0.8,

        // ---------------- BACKGROUND TRACKING (drift AE/AWB) ----------------
        // AJUSTE AQUI (6): tracking faz EMA no fundo pra acompanhar drift de exposição/balanço.
        val bgTrackEnabled: Boolean = true,
        val bgTrackAlpha: Double = 0.06,
        val bgTrackThrMult: Double = 1.35,
        val bgTrackCoverageMax: Double = 0.45
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
    private var stableSinceMs: Long = 0L

    // NOVO: último frame "small" do frame TODO (pra motionFull)
    private var lastFullSmall: ByteArray? = null

    private var roiBufA: ByteArray? = null
    private var roiBufB: ByteArray? = null
    private var roiUseA: Boolean = true

    private var gridBufA: ByteArray? = null
    private var gridBufB: ByteArray? = null
    private var gridUseA: Boolean = true

    private val calibFrames: ArrayList<ByteArray> = arrayListOf()
    private var bgNoiseMu: Double = 0.0
    private var bgNoiseSigma: Double = 0.0
    private var bgSwapThr: Double = 0.0
    private var bgTrackThr: Double = 0.0

    // >>> qual passo deve ficar ativo após calibrar o fundo.
    // No fluxo novo, usamos isso para "recalibrar pro VERSO" logo após o GRADE FRONT,
    // e também pro botão Recalibrar NÃO resetar pro FRONT.
    private var calibNextStepAfterBg: CaptureStep = CaptureStep.FRONT

    fun close() = ocrGate.close()

    /**
     * Recalibra APENAS o fundo, e ao terminar mantém o step definido por nextStepAfterCalib.
     *
     * Retorna false se estiver "ocupado" (capturando/validando),
     * para a UI não assumir que recalibrou quando na verdade foi ignorado.
     */
    fun requestRecalibrate(nextStepAfterCalib: CaptureStep = CaptureStep.FRONT): Boolean {
        // AJUSTE AQUI: regras de bloqueio (pra evitar recalibrar no meio de uma foto/validação)
        if (rt.captureInProgress.get()) return false
        if (rt.analyzing.get()) return false

        calibNextStepAfterBg = nextStepAfterCalib

        rt.clearBg()
        rt.calibratingBg.set(true)

        bgAcc = null
        bgAccCount = 0
        calibFrames.clear()

        bgNoiseMu = 0.0
        bgNoiseSigma = 0.0
        bgSwapThr = 0.0
        bgTrackThr = 0.0

        bgMatchSinceMs = 0L
        rt.absentFrames = 0
        rt.presentFrames = 0
        rt.stableFrames = 0
        stableSinceMs = 0L
        rt.lastSmall = null
        bgCardPresent = false

        // fluxo novo: não usamos SWAP entre frente/verso
        rt.awaitingSwap.set(false)

        // RESET: evita “herdar” motionFull antigo
        lastFullSmall = null

        val hint = if (calibNextStepAfterBg == CaptureStep.FRONT) {
            "Recalibrando fundo... mantenha SEM carta por 1 segundo"
        } else {
            "Recalibrando fundo para o VERSO... mantenha SEM carta por 1 segundo"
        }
        listener.onHint(hint)
        return true
    }

    fun analyze(image: ImageProxy) {
        try {
            if (rt.analyzing.get()) return

            val now = SystemClock.elapsedRealtime()
            val m = frameMetricsWide(image, step = cfg.stepDownsample)

            // ---------------- motion do frame TODO (pega dedo fora da ROI) ----------------
            val prevFull = lastFullSmall
            val motionFull =
                if (prevFull == null || prevFull.size != m.small.size) 999.0 else motionScore(prevFull, m.small)
            lastFullSmall = m.small

            val (smallW, smallH) = deriveSmallDims(image, cfg.stepDownsample, m.small.size)
            val roiPx = roiToPx(cfg.roi.clamped(), smallW, smallH)

            val roiW = roiPx.w
            val roiH = roiPx.h
            val roiSize = roiW * roiH

            val roiSmall = extractRoiSmall(
                src = m.small,
                srcW = smallW,
                roi = roiPx,
                dst = acquireRoiBuffer(roiSize)
            )

            val g = max(2, cfg.roiGrid)
            val gridSmall = gridifyToNxN(
                src = roiSmall,
                srcW = roiW,
                srcH = roiH,
                n = g,
                dst = acquireGridBuffer(g * g)
            )

            val prev = rt.lastSmall
            rt.lastSmall = gridSmall
            val motionRoi =
                if (prev == null || prev.size != gridSmall.size) 999.0 else motionScore(prev, gridSmall)

            // obstrução global
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
                        "step=${rt.step} obstructed=true bgReady=${rt.bgReady} calibrating=${rt.calibratingBg.get()} awaitSwap=${rt.awaitingSwap.get()} roi=${roiW}x${roiH} grid=${g}x${g}",
                        "sharp=${fmt(m.sharp)} motRoi=${fmt(motionRoi)} motFull=${fmt(motionFull)} mean=${fmt(m.mean)} std=${fmt(m.std)} range=${m.range}"
                    )
                }
                return
            }

            // ---------------- CALIBRAÇÃO DO FUNDO ----------------
            if (!rt.bgReady || rt.calibratingBg.get()) {
                if (!rt.calibratingBg.get()) rt.calibratingBg.set(true)

                // usa motionFull pra evitar calibrar com mão passando fora da ROI
                val motionOkForBg = motionFull < cfg.bgCalibMotionThr
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
                    val acc = bgAcc ?: IntArray(gridSmall.size).also { bgAcc = it }
                    if (acc.size != gridSmall.size) {
                        bgAcc = IntArray(gridSmall.size)
                        bgAccCount = 0
                        calibFrames.clear()
                    }

                    val a = bgAcc!!
                    for (i in gridSmall.indices) a[i] += (gridSmall[i].toInt() and 0xFF)
                    bgAccCount++

                    calibFrames.add(gridSmall.copyOf())

                    if (bgAccCount >= cfg.bgCalibNeedFrames) {
                        val out = ByteArray(a.size)
                        val div = bgAccCount
                        for (i in a.indices) out[i] = (a[i] / div).coerceIn(0, 255).toByte()

                        computeBgNoiseStats(baseline = out)
                        bgSwapThr = bgNoiseMu + (cfg.swapBgK * bgNoiseSigma) + cfg.swapBgMargin
                        bgTrackThr = bgSwapThr * cfg.bgTrackThrMult

                        rt.bgSmall = out
                        rt.bgReady = true
                        rt.bgBuiltAtMs = now
                        rt.calibratingBg.set(false)

                        // >>> fluxo novo: sempre sai da calibração pronto pro passo pedido
                        rt.awaitingSwap.set(false)
                        rt.step = calibNextStepAfterBg

                        rt.presentFrames = 0
                        rt.stableFrames = 0
                        stableSinceMs = 0L
                        rt.absentFrames = 0
                        rt.lastSmall = null
                        bgCardPresent = false
                        bgMatchSinceMs = 0L

                        // RESET: evita “herdar” motionFull antigo após calibrar
                        lastFullSmall = null

                        val hint =
                            if (rt.step == CaptureStep.FRONT) "Fundo calibrado ✅ Aponte para a FRENTE"
                            else "Fundo calibrado ✅ Aponte para o VERSO"
                        listener.onHint(hint)
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
                    calibFrames.clear()
                }

                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "CALIB bgCount=$bgAccCount/${cfg.bgCalibNeedFrames} bgReady=${rt.bgReady} ocrCard=$ocrLooksLikeCardName roi=${roiW}x${roiH} grid=${g}x${g}",
                        "sharp=${fmt(m.sharp)} motRoi=${fmt(motionRoi)} motFull=${fmt(motionFull)} ocr(age=${ocrAge}ms chars=$ocrChars lines=$ocrLines)"
                    )
                }
                return
            }

            // ---------------- RUN ----------------
            val bg = rt.bgSmall ?: run { requestRecalibrate(calibNextStepAfterBg); return }
            if (bg.size != gridSmall.size) {
                requestRecalibrate(calibNextStepAfterBg)
                return
            }

            val bgDiff = motionScore(bg, gridSmall)
            val coverage =
                if (cfg.roiCoverageGuardEnabled) coverageScore(bg, gridSmall, cfg.roiDiffThr) else 1.0

            val presentThr =
                if (rt.step == CaptureStep.FRONT) cfg.bgDiffPresentThrFront else cfg.bgDiffPresentThrBack
            val absentThr =
                if (rt.step == CaptureStep.FRONT) cfg.bgDiffAbsentThrFront else cfg.bgDiffAbsentThrBack

            val presentByCoverage = (!cfg.roiCoverageGuardEnabled) || (coverage >= cfg.roiMinCoverageEnter)

            // histerese (enter/exit) para presença
            bgCardPresent = if (!bgCardPresent) {
                (bgDiff >= presentThr) && presentByCoverage
            } else {
                (bgDiff > absentThr) && presentByCoverage
            }

            // estabilidade exige ROI + FRAME TODO estáveis
            val motionOk = (motionRoi < cfg.motionStableThr) && (motionFull < cfg.motionStableThrFull)
            val sharpOk = m.sharp > cfg.sharpThr

            // ---------------- SWAP (LEGADO) ----------------
            // No fluxo novo, awaitingSwap nunca é setado. Mantido apenas como "fallback" se quiser reativar.
            if (rt.awaitingSwap.get()) {
                val sinceCapMs = if (rt.lastCaptureAt == 0L) 999_999L else (now - rt.lastCaptureAt)
                val settleOk = sinceCapMs >= cfg.swapSettleMs

                val swapThr = if (bgSwapThr > 0.0) bgSwapThr else absentThr
                val bgOk = bgDiff <= swapThr

                val motionOkSwap = motionFull < cfg.swapMotionThr
                val covOkSwap = (!cfg.roiCoverageGuardEnabled) || (coverage <= cfg.swapCoverageMax)

                val bgMatch = settleOk && bgOk && motionOkSwap && covOkSwap

                if (cfg.bgTrackEnabled && settleOk && motionOkSwap) {
                    val trackOk =
                        (bgDiff <= bgTrackThr) &&
                                ((!cfg.roiCoverageGuardEnabled) || (coverage <= cfg.bgTrackCoverageMax))
                    if (trackOk) updateBaselineEma(bg, gridSmall, cfg.bgTrackAlpha)
                }

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
                    lastFullSmall = null
                    val hint = if (rt.step == CaptureStep.FRONT) "Aponte para a FRENTE" else "Agora o VERSO"
                    listener.onHint(hint)
                }

                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "SWAP step=${rt.step} bgDiff=${fmt(bgDiff)} cov=${fmt(coverage)} settle=${sinceCapMs}/${cfg.swapSettleMs} absentFrames=${rt.absentFrames} thr=${fmt(swapThr)} mu=${fmt(bgNoiseMu)} sig=${fmt(bgNoiseSigma)}",
                        "sharp=${fmt(m.sharp)} motRoi=${fmt(motionRoi)} motFull=${fmt(motionFull)} bgOk=$bgOk covOk=$covOkSwap motionOk=$motionOkSwap"
                    )
                }
                return
            }

            // ---------------- gating por presentFrames ----------------
            rt.presentFrames = if (bgCardPresent) rt.presentFrames + 1 else 0
            if (rt.presentFrames < cfg.needPresentFrames) {
                rt.stableFrames = 0
                stableSinceMs = 0L
                if (now - rt.lastUiAt > cfg.uiDebugEveryMs) {
                    rt.lastUiAt = now
                    listener.onDebug(
                        "WAIT step=${rt.step} bgDiff=${fmt(bgDiff)} cov=${fmt(coverage)} present=$bgCardPresent presentFrames=${rt.presentFrames}/${cfg.needPresentFrames} thrP=${fmt(presentThr)}",
                        "sharp=${fmt(m.sharp)} motRoi=${fmt(motionRoi)} motFull=${fmt(motionFull)}"
                    )
                }
                return
            }

            // se está presente e estável mas nitidez ainda não tá lá, pede foco
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
                    "RUN step=${rt.step} bgDiff=${fmt(bgDiff)} cov=${fmt(coverage)} present=$bgCardPresent stable=${rt.stableFrames}/${cfg.needStableFrames} stableMs=${stableMs}/${cfg.needStableMs} focusMs=${sinceFocusMs}/${cfg.focusSettleMs} thrP=${fmt(presentThr)}",
                    "sharp=${fmt(m.sharp)} motRoi=${fmt(motionRoi)} motFull=${fmt(motionFull)}"
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

    private fun computeBgNoiseStats(baseline: ByteArray) {
        val n = calibFrames.size
        if (n <= 1) {
            bgNoiseMu = 0.0
            bgNoiseSigma = 0.0
            return
        }

        var sum = 0.0
        var sumSq = 0.0
        for (f in calibFrames) {
            if (f.size != baseline.size) continue
            val d = motionScore(baseline, f)
            sum += d
            sumSq += d * d
        }
        val mean = sum / n.toDouble()
        val var0 = (sumSq / n.toDouble()) - (mean * mean)
        val sigma = sqrt(max(0.0, var0))

        bgNoiseMu = mean
        bgNoiseSigma = max(0.05, sigma)
    }

    private fun updateBaselineEma(bg: ByteArray, cur: ByteArray, alpha: Double) {
        val a = alpha.coerceIn(0.0, 0.5)
        if (bg.size != cur.size) return
        for (i in bg.indices) {
            val b = bg[i].toInt() and 0xFF
            val c = cur[i].toInt() and 0xFF
            val v = (b * (1.0 - a) + c * a).toInt().coerceIn(0, 255)
            bg[i] = v.toByte()
        }
    }

    // ---------------- ROI / GRID helpers ----------------

    private data class RoiPx(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w: Int get() = max(1, x1 - x0)
        val h: Int get() = max(1, y1 - y0)
    }

    private fun roiToPx(roi: RoiNorm, w: Int, h: Int): RoiPx {
        val r = roi.clamped()
        val x0 = (r.left * w).toInt().coerceIn(0, w - 1)
        val x1 = (r.right * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (r.top * h).toInt().coerceIn(0, h - 1)
        val y1 = (r.bottom * h).toInt().coerceIn(y0 + 1, h)
        return RoiPx(x0, y0, x1, y1)
    }

    private fun acquireRoiBuffer(size: Int): ByteArray {
        val a = roiBufA?.takeIf { it.size == size } ?: ByteArray(size).also { roiBufA = it }
        val b = roiBufB?.takeIf { it.size == size } ?: ByteArray(size).also { roiBufB = it }
        val out = if (roiUseA) a else b
        roiUseA = !roiUseA
        return out
    }

    private fun acquireGridBuffer(size: Int): ByteArray {
        val a = gridBufA?.takeIf { it.size == size } ?: ByteArray(size).also { gridBufA = it }
        val b = gridBufB?.takeIf { it.size == size } ?: ByteArray(size).also { gridBufB = it }
        val out = if (gridUseA) a else b
        gridUseA = !gridUseA
        return out
    }

    private fun extractRoiSmall(src: ByteArray, srcW: Int, roi: RoiPx, dst: ByteArray): ByteArray {
        var di = 0
        var si = roi.y0 * srcW + roi.x0
        val rowLen = roi.w
        for (y in 0 until roi.h) {
            System.arraycopy(src, si, dst, di, rowLen)
            di += rowLen
            si += srcW
        }
        return dst
    }

    private fun gridifyToNxN(src: ByteArray, srcW: Int, srcH: Int, n: Int, dst: ByteArray): ByteArray {
        val nn = max(2, n)
        if (dst.size != nn * nn) return dst

        for (gy in 0 until nn) {
            val y0 = (gy * srcH) / nn
            val y1 = max(y0 + 1, ((gy + 1) * srcH) / nn)
            for (gx in 0 until nn) {
                val x0 = (gx * srcW) / nn
                val x1 = max(x0 + 1, ((gx + 1) * srcW) / nn)

                var sum = 0
                var cnt = 0
                var row = y0 * srcW + x0
                val w = x1 - x0
                for (y in y0 until y1) {
                    var i = row
                    for (x in 0 until w) {
                        sum += (src[i].toInt() and 0xFF)
                        cnt++
                        i++
                    }
                    row += srcW
                }
                val avg = if (cnt == 0) 0 else (sum / cnt)
                dst[gy * nn + gx] = avg.coerceIn(0, 255).toByte()
            }
        }
        return dst
    }

    private fun coverageScore(bg: ByteArray, cur: ByteArray, thr: Int): Double {
        if (bg.size != cur.size || bg.isEmpty()) return 0.0
        var changed = 0
        for (i in bg.indices) {
            val d = abs((cur[i].toInt() and 0xFF) - (bg[i].toInt() and 0xFF))
            if (d >= thr) changed++
        }
        return changed.toDouble() / bg.size.toDouble()
    }

    private fun deriveSmallDims(image: ImageProxy, step: Int, size: Int): Pair<Int, Int> {
        val rot = (image.imageInfo.rotationDegrees % 180) != 0
        val rw = if (rot) image.height else image.width
        val rh = if (rot) image.width else image.height

        val expW = (rw + step - 1) / step
        val expH = (rh + step - 1) / step
        if (expW * expH == size) return expW to expH

        val expW2 = (image.width + step - 1) / step
        val expH2 = (image.height + step - 1) / step
        if (expW2 * expH2 == size) return expW2 to expH2

        val targetAspect = if (rh == 0) 1.0 else (rw.toDouble() / rh.toDouble())
        var bestW = 1
        var bestH = size
        var bestScore = Double.POSITIVE_INFINITY

        val limit = max(1, sqrt(size.toDouble()).toInt())
        for (w in 1..limit) {
            if (size % w != 0) continue
            val h = size / w

            fun scorePair(ww: Int, hh: Int): Double {
                val aspect = ww.toDouble() / hh.toDouble()
                val aspectScore = abs(aspect - targetAspect)
                val dimScore = abs(ww - expW) * 0.001 + abs(hh - expH) * 0.001
                return aspectScore + dimScore
            }

            val s1 = scorePair(w, h)
            if (s1 < bestScore) {
                bestScore = s1
                bestW = w
                bestH = h
            }

            val s2 = scorePair(h, w)
            if (s2 < bestScore) {
                bestScore = s2
                bestW = h
                bestH = w
            }
        }

        return bestW to bestH
    }
}