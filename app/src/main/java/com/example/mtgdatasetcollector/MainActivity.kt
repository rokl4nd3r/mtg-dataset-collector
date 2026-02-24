package com.example.mtgdatasetcollector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mtgdatasetcollector.data.queue.AppDatabase
import com.example.mtgdatasetcollector.data.queue.UploadQueueRepository
import com.example.mtgdatasetcollector.ui.screens.AutoShootCaptureScreen
import com.example.mtgdatasetcollector.ui.theme.MTGDatasetCollectorTheme
import com.example.mtgdatasetcollector.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen {
    data object Capture : Screen()
    data object Label : Screen()
    data object NextCard : Screen() // tela de "Identificação OK"
}

data class CaptureSession(
    val frontPath: String? = null,
    val backPath: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MTGDatasetCollectorTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CollectorApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectorApp() {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { WorkScheduler.ensurePeriodic(appCtx) }

    val repo = remember {
        UploadQueueRepository(AppDatabase.get(appCtx).uploadJobDao())
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }

    var screen: Screen by remember { mutableStateOf<Screen>(Screen.Capture) }
    var session by remember { mutableStateOf(CaptureSession()) }

    if (!hasCameraPermission) {
        PermissionGate(
            modifier = Modifier.fillMaxSize(),
            onRequest = { requestPermission.launch(Manifest.permission.CAMERA) }
        )
        return
    }

    when (screen) {
        Screen.Capture -> AutoShootCaptureScreen(
            onCapturedBoth = { front, back ->
                session = CaptureSession(frontPath = front, backPath = back)
                screen = Screen.Label
            },
            onExit = {
                session = CaptureSession()
                screen = Screen.Capture
            }
        )

        Screen.Label -> Scaffold(
            topBar = { TopAppBar(title = { Text("Rotular") }) }
        ) { padding ->
            LabelScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                session = session,
                onCancel = {
                    session = CaptureSession()
                    screen = Screen.Capture
                },
                onLabeled = { grade ->
                    val front = session.frontPath
                    val back = session.backPath

                    // Vai pra tela de "OK" imediatamente (evita cair na câmera e capturar o verso que ficou lá).
                    // O enqueue roda em background.
                    screen = Screen.NextCard
                    session = CaptureSession()

                    if (front != null && back != null) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                repo.enqueue(grade, front, back)
                                WorkScheduler.kick(appCtx)
                            }
                        }
                    }
                }
            )
        }

        Screen.NextCard -> Scaffold(
            topBar = { TopAppBar(title = { Text("Próxima carta") }) }
        ) { padding ->
            NextCardScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                autoMs = 1000L,
                onAutoDone = {
                    // Volta pra câmera automaticamente
                    screen = Screen.Capture
                }
            )
        }
    }
}

@Composable
private fun PermissionGate(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Preciso da permissão de câmera para capturar as cartas.")
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequest) { Text("Permitir câmera") }
    }
}

@Composable
private fun NextCardScreen(
    modifier: Modifier = Modifier,
    autoMs: Long = 1000L,
    onAutoDone: () -> Unit
) {
    // Auto-fecha e rearma
    LaunchedEffect(Unit) {
        delay(autoMs)
        onAutoDone()
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Identificação OK ✅",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Remova a carta do papel e coloque a próxima.\nRearmando automaticamente…",
            style = MaterialTheme.typography.bodyLarge
        )
        // Sem botão OK
    }
}

@Composable
private fun LabelScreen(
    modifier: Modifier = Modifier,
    session: CaptureSession,
    onCancel: () -> Unit,
    onLabeled: (grade: String) -> Unit
) {
    var locked by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Resumo da captura", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("front: ${session.frontPath ?: "-"}")
                Text("back:  ${session.backPath ?: "-"}")
            }
        }

        Text("Selecione o estado:", style = MaterialTheme.typography.bodyLarge)

        // UI label -> valor real (mantém "D" internamente)
        val grades = listOf(
            "NM" to "NM",
            "SP" to "SP",
            "MP" to "MP",
            "HP" to "HP",
            "DAMAGED" to "D"
        )

        grades.forEach { (label, value) ->
            Button(
                onClick = {
                    if (locked) return@Button
                    locked = true
                    onLabeled(value)
                },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) { Text(label) }

            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = { if (!locked) onCancel() },
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp)
        ) { Text("Cancelar") }
    }
}