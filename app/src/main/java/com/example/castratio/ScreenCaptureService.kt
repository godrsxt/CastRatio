package com.example.castratio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentPresentation: MirrorPresentation? = null
    private var tvSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundNotification()

            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            
            // Fix for Android 13+ Intent Parsing
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                setupSecondaryDisplayScanner()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Service Error: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf() // Stop service if it fails to initialize
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "MirrorChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screen Mirroring", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Casting Screen")
            .setContentText("Your screen is being mirrored to the TV.")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
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
            stopVirtualDisplay()
        } else {
            val display = displays[0]
            if (currentPresentation?.display?.displayId != display.displayId) {
                currentPresentation?.dismiss()
                val displayContext = createDisplayContext(display)
                currentPresentation = MirrorPresentation(displayContext, display) { surface, w, h ->
                    tvSurface = surface
                    surfaceWidth = w
                    surfaceHeight = h
                    startVirtualDisplay()
                }.apply { show() }
            }
        }
    }

    private fun startVirtualDisplay() {
        stopVirtualDisplay()
        if (tvSurface != null && surfaceWidth > 0 && surfaceHeight > 0 && mediaProjection != null) {
            try {
                val metrics = resources.displayMetrics
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "MirrorDisplay",
                    surfaceWidth, surfaceHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    tvSurface, null, null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVirtualDisplay()
        mediaProjection?.stop()
        currentPresentation?.dismiss()
    }
}

class MirrorPresentation(
    context: Context,
    display: Display,
    private val onSurfaceReady: (Surface?, Int, Int) -> Unit
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                val ratio by aspectRatioState.collectAsState()
                
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.aspectRatio(ratio).fillMaxSize().background(Color.DarkGray)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {}
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                            onSurfaceReady(holder.surface, w, h)
                                        }
                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            onSurfaceReady(null, 0, 0)
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        setContentView(composeView)
    }
}
