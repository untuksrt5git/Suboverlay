package id.suboverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.ComponentName
import android.media.session.PlaybackState
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    init { currentInstance = this }
    private var overlay: LinearLayout? = null
    private var subtitleView: TextView? = null
    private var syncStatusView: TextView? = null
    private var cues: List<SubtitleCue> = emptyList()
    private var manualPositionMs = 0L
    private var manualOffsetMs = 0L
    private var fallbackStartedAtMs = 0L
    private var fallbackRunning = false
    private var accessibilityPositionMs = -1L
    private var accessibilityUpdatedAtMs = 0L
    private var accessibilityPlaying = false
    private var subtitleTextSizeSp = 19f
    private var overlayBackgroundAlpha = 204
    private val handler = Handler(Looper.getMainLooper())
    private val syncTask = object : Runnable { override fun run() { syncFromMediaSession(); handler.postDelayed(this, 500) } }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post(syncTask)
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
        manualOffsetMs = 0L
        fallbackStartedAtMs = 0L
        fallbackRunning = false
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
        syncStatusView = TextView(this).apply {
            setTextColor(0xFFBDBDBD.toInt()); textSize = 10f; gravity = Gravity.CENTER
            setPadding(8, 2, 8, 4); this.text = "Memeriksa sesi video…"
        }

        fun makeControlRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun addControl(row: LinearLayout, label: String, action: () -> Unit) {
            row.addView(Button(this).apply {
                this.text = label; textSize = 11f; minWidth = 0; setPadding(2, 0, 2, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { action() }
            })
        }

        val playbackControls = makeControlRow()
        addControl(playbackControls, "Mulai") {
            if (!fallbackRunning) {
                fallbackStartedAtMs = SystemClock.elapsedRealtime() - manualPositionMs
                fallbackRunning = true
                updateSyncStatus("Jam manual berjalan (tidak mengikuti video)")
            }
        }
        addControl(playbackControls, "Jeda") {
            if (fallbackRunning) {
                manualPositionMs = (SystemClock.elapsedRealtime() - fallbackStartedAtMs).coerceAtLeast(0L)
                fallbackRunning = false
                updateSyncStatus("Jam manual dijeda")
                renderSubtitle()
            }
        }

        val adjustmentControls = makeControlRow()
        addControl(adjustmentControls, "−5s") { manualOffsetMs -= 5_000; renderSubtitle() }
        addControl(adjustmentControls, "+5s") { manualOffsetMs += 5_000; renderSubtitle() }
        addControl(adjustmentControls, "Tutup") { stopOverlay(); stopSelf() }

        val appearanceControls = makeControlRow()
        addControl(appearanceControls, "A−") { adjustTextSize(-2f) }
        addControl(appearanceControls, "A+") { adjustTextSize(2f) }
        addControl(appearanceControls, "Op−") { adjustBackgroundAlpha(-20) }
        addControl(appearanceControls, "Op+") { adjustBackgroundAlpha(20) }

        val fineSyncControls = makeControlRow()
        addControl(fineSyncControls, "Sync −0.5s") { manualOffsetMs -= 500; renderSubtitle() }
        addControl(fineSyncControls, "Sync +0.5s") { manualOffsetMs += 500; renderSubtitle() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(overlayBackgroundAlpha, 17, 17, 17))
            addView(text); addView(syncStatusView); addView(playbackControls)
            addView(adjustmentControls); addView(appearanceControls); addView(fineSyncControls)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 90 }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        text.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY - (event.rawY - downY).toInt()
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

    private fun syncFromMediaSession() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, SessionAccessService::class.java))
            val candidates = sessions.mapNotNull { controller ->
                val state = controller.playbackState ?: return@mapNotNull null
                if (state.position < 0L || (state.state != PlaybackState.STATE_PLAYING && state.state != PlaybackState.STATE_PAUSED)) return@mapNotNull null
                controller to state
            }
            val selected = candidates.firstOrNull { it.first.packageName.startsWith("com.UCMobile", true) || it.first.packageName.startsWith("com.uc.browser", true) }
                ?: candidates.firstOrNull()
            if (selected != null) {
                val (controller, state) = selected
                val elapsed = if (state.state == PlaybackState.STATE_PLAYING)
                    (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L) else 0L
                manualPositionMs = state.position + elapsed
                val stateLabel = if (state.state == PlaybackState.STATE_PLAYING) "PLAY" else "PAUSE"
                updateSyncStatus("MediaSession: ${controller.packageName} · $stateLabel · ${manualPositionMs / 1000}s")
                renderSubtitle()
            } else {
                val now = SystemClock.elapsedRealtime()
                val accessFresh = accessibilityPositionMs >= 0L && now - accessibilityUpdatedAtMs < ACCESSIBILITY_FRESH_MS
                if (accessFresh) {
                    val elapsed = if (accessibilityPlaying) (now - accessibilityUpdatedAtMs).coerceAtLeast(0L) else 0L
                    manualPositionMs = accessibilityPositionMs + elapsed
                    updateSyncStatus("Accessibility seekbar · ${if (accessibilityPlaying) "PLAY" else "PAUSE"} · ${manualPositionMs / 1000}s")
                    renderSubtitle()
                } else {
                    val packageList = sessions.map { it.packageName }.distinct().take(3).joinToString()
                    updateSyncStatus(if (packageList.isBlank()) "MediaSession/seekbar belum terdeteksi" else "Sesi lain: $packageList · seekbar tidak terbaca")
                    if (fallbackRunning) {
                        manualPositionMs = (SystemClock.elapsedRealtime() - fallbackStartedAtMs).coerceAtLeast(0L)
                        renderSubtitle()
                    }
                }
            }
        } catch (_: SecurityException) {
            updateSyncStatus("Akses MediaSession ditolak — periksa Akses notifikasi")
        } catch (_: Exception) {
            updateSyncStatus("Gagal membaca MediaSession")
        }
    }

    private fun adjustTextSize(deltaSp: Float) {
        subtitleTextSizeSp = (subtitleTextSizeSp + deltaSp).coerceIn(12f, 40f)
        subtitleView?.textSize = subtitleTextSizeSp
    }

    private fun adjustBackgroundAlpha(delta: Int) {
        overlayBackgroundAlpha = (overlayBackgroundAlpha + delta).coerceIn(60, 255)
        val color = Color.argb(overlayBackgroundAlpha, 17, 17, 17)
        overlay?.setBackgroundColor(color)
        subtitleView?.setBackgroundColor(color)
    }

    private fun updateSyncStatus(message: String) {
        syncStatusView?.text = message
    }

    private fun renderSubtitle() {
        val cue = SrtParser.at(cues, (manualPositionMs + manualOffsetMs).coerceAtLeast(0))
        subtitleView?.text = cue?.text ?: if (cues.isEmpty()) "File subtitle kosong/tidak terbaca" else "—"
    }

    private fun stopOverlay() {
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        overlay = null; subtitleView = null; syncStatusView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Suboverlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("Suboverlay aktif").setContentText("Subtitle mengambang sedang berjalan")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
    }

    override fun onDestroy() { handler.removeCallbacks(syncTask); stopOverlay(); if (currentInstance === this) currentInstance = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACCESSIBILITY_FRESH_MS = 2_000L

        @Volatile private var currentInstance: SubtitleOverlayService? = null

        fun updateAccessibilityPosition(positionMs: Long, playing: Boolean) {
            currentInstance?.let { service ->
                service.accessibilityPositionMs = positionMs
                service.accessibilityUpdatedAtMs = SystemClock.elapsedRealtime()
                service.accessibilityPlaying = playing
            }
        }

        const val ACTION_START = "id.suboverlay.START"
        const val ACTION_STOP = "id.suboverlay.STOP"
        private const val CHANNEL_ID = "suboverlay_service"
        private const val NOTIFICATION_ID = 41
    }
}
