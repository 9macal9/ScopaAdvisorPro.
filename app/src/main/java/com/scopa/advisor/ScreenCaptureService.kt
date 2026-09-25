package com.example.scopaadvisor

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.example.scopaadvisor.STOP_CAPTURE"
        private const val CHANNEL_ID = "scopa_capture"
        private const val NOTIFICATION_ID = 42
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: View? = null
    private var wm: WindowManager? = null
    private lateinit var recognizer: CardRecognitionEngine
    private val processing = AtomicBoolean(false)
    private var lastProcessMs = 0L

    override fun onCreate() {
        super.onCreate()
        recognizer = CardRecognitionEngine(this)
        createChannel()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Analisi schermo attiva"))
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode == Activity.RESULT_OK && data != null) startProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = SystemClock.elapsedRealtime()
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (now - lastProcessMs < 850 || !processing.compareAndSet(false, true)) { image.close(); return@setOnImageAvailableListener }
            lastProcessMs = now
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                Thread {
                    try { processBitmap(bitmap) } finally { bitmap.recycle(); processing.set(false) }
                }.start()
            } catch (_: Throwable) { processing.set(false) } finally { image.close() }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = projection?.createVirtualDisplay(
            "ScopaAdvisorCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun processBitmap(bitmap: Bitmap) {
        val r = recognizer.recognize(bitmap)
        AutoState.hand = r.hand
        AutoState.table = r.table
        AutoState.seen = AutoState.seen + r.hand + r.table
        AutoState.status = if (r.templateCount == 0) {
            "AUTO: aggiungi il profilo grafico delle carte"
        } else {
            "AUTO: ${r.hand.size} mano, ${r.table.size} tavolo · conf. ${(r.confidence*100).toInt()}%"
        }
        if (r.hand.isNotEmpty()) {
            val zones = ScopaEngine.allCards.associateWith { c ->
                when {
                    c in r.hand -> Zone.HAND
                    c in r.table -> Zone.TABLE
                    c in AutoState.seen -> Zone.MINE
                    else -> Zone.UNKNOWN
                }
            }
            val advice = ScopaEngine.analyze(zones, oppHandCount = 3, runs = 220).firstOrNull()
            AutoState.lastSuggestion = advice?.let {
                val take = if (it.move.capture.isEmpty()) "" else " | prende ${it.move.capture.joinToString()}"
                "${it.move.card} · ${(it.winPct*100).toInt()}%$take"
            } ?: ""
        }
        Handler(Looper.getMainLooper()).post { updateOverlay() }
    }

    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(0xE61A1A1A.toInt())
        }
        val status = TextView(this).apply { tag = "status"; setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; text = "Scopa Advisor" }
        val advice = TextView(this).apply { tag = "advice"; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; text = "Avvio..." }
        box.addView(status); box.addView(advice)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 130 }
        box.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var px = 0; var py = 0
            override fun onTouch(v: View?, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; return true }
                    MotionEvent.ACTION_MOVE -> { params.x = px - (e.rawX - sx).toInt(); params.y = py + (e.rawY - sy).toInt(); wm?.updateViewLayout(box, params); return true }
                }
                return false
            }
        })
        overlay = box
        wm?.addView(box, params)
    }

    private fun updateOverlay() {
        val box = overlay as? LinearLayout ?: return
        (box.findViewWithTag<TextView>("status"))?.text = AutoState.status
        (box.findViewWithTag<TextView>("advice"))?.text = AutoState.lastSuggestion.ifBlank { "Nessun consiglio disponibile" }
    }

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val pi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Scopa Advisor Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Ferma", pi).build())
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Cattura schermo", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
