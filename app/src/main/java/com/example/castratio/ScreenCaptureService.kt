package com.example.castratio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentPresentation: MirrorPresentation? = null
    private var tvSurface: Surface? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundNotification()

            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA")
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                
                // Keep projection alive for Android 14+ strict rules
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {}, null)
                
                setupSecondaryDisplayScanner()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
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
            .setContentTitle("CastRatio Active")
            .setContentText("Filtering screen to Anycast...")
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
                
                try {
                    val displayContext = applicationContext.createDisplayContext(display)
                    val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    } else {
                        displayContext
                    }

                    currentPresentation = MirrorPresentation(windowContext, display) { surface ->
                        tvSurface = surface
                        startVirtualDisplay()
                    }.apply { show() }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(applicationContext, "TV Render Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startVirtualDisplay() {
        stopVirtualDisplay()
        if (tvSurface != null && mediaProjection != null) {
            try {
                // FIX: Always capture using the phone's exact screen dimensions. 
                // This prevents the hardware encoder from silently failing due to weird TV box dimensions.
                val metrics = applicationContext.resources.displayMetrics
                val screenWidth = metrics.widthPixels
                val screenHeight = metrics.heightPixels
                val screenDensity = metrics.densityDpi

                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "CastRatioDisplay",
                    screenWidth, screenHeight, screenDensity,
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
    private val onSurfaceReady: (Surface?) -> Unit
) : Presentation(context, display) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var aspectRatioLayout: AspectRatioFrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Root layout fills the TV with black bars
        val rootLayout = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        // The aspect ratio container (No background color here so it doesn't block the video)
        aspectRatioLayout = AspectRatioFrameLayout(context).apply {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
            layoutParams = params
        }

        // The video feed surface
        val surfaceView = SurfaceView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // FIX: Forces the video to render above the window background
            setZOrderMediaOverlay(true)
            
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    onSurfaceReady(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    onSurfaceReady(null)
                }
            })
        }

        aspectRatioLayout.addView(surfaceView)
        rootLayout.addView(aspectRatioLayout)
        setContentView(rootLayout)

        scope.launch {
            aspectRatioState.collect { ratio ->
                aspectRatioLayout.setRatio(ratio)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}

class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
    private var targetRatio = 16f / 9f

    fun setRatio(ratio: Float) {
        targetRatio = ratio
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val reqHeight = (width / targetRatio).toInt()
        if (reqHeight <= height) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(reqHeight, MeasureSpec.EXACTLY)
            )
        } else {
            val reqWidth = (height * targetRatio).toInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(reqWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
    }
}
