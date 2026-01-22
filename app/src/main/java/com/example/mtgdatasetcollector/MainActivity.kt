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
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch

sealed class Screen {
    data object Capture : Screen()
    data object Label : Screen()
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
                    if (front != null && back != null) {
                        scope.launch(Dispatchers.IO) {
                            repo.enqueue(grade, front, back)
                            WorkScheduler.kick(appCtx)
                        }
                    }
                    session = CaptureSession()
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
private fun LabelScreen(
    modifier: Modifier = Modifier,
    session: CaptureSession,
    onCancel: () -> Unit,
    onLabeled: (grade: String) -> Unit
) {
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

        val grades = listOf("NM", "SP", "MP", "HP", "D")
        grades.forEach { g ->
            Button(
                onClick = { onLabeled(g) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) { Text(g) }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp)
        ) { Text("Cancelar") }
    }
}
