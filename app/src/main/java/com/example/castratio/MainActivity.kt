package com.example.castratio

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Passes the chosen output ratio from the Phone UI to the TV UI.
object RatioState {
    var currentRatio: Float = 16f / 9f
    var listener: ((Float) -> Unit)? = null

    fun update(ratio: Float) {
        currentRatio = ratio
        listener?.invoke(ratio)
    }
}

// Lets the (out-of-process-timing) capture service reach the live Presentation
// that MainActivity is currently showing on the external display.
object PresentationBridge {
    var current: CastPresentation? = null
}

class MainActivity : ComponentActivity() {
    private var currentPresentation: CastPresentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(
                    onOpenCastSettings = {
                        startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                    }
                )
            }
        }
        setupSecondaryDisplayScanner()
    }

    private fun setupSecondaryDisplayScanner() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { updateTVDisplay() }
            override fun onDisplayRemoved(displayId: Int) { updateTVDisplay() }
            override fun onDisplayChanged(displayId: Int) { updateTVDisplay() }
        }
        displayManager.registerDisplayListener(displayListener, null)
        updateTVDisplay()
    }

    private fun updateTVDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (displays.isEmpty()) {
            currentPresentation?.dismiss()
            currentPresentation = null
            PresentationBridge.current = null
        } else {
            val display = displays[0]
            if (currentPresentation?.display?.displayId != display.displayId) {
                currentPresentation?.dismiss()
                currentPresentation = CastPresentation(this, display)
                currentPresentation?.show()
                PresentationBridge.current = currentPresentation
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPresentation?.dismiss()
        currentPresentation = null
        PresentationBridge.current = null
    }
}

@Composable
fun MainScreen(onOpenCastSettings: () -> Unit) {
    var currentRatio by remember { mutableStateOf(RatioState.currentRatio) }
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            if (PresentationBridge.current == null) {
                Toast.makeText(
                    context,
                    "Connect to the Anycast dongle first (step 1), then start mirroring.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } else {
            Toast.makeText(context, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Anycast Mirror", style = MaterialTheme.typography.headlineSmall)

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onOpenCastSettings, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Text("1. Connect to Anycast")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { captureLauncher.launch(projectionManager.createScreenCaptureIntent()) },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Text("2. Start Mirroring")
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("3. Set TV Aspect Ratio:", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                currentRatio = 16f / 9f
                RatioState.update(currentRatio)
            }) { Text("16:9") }

            Button(onClick = {
                currentRatio = 4f / 3f
                RatioState.update(currentRatio)
            }) { Text("4:3") }

            Button(onClick = {
                currentRatio = 21f / 9f
                RatioState.update(currentRatio)
            }) { Text("21:9") }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Stop Mirroring")
        }
    }
}

// -------------------------------------------------------------------------
// TV DISPLAY LOGIC (standard Android Views, same pattern as the working
// CastRatio proof of concept -- just with a live SurfaceView in place of
// the placeholder text box).
// -------------------------------------------------------------------------
class CastPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var aspectContainer: FrameLayout
        private set
    lateinit var surfaceView: SurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Root layout (black borders = the letterbox/pillarbox bars)
        val rootLayout = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // 2. The resizing box -- this is exactly the box from the proof of
        // concept that you confirmed resizes correctly on your hardware.
        aspectContainer = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.DKGRAY)
        }

        // 3. Live mirrored content goes here now, filling the box exactly.
        // Because it's MATCH_PARENT inside aspectContainer, it automatically
        // follows every resize the ratio buttons trigger -- no need to
        // recreate the capture when the ratio changes.
        surfaceView = SurfaceView(context)
        aspectContainer.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rootLayout.addView(
            aspectContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        setContentView(rootLayout)

        // 4. Same resize logic as the working proof of concept.
        RatioState.listener = { targetRatio ->
            val displayMetrics = android.util.DisplayMetrics()
            display.getMetrics(displayMetrics)

            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()

            val params = aspectContainer.layoutParams as FrameLayout.LayoutParams

            if (screenRatio > targetRatio) {
                params.height = screenHeight
                params.width = (screenHeight * targetRatio).toInt()
            } else {
                params.width = screenWidth
                params.height = (screenWidth / targetRatio).toInt()
            }

            aspectContainer.post {
                aspectContainer.layoutParams = params
            }
        }

        RatioState.listener?.invoke(RatioState.currentRatio)
    }

    /** Registers a callback for when this Presentation's SurfaceView has a real Surface. */
    fun onSurfaceAvailable(callback: (SurfaceHolder) -> Unit) {
        val holder = surfaceView.holder
        if (holder.surface != null && holder.surface.isValid) {
            callback(holder)
            return
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = callback(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    override fun onStop() {
        super.onStop()
        RatioState.listener = null
    }
}
