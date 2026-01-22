package com.example.mtgdatasetcollector.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.mtgdatasetcollector.camera.*
import com.example.mtgdatasetcollector.model.CaptureStep
import com.example.mtgdatasetcollector.util.AppConfig
import com.example.mtgdatasetcollector.util.StoragePaths
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoShootCaptureScreen(
    onCapturedBoth: (frontPath: String, backPath: String) -> Unit,
    onExit: () -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExec = remember { ContextCompat.getMainExecutor(ctx) }

    val previewView = remember {
        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val rt = remember { CaptureRuntime() }

    var stepUi by remember { mutableStateOf(CaptureStep.FRONT) }
    var hintUi by remember { mutableStateOf("Aponte para a FRENTE") }
    var dbg1 by remember { mutableStateOf("") }
    var dbg2 by remember { mutableStateOf("") }

    var frontFile by remember { mutableStateOf<File?>(null) }
    var backFile by remember { mutableStateOf<File?>(null) }

    val boundRef = remember { mutableStateOf<BoundCamera?>(null) }
    val cameraControlRef = remember { AtomicReference<CameraControl?>(null) }

    fun postUi(block: () -> Unit) = mainExec.execute(block)

    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }
    fun beepFront() = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    fun beepBack() = tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    fun beepError() = tone.startTone(ToneGenerator.TONE_PROP_NACK, 160)

    fun requestFocusNow() {
        val cc = cameraControlRef.get() ?: return
        previewView.post {
            try {
                if (previewView.width <= 0 || previewView.height <= 0) return@post
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)

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

    fun resetAll(toast: Boolean = false) {
        try { frontFile?.delete() } catch (_: Throwable) {}
        try { backFile?.delete() } catch (_: Throwable) {}
        frontFile = null
        backFile = null

        rt.step = CaptureStep.FRONT
        rt.stableFrames = 0
        rt.presentFrames = 0
        rt.absentFrames = 0
        rt.lastSmall = null
        rt.captureInProgress.set(false)
        rt.analyzing.set(false)
        rt.awaitingSwap.set(false)

        postUi {
            stepUi = CaptureStep.FRONT
            hintUi = "Aponte para a FRENTE"
        }

        if (toast) Toast.makeText(ctx, "Carta cancelada.", Toast.LENGTH_SHORT).show()
    }

    val engine = remember {
        CaptureEngine(
            rt = rt,
            listener = object : CaptureEngine.Listener {

                override fun onDebug(line1: String, line2: String) {
                    if (!AppConfig.DBG) return
                    postUi { dbg1 = line1; dbg2 = line2 }
                }

                override fun onHint(text: String) {
                    postUi { hintUi = text }
                }

                override fun onRequestFocus() {
                    requestFocusNow()
                }

                override fun onTriggerCapture(step: CaptureStep) {
                    val bound = boundRef.value ?: return
                    val imageCapture = bound.imageCapture

                    val out = when (step) {
                        CaptureStep.FRONT -> StoragePaths.newStagingImageFile(ctx, "front")
                        CaptureStep.BACK -> StoragePaths.newStagingImageFile(ctx, "back")
                    }

                    takePhoto(
                        imageCapture = imageCapture,
                        outFile = out,
                        executor = mainExec,
                        onOk = {
                            rt.captureInProgress.set(false)

                            if (step == CaptureStep.FRONT) {
                                try { frontFile?.delete() } catch (_: Throwable) {}
                                frontFile = out
                                beepFront()

                                rt.step = CaptureStep.BACK
                                rt.awaitingSwap.set(true)
                                rt.absentFrames = 0
                                rt.presentFrames = 0
                                rt.stableFrames = 0
                                rt.lastSmall = null

                                postUi {
                                    stepUi = CaptureStep.BACK
                                    hintUi = "Tire a carta do papel (sumir) para liberar o VERSO"
                                }
                            } else {
                                try { backFile?.delete() } catch (_: Throwable) {}
                                backFile = out

                                val f = frontFile
                                val b = backFile
                                if (f == null || b == null) {
                                    beepError()
                                    resetAll()
                                    Toast.makeText(ctx, "Falhou: falta frente ou verso.", Toast.LENGTH_SHORT).show()
                                    return@takePhoto
                                }

                                beepBack()

                                rt.step = CaptureStep.FRONT
                                rt.awaitingSwap.set(true)
                                rt.absentFrames = 0
                                rt.presentFrames = 0
                                rt.stableFrames = 0
                                rt.lastSmall = null

                                postUi {
                                    stepUi = CaptureStep.FRONT
                                    hintUi = "Remova a carta e coloque a próxima (precisa sumir)"
                                }

                                onCapturedBoth(f.absolutePath, b.absolutePath)
                            }
                        },
                        onFail = { err ->
                            rt.captureInProgress.set(false)
                            beepError()
                            Toast.makeText(ctx, "Erro ao capturar: ${err.message}", Toast.LENGTH_SHORT).show()

                            rt.awaitingSwap.set(true)
                            rt.absentFrames = 0
                            rt.presentFrames = 0
                            rt.stableFrames = 0
                            rt.lastSmall = null

                            postUi { hintUi = "Erro. Tire a carta do papel e tente de novo." }
                        }
                    )
                }

                override fun onError(t: Throwable) {
                    Log.e(AppConfig.DBG_TAG, "Engine crash", t)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Captura automática") },
                actions = {
                    TextButton(onClick = { resetAll(toast = true) }) { Text("Cancelar carta") }
                    TextButton(onClick = { resetAll(); onExit() }) { Text("Sair") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

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
                Text(
                    "Passo: ${if (stepUi == CaptureStep.FRONT) "FRENTE" else "VERSO"}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text("Dica: papel claro e boa luz ajudam. O foco é acionado automaticamente.")
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

            rt.step = CaptureStep.FRONT
            rt.awaitingSwap.set(false)
            postUi {
                stepUi = CaptureStep.FRONT
                hintUi = "Aponte para a FRENTE"
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
            runCatching { tone.release() }
            // NÃO apagar aqui. As imagens precisam sobreviver até Label/Room/Worker.
            runCatching { engine.close() }
        }
    }
}
