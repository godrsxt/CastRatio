package com.example.castratio

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Simple object to pass the ratio data from the Phone UI to the TV UI safely
object RatioState {
    var currentRatio: Float = 16f / 9f
    var listener: ((Float) -> Unit)? = null

    fun update(ratio: Float) {
        currentRatio = ratio
        listener?.invoke(ratio)
    }
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
        } else {
            val display = displays[0]
            if (currentPresentation?.display?.displayId != display.displayId) {
                currentPresentation?.dismiss()
                currentPresentation = CastPresentation(this, display)
                currentPresentation?.show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPresentation?.dismiss()
        currentPresentation = null
    }
}

@Composable
fun MainScreen(onOpenCastSettings: () -> Unit) {
    var currentRatio by remember { mutableStateOf(RatioState.currentRatio) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Anycast Ratio Controller", style = MaterialTheme.typography.headlineSmall)
        
        Spacer(modifier = Modifier.height(48.dp))

        Button(onClick = onOpenCastSettings, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Text("1. Connect to Anycast")
        }

        Spacer(modifier = Modifier.height(48.dp))
        Text("2. Set TV Aspect Ratio:", style = MaterialTheme.typography.bodyLarge)
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
    }
}

// -------------------------------------------------------------------------
// TV DISPLAY LOGIC (Using crash-proof standard Android Views instead of Compose)
// -------------------------------------------------------------------------
class CastPresentation(context: Context, display: Display) : Presentation(context, display) {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Root Layout (Black borders)
        val rootLayout = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        
        // 2. The Resizing Box (Dark Gray Background)
        val aspectContainer = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.DKGRAY)
        }
        
        // 3. The Text inside the box
        val textView = TextView(context).apply {
            text = "TV Output\nAspect Ratio applied successfully."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        
        aspectContainer.addView(textView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        
        rootLayout.addView(aspectContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        
        setContentView(rootLayout)
        
        // 4. Listen for button clicks from the phone to resize the box
        RatioState.listener = { targetRatio ->
            val displayMetrics = android.util.DisplayMetrics()
            display.getMetrics(displayMetrics)
            
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
            
            val params = aspectContainer.layoutParams as FrameLayout.LayoutParams
            
            // Calculate exact pixel dimensions for the chosen ratio
            if (screenRatio > targetRatio) {
                // TV is wider than the ratio (Add black bars on left/right)
                params.height = screenHeight
                params.width = (screenHeight * targetRatio).toInt()
            } else {
                // TV is narrower than the ratio (Add black bars on top/bottom)
                params.width = screenWidth
                params.height = (screenWidth / targetRatio).toInt()
            }
            
            // Update the TV UI instantly
            aspectContainer.post {
                aspectContainer.layoutParams = params
            }
        }
        
        // Trigger setup immediately on load
        RatioState.listener?.invoke(RatioState.currentRatio)
    }
    
    override fun onStop() {
        super.onStop()
        // Prevent memory leaks when Anycast disconnects
        RatioState.listener = null
    }
}
