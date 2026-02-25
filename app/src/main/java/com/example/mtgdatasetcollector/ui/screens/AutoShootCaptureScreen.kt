package com.example.mtgdatasetcollector.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.mtgdatasetcollector.camera.*
import com.example.mtgdatasetcollector.model.CaptureStep
import com.example.mtgdatasetcollector.util.AppConfig
import com.example.mtgdatasetcollector.util.IdGenerator
import com.example.mtgdatasetcollector.util.StoragePaths
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

private enum class UiMode { CAPTURING, LABEL_FRONT, LABEL_BACK, FINAL }

private fun gradeLabel(v: String): String = if (v == "D") "DAMAGED" else v

private fun worstGrade(a: String, b: String): String {
    val order = mapOf("NM" to 0, "SP" to 1, "MP" to 2, "HP" to 3, "D" to 4)
    val oa = order[a] ?: 99
    val ob = order[b] ?: 99
    return if (oa >= ob) a else b
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoShootCaptureScreen(
    onCompleted: (
        frontPath: String,
        backPath: String,
        frontGrade: String,
        backGrade: String,
        finalGrade: String
    ) -> Unit,
    onExit: () -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExec = remember { ContextCompat.getMainExecutor(ctx) }

    val previewView = remember {
        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val validateExecutor = remember { Executors.newSingleThreadExecutor() }

    val rt = remember { CaptureRuntime() }

    var stepUi by remember { mutableStateOf(CaptureStep.FRONT) }
    var hintUi by remember { mutableStateOf("Calibrando fundo... mantenha SEM carta por 1 segundo") }
    var dbg1 by remember { mutableStateOf("") }
    var dbg2 by remember { mutableStateOf("") }

    var frontFile by remember { mutableStateOf<File?>(null) }
    var backFile by remember { mutableStateOf<File?>(null) }

    var frontGrade by remember { mutableStateOf<String?>(null) }
    var backGrade by remember { mutableStateOf<String?>(null) }
    var finalGrade by remember { mutableStateOf<String?>(null) }

    // >>> baseId único por carta (FRONT/BACK compartilham)
    var sessionBaseId by remember { mutableStateOf<String?>(null) }

    var mode by remember { mutableStateOf(UiMode.CAPTURING) }
    val modeRef = remember { AtomicReference(UiMode.CAPTURING) }

    fun setMode(m: UiMode) {
        modeRef.set(m)
        mode = m
    }

    val boundRef = remember { mutableStateOf<BoundCamera?>(null) }
    val cameraControlRef = remember { AtomicReference<CameraControl?>(null) }

    fun postUi(block: () -> Unit) = mainExec.execute(block)

    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }
    fun beepFront() = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    fun beepBack() = tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    fun beepError() = tone.startTone(ToneGenerator.TONE_PROP_NACK, 120)

    val roi = remember {
        CaptureEngine.RoiNorm(
            left = 0.12f,
            top = 0.18f,
            right = 0.88f,
            bottom = 0.82f
        ).clamped()
    }

    fun requestFocusNow() {
        val cc = cameraControlRef.get() ?: return
        previewView.post {
            try {
                if (previewView.width <= 0 || previewView.height <= 0) return@post

                val cx = previewView.width * (roi.left + (roi.right - roi.left) * 0.5f)
                val cy = previewView.height * (roi.top + (roi.bottom - roi.top) * 0.5f)

                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(cx, cy)

                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                )
                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                    .build()

                cc.startFocusAndMetering(action)
            } catch (t: Throwable) {
                if (AppConfig.DBG) Log.w(AppConfig.DBG_TAG, "Focus request failed: ${t.message}")
            }
        }
    }

    fun resetSessionFiles() {
        try { frontFile?.delete() } catch (_: Throwable) {}
        try { backFile?.delete() } catch (_: Throwable) {}
        frontFile = null
        backFile = null
    }

    fun resetAll(toast: Boolean = false) {
        resetSessionFiles()

        frontGrade = null
        backGrade = null
        finalGrade = null

        // >>> reset do baseId da carta atual
        sessionBaseId = null

        rt.step = CaptureStep.FRONT
        rt.stableFrames = 0
        rt.absentFrames = 0
        rt.presentFrames = 0
        rt.lastSmall = null
        rt.captureInProgress.set(false)
        rt.analyzing.set(false)
        rt.awaitingSwap.set(false)

        rt.clearBg()
        rt.calibratingBg.set(true)

        postUi {
            setMode(UiMode.CAPTURING)
            stepUi = CaptureStep.FRONT
            hintUi = "Calibrando fundo... mantenha SEM carta por 1 segundo"
        }

        if (toast) Toast.makeText(ctx, "Carta cancelada.", Toast.LENGTH_SHORT).show()
    }

    fun lockForLabeling() {
        rt.captureInProgress.set(true)
    }

    fun unlockForCapturing() {
        rt.captureInProgress.set(false)
    }

    val engine = remember {
        CaptureEngine(
            rt = rt,
            cfg = CaptureEngine.Config(
                roi = roi,
                needStableMs = 2000,
                needStableFrames = 7,
                needPresentFrames = 7,
                stepDownsample = 12,
                roiGrid = 16,
                motionStableThr = 6.5,
                motionStableThrFull = 5.8,
                sharpThr = 11.0,
                roiCoverageGuardEnabled = true
            ),
            listener = object : CaptureEngine.Listener {

                override fun onDebug(line1: String, line2: String) {
                    if (!AppConfig.DBG) return
                    postUi { dbg1 = line1; dbg2 = line2 }
                }

                override fun onHint(text: String) {
                    val m = modeRef.get()
                    if (m == UiMode.LABEL_FRONT || m == UiMode.LABEL_BACK || m == UiMode.FINAL) return
                    postUi { hintUi = text }
                }

                override fun onRequestFocus() {
                    requestFocusNow()
                }

                override fun onTriggerCapture(step: CaptureStep) {
                    val bound = boundRef.value ?: return
                    val imageCapture = bound.imageCapture

                    // >>> garante um baseId por carta e usa nos dois lados
                    val baseId = sessionBaseId ?: IdGenerator.nextBaseId(ctx).also { sessionBaseId = it }

                    val out = when (step) {
                        CaptureStep.FRONT -> StoragePaths.newStagingImageFile(ctx, baseId, "front")
                        CaptureStep.BACK -> StoragePaths.newStagingImageFile(ctx, baseId, "back")
                    }

                    rt.analyzing.set(true)

                    takePhoto(
                        imageCapture = imageCapture,
                        outFile = out,
                        executor = mainExec,
                        onOk = {
                            validateExecutor.execute {
                                val vr = PhotoValidator.validateJpeg(out, step)
                                postUi {
                                    rt.analyzing.set(false)
                                    rt.captureInProgress.set(false)

                                    if (!vr.ok) {
                                        try { out.delete() } catch (_: Throwable) {}

                                        rt.stableFrames = 0
                                        rt.presentFrames = 0
                                        rt.lastSmall = null

                                        beepError()
                                        hintUi = when (vr.reason) {
                                            "BLUR" -> "Foto desfocada. Aguarde estabilizar (e não mexa na carta)."
                                            "OCCLUSION_BACK" -> "Algo cobrindo o VERSO. Segure só pelas laterais."
                                            "ALIGN_SMALL", "ALIGN_HUGE", "ALIGN_NO_EDGES" -> "Carta fora do enquadramento. Centralize e tente de novo."
                                            else -> "Foto inválida (${vr.reason}). Ajuste e tente de novo."
                                        }
                                        return@postUi
                                    }

                                    if (step == CaptureStep.FRONT) {
                                        try { frontFile?.delete() } catch (_: Throwable) {}
                                        frontFile = out
                                        beepFront()

                                        rt.step = CaptureStep.BACK
                                        rt.awaitingSwap.set(false)
                                        rt.absentFrames = 0
                                        rt.presentFrames = 0
                                        rt.stableFrames = 0
                                        rt.lastSmall = null

                                        stepUi = CaptureStep.BACK

                                        lockForLabeling()
                                        setMode(UiMode.LABEL_FRONT)
                                        hintUi = "GRADE FRONT: selecione o estado da FRENTE (logo após a captura)."
                                    } else {
                                        try { backFile?.delete() } catch (_: Throwable) {}
                                        backFile = out
                                        beepBack()

                                        lockForLabeling()
                                        setMode(UiMode.LABEL_BACK)
                                        hintUi = "GRADE BACK: selecione o estado do VERSO (logo após a captura)."
                                    }
                                }
                            }
                        },
                        onFail = { err ->
                            rt.analyzing.set(false)
                            rt.captureInProgress.set(false)
                            beepError()
                            Toast.makeText(ctx, "Erro ao capturar: ${err.message}", Toast.LENGTH_SHORT).show()

                            rt.awaitingSwap.set(false)
                            rt.stableFrames = 0
                            rt.presentFrames = 0
                            rt.absentFrames = 0
                            rt.lastSmall = null

                            postUi { hintUi = "Erro. Recalibre o fundo e tente de novo." }
                        }
                    )
                }

                override fun onError(t: Throwable) {
                    Log.e(AppConfig.DBG_TAG, "Engine crash", t)
                }
            }
        )
    }

    fun finalizeAndEmit() {
        val f = frontFile
        val b = backFile
        val fg = frontGrade
        val bg = backGrade
        val fin = finalGrade

        if (f == null || b == null || fg == null || bg == null || fin == null) {
            beepError()
            Toast.makeText(ctx, "Falhou: faltou frente/verso ou grades.", Toast.LENGTH_SHORT).show()
            resetAll()
            return
        }

        onCompleted(f.absolutePath, b.absolutePath, fg, bg, fin)
    }

    fun onPickFrontGrade(value: String) {
        frontGrade = value

        unlockForCapturing()
        rt.awaitingSwap.set(false)

        // >>> após GRADE FRONT, recalibra o fundo para capturar o VERSO
        engine.requestRecalibrate(nextStepAfterCalib = CaptureStep.BACK)

        setMode(UiMode.CAPTURING)
        stepUi = CaptureStep.BACK
        hintUi = "Calibrando fundo para o VERSO... mantenha SEM carta por 1 segundo"
    }

    fun onPickBackGrade(value: String) {
        backGrade = value

        val fg = frontGrade
        if (fg == null) {
            beepError()
            resetAll(toast = true)
            return
        }

        val fin = worstGrade(fg, value)
        finalGrade = fin

        lockForLabeling()
        setMode(UiMode.FINAL)
        hintUi = "FINAL = pior(FRONT, BACK). Toque no botão FINAL para salvar."
    }

    // helper: recalibrar mantém o passo atual (sem resetar o processo)
    fun desiredStepForRecalib(): CaptureStep {
        // se está no verso mas ainda não tem frente (estado esquisito), força FRONT
        if (stepUi == CaptureStep.BACK && frontFile == null) return CaptureStep.FRONT
        return stepUi
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Captura automática") },
                actions = {
                    TextButton(
                        onClick = {
                            // >>> FIX: Recalibrar = só fundo (mantém FRONT/BACK), não mexe em files/grades/baseId.
                            if (mode == UiMode.LABEL_FRONT || mode == UiMode.LABEL_BACK || mode == UiMode.FINAL) {
                                Toast.makeText(ctx, "Finalize a seleção antes de recalibrar.", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }

                            val targetStep = desiredStepForRecalib()

                            val ok = engine.requestRecalibrate(nextStepAfterCalib = targetStep)
                            if (!ok) {
                                Toast.makeText(ctx, "Recalibração ignorada (ocupado).", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }

                            postUi {
                                setMode(UiMode.CAPTURING)
                                stepUi = targetStep
                                hintUi = if (targetStep == CaptureStep.FRONT) {
                                    "Calibrando fundo... mantenha SEM carta por 1 segundo"
                                } else {
                                    "Calibrando fundo para o VERSO... mantenha SEM carta por 1 segundo"
                                }
                            }
                        }
                    ) { Text("Recalibrar") }

                    TextButton(
                        onClick = { resetAll(toast = true) }
                    ) { Text("Cancelar carta") }

                    TextButton(
                        onClick = {
                            resetAll()
                            onExit()
                        }
                    ) { Text("Sair") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

                val leftDp = maxWidth * roi.left
                val topDp = maxHeight * roi.top
                val wDp = maxWidth * (roi.right - roi.left)
                val hDp = maxHeight * (roi.bottom - roi.top)

                Box(
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .width(wDp)
                        .height(hDp)
                        .border(
                            width = 3.dp,
                            color = Color(0xFF00FF00),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .alpha(0.85f)
                )

                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(hintUi, style = MaterialTheme.typography.titleMedium)
                        if (AppConfig.DBG) {
                            Spacer(Modifier.height(6.dp))
                            Text(dbg1, style = MaterialTheme.typography.bodySmall)
                            Text(dbg2, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Column(Modifier.padding(16.dp)) {
                when (mode) {
                    UiMode.CAPTURING -> {
                        Text(
                            "Passo: ${if (stepUi == CaptureStep.FRONT) "FRENTE" else "VERSO"}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Dica: calibre o fundo sem carta. Depois, só troque frente/verso e rotule.")
                    }

                    UiMode.LABEL_FRONT -> {
                        Text("GRADE FRONT", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        GradeButtons(onPick = { v -> onPickFrontGrade(v) })
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Escolha o estado da FRENTE. (Depois você captura o VERSO)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    UiMode.LABEL_BACK -> {
                        Text("GRADE BACK", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        GradeButtons(onPick = { v -> onPickBackGrade(v) })
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Escolha o estado do VERSO. (Depois você salva o FINAL)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    UiMode.FINAL -> {
                        val fg = frontGrade ?: "-"
                        val bg = backGrade ?: "-"
                        val fin = finalGrade ?: "-"

                        Text("RESULTADO FINAL", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))

                        Text(
                            "FRONT: ${gradeLabel(fg)}   |   BACK: ${gradeLabel(bg)}",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "FINAL: ${gradeLabel(fin)}",
                            style = MaterialTheme.typography.displaySmall
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            "FINAL = pior(FRONT, BACK)",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = { finalizeAndEmit() },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            contentPadding = PaddingValues(14.dp)
                        ) {
                            Text(
                                "ESTADO FINAL: ${gradeLabel(fin)}",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val bound = bindCameraWithEngine(
                ctx = ctx,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                analyzerExecutor = cameraExecutor,
                engine = engine,
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            )
            boundRef.value = bound
            cameraControlRef.set(bound.camera.cameraControl)

            requestFocusNow()

            engine.requestRecalibrate(nextStepAfterCalib = CaptureStep.FRONT)
            postUi {
                setMode(UiMode.CAPTURING)
                stepUi = CaptureStep.FRONT
                hintUi = "Calibrando fundo... mantenha SEM carta por 1 segundo"
            }

            if (AppConfig.DBG) Log.i(AppConfig.DBG_TAG, "CAMERA_BOUND_OK")
        } catch (e: Exception) {
            Log.e(AppConfig.DBG_TAG, "Bind camera failed", e)
            Toast.makeText(ctx, "Falha ao iniciar câmera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { boundRef.value?.provider?.unbindAll() }
            runCatching { cameraExecutor.shutdown() }
            runCatching { validateExecutor.shutdown() }
            runCatching { tone.release() }
            runCatching { engine.close() }
        }
    }
}

@Composable
private fun GradeButtons(
    onPick: (String) -> Unit
) {
    val items = listOf(
        "NM" to "NM",
        "SP" to "SP",
        "MP" to "MP",
        "HP" to "HP",
        "D" to "DAMAGED"
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items.take(3).forEach { (v, label) ->
                Button(
                    onClick = { onPick(v) },
                    modifier = Modifier.weight(1f).height(54.dp),
                    contentPadding = PaddingValues(12.dp)
                ) { Text(label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items.drop(3).forEach { (v, label) ->
                Button(
                    onClick = { onPick(v) },
                    modifier = Modifier.weight(1f).height(54.dp),
                    contentPadding = PaddingValues(12.dp)
                ) { Text(label) }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}