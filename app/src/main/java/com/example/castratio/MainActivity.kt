package com.example.castratio

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

// Global state for Aspect Ratio
val aspectRatioState = MutableStateFlow(16f / 9f)

class MainActivity : ComponentActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Permission granted, start the foreground service to capture screen
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startForegroundService(serviceIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(
                    onOpenCastSettings = { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) },
                    onStartMirroring = { startScreenCapture() },
                    onStopMirroring = { stopService(Intent(this, ScreenCaptureService::class.java)) }
                )
            }
        }
    }

    private fun startScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}

@Composable
fun MainScreen(
    onOpenCastSettings: () -> Unit,
    onStartMirroring: () -> Unit,
    onStopMirroring: () -> Unit
) {
    var currentRatio by remember { mutableStateOf(16f / 9f) }

    LaunchedEffect(currentRatio) { aspectRatioState.value = currentRatio }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Anycast Mirror Controller", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onOpenCastSettings, modifier = Modifier.fillMaxWidth()) {
            Text("1. Connect to Anycast")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("2. Set TV Aspect Ratio:", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { currentRatio = 16f / 9f }) { Text("16:9") }
            Button(onClick = { currentRatio = 4f / 3f }) { Text("4:3") }
            Button(onClick = { currentRatio = 21f / 9f }) { Text("21:9") }
        }

        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = onStartMirroring, 
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("3. Start Screen Mirroring")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onStopMirroring,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop Mirroring")
        }
    }
}
