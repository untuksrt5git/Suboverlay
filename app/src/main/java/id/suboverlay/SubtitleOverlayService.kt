package id.suboverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

class SubtitleOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: LinearLayout? = null
    private var subtitleView: TextView? = null
    private var cues: List<SubtitleCue> = emptyList()
    private var manualPositionMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOverlay(); stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            intent.data?.let { loadSubtitle(it) }
            showOverlay()
        }
        return START_NOT_STICKY
    }

    private fun loadSubtitle(uri: Uri) {
        val content = contentResolver.openInputStream(uri)?.use {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
        }.orEmpty()
        cues = SrtParser.parse(content)
        manualPositionMs = 0L
        renderSubtitle()
    }

    private fun showOverlay() {
        if (overlay != null) return
        val text = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER
            setPadding(18, 10, 18, 10); setBackgroundColor(0xCC111111.toInt())
            this.text = "Suboverlay siap"
        }
        subtitleView = text
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        fun addControl(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply {
                this.text = label; textSize = 11f
                setOnClickListener { action() }
            })
        }
        addControl("−5s") { manualPositionMs = (manualPositionMs - 5_000).coerceAtLeast(0); renderSubtitle() }
        addControl("+5s") { manualPositionMs += 5_000; renderSubtitle() }
        addControl("Tutup") { stopOverlay(); stopSelf() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC111111.toInt())
            addView(text); addView(controls)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 90 }
        // Drag the subtitle text area to move the floating panel.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        text.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    try { windowManager.updateViewLayout(root, params) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try {
            windowManager.addView(root, params)
            overlay = root
            renderSubtitle()
        } catch (_: SecurityException) { stopSelf() }
    }

    private fun renderSubtitle() {
        val cue = SrtParser.at(cues, manualPositionMs.coerceAtLeast(0))
        subtitleView?.text = cue?.text ?: if (cues.isEmpty()) "File subtitle kosong/tidak terbaca" else "—"
    }

    private fun stopOverlay() {
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        overlay = null; subtitleView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Suboverlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("Suboverlay aktif")
            .setContentText("Subtitle mengambang sedang berjalan")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
    }

    override fun onDestroy() { stopOverlay(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "id.suboverlay.START"
        const val ACTION_STOP = "id.suboverlay.STOP"
        private const val CHANNEL_ID = "suboverlay_service"
        private const val NOTIFICATION_ID = 41
    }
}
