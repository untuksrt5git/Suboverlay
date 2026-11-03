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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class SubtitleOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    init { currentInstance = this }
    private var overlay: LinearLayout? = null
    private var controlPanel: LinearLayout? = null
    private var controlHandle: Button? = null
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
    private var accessibilityDiagnostic = "menunggu data browser"
    private var subtitleTextSizeSp = 19f
    private var overlayBackgroundAlpha = 204
    private var dragMode = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var subtitleParams: WindowManager.LayoutParams? = null
    private val overlayPrefs by lazy { getSharedPreferences("overlay_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val subtitleLoader = Executors.newSingleThreadExecutor()
    private var subtitleLoadRequestId = 0L
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
            subtitleLoadRequestId++
            stopOverlay(); stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            intent.data?.let { loadSubtitle(it) }
            showOverlay()
        }
        return START_NOT_STICKY
    }

    private fun loadSubtitle(uri: Uri) {
        val requestId = ++subtitleLoadRequestId
        subtitleLoader.execute {
            val totalStartedAtMs = SystemClock.elapsedRealtime()
            try {
                val readStartedAtMs = SystemClock.elapsedRealtime()
                val content = contentResolver.openInputStream(uri)?.use {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
                }.orEmpty()
                val readMs = SystemClock.elapsedRealtime() - readStartedAtMs
                val parseStartedAtMs = SystemClock.elapsedRealtime()
                val parsedCues = SrtParser.parse(content)
                val parseMs = SystemClock.elapsedRealtime() - parseStartedAtMs
                val fileBytes = uri.path?.let { java.io.File(it).length() } ?: -1L
                val totalMs = SystemClock.elapsedRealtime() - totalStartedAtMs
                Log.i("SuboverlaySrt", "Loaded ${uri.lastPathSegment}: bytes=$fileBytes chars=${content.length} cues=${parsedCues.size} readMs=$readMs parseMs=$parseMs totalMs=$totalMs")
                handler.post {
                    if (requestId != subtitleLoadRequestId) return@post
                    cues = parsedCues
                    manualPositionMs = 0L
                    manualOffsetMs = 0L
                    fallbackStartedAtMs = 0L
                    fallbackRunning = false
                    renderSubtitle()
                    android.widget.Toast.makeText(this@SubtitleOverlayService,
                        "Diagnostik SRT: ${if (fileBytes >= 0) fileBytes / 1024 else "?"} KB · ${parsedCues.size} subtitle · baca ${readMs}ms · parsing ${parseMs}ms",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("SuboverlaySrt", "Failed to read or parse subtitle $uri", e)
                handler.post {
                    if (requestId == subtitleLoadRequestId) {
                        updateSyncStatus("Gagal memuat subtitle")
                    }
                }
            }
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        subtitleTextSizeSp = overlayPrefs.getFloat("text_size", subtitleTextSizeSp).coerceIn(12f, 40f)
        overlayBackgroundAlpha = overlayPrefs.getInt("background_alpha", overlayBackgroundAlpha).coerceIn(60, 255)
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = subtitleTextSizeSp
            gravity = Gravity.CENTER
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.argb(overlayBackgroundAlpha, 17, 17, 17))
            this.text = "Suboverlay siap"
        }
        subtitleView = text
        syncStatusView = TextView(this).apply {
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            this.text = "Menunggu posisi video"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = overlayPrefs.getInt("x", 0)
            y = overlayPrefs.getInt("y", dp(70))
        }
        subtitleParams = layoutParams
        root.setOnTouchListener { _, event -> handleOverlayTouch(event) }

        try {
            windowManager.addView(root, layoutParams)
            overlay = root
            createControlHandle()
            renderSubtitle()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun createControlHandle() {
        val handle = Button(this).apply {
            text = "⚙"
            textSize = 16f
            minWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleControlPanel() }
        }
        val params = WindowManager.LayoutParams(
            dp(42), dp(42),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(100) }
        windowManager.addView(handle, params)
        controlHandle = handle
    }

    private fun toggleControlPanel() {
        controlPanel?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
            controlPanel = null
            controlHandle?.text = "⚙"
            return
        }
        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addControl(parent: LinearLayout, label: String, action: () -> Unit) {
            parent.addView(Button(this).apply {
                text = label
                textSize = 11f
                minWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                setOnClickListener { action() }
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(0xEE111111.toInt())
        }
        val sync = row()
        addControl(sync, "−5s") { manualOffsetMs -= 5_000; renderSubtitle() }
        addControl(sync, "+5s") { manualOffsetMs += 5_000; renderSubtitle() }
        addControl(sync, "−0.5s") { manualOffsetMs -= 500; renderSubtitle() }
        addControl(sync, "+0.5s") { manualOffsetMs += 500; renderSubtitle() }
        val appearance = row()
        addControl(appearance, "A−") { adjustTextSize(-2f) }
        addControl(appearance, "A+") { adjustTextSize(2f) }
        addControl(appearance, "Op−") { adjustBackgroundAlpha(-20) }
        addControl(appearance, "Op+") { adjustBackgroundAlpha(20) }
        val actions = row()
        addControl(actions, "Ganti subtitle") {
            // Remove overlay controls before opening the system picker so they cannot
            // cover its window on devices that keep application overlays on top.
            controlPanel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
            controlPanel = null
            controlHandle?.visibility = android.view.View.GONE
            controlHandle?.text = "⚙"
            try {
                val picker = Intent(this, SubtitlePickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(picker)
            } catch (_: Exception) {
                controlHandle?.visibility = android.view.View.VISIBLE
                android.widget.Toast.makeText(this, "Tidak dapat membuka pemilih file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        addControl(actions, "Geser subtitle") { toggleDragMode() }
        addControl(actions, "Tutup panel") { toggleControlPanel() }
        addControl(actions, "Matikan") { stopOverlay(); stopSelf() }
        // Show the live playback clock in the control panel for diagnosis.
        // This view was already updated by syncFromMediaSession(), but was not
        // attached to any visible window, so the status could never be seen.
        syncStatusView?.let { status ->
            status.setPadding(dp(8), dp(6), dp(8), dp(6))
            status.maxLines = 3
            panel.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        panel.addView(sync)
        panel.addView(appearance)
        panel.addView(actions)
        val params = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(150) }
        windowManager.addView(panel, params)
        controlPanel = panel
        controlHandle?.text = "×"
    }

    private fun syncFromMediaSession() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, SessionAccessService::class.java))
            val now = SystemClock.elapsedRealtime()

            // Prefer UC Browser's MediaSession as the primary clock. Its position
            // should jump immediately when the user seeks (e.g. fast-forward/back).
            // Accessibility/OCR remains the fallback when UC exposes no usable session.
            val ucSession = sessions.firstOrNull { controller ->
                (controller.packageName.startsWith("com.UCMobile", true) ||
                    controller.packageName.startsWith("com.uc.browser", true)) &&
                    controller.playbackState?.let { state ->
                        state.position >= 0L &&
                            (state.state == PlaybackState.STATE_PLAYING ||
                                state.state == PlaybackState.STATE_PAUSED)
                    } == true
            }

            if (ucSession != null) {
                val state = ucSession.playbackState!!
                val sessionPosition = state.position +
                    if (state.state == PlaybackState.STATE_PLAYING)
                        (now - state.lastPositionUpdateTime).coerceAtLeast(0L) else 0L
                val mediaPosition = sessionPosition.coerceIn(0L, 24L * 60L * 60L * 1000L)
                val accessFresh = accessibilityPositionMs >= 0L &&
                    now - accessibilityUpdatedAtMs < ACCESSIBILITY_FRESH_MS
                val accessPosition = if (accessFresh && accessibilityPlaying) {
                    (accessibilityPositionMs + (now - accessibilityUpdatedAtMs).coerceAtLeast(0L))
                        .coerceIn(0L, 24L * 60L * 60L * 1000L)
                } else accessibilityPositionMs.coerceAtLeast(0L)

                // Some browser MediaSessions keep publishing an old timeline after
                // the user seeks. If the independent Accessibility/OCR clock sees
                // a large jump, trust that fresh position instead of freezing on
                // the stale MediaSession timestamp.
                val accessibilitySawSeek = accessFresh &&
                    kotlin.math.abs(accessPosition - mediaPosition) >= MEDIA_SESSION_SEEK_MISMATCH_MS

                manualPositionMs = if (accessibilitySawSeek) accessPosition else mediaPosition
                val stateLabel = if (state.state == PlaybackState.STATE_PLAYING) "PLAY" else "PAUSE"
                updateSyncStatus(
                    if (accessibilitySawSeek)
                        "Seek UC terdeteksi · Accessibility/OCR: ${manualPositionMs / 1000}s (MediaSession tertinggal)"
                    else
                        "MediaSession UC: $stateLabel · ${manualPositionMs / 1000}s"
                )
                renderSubtitle()
                return
            }

            // No usable UC MediaSession: fall back to the browser accessibility
            // clock, which itself can use visible labels/OCR.
            val accessFresh = accessibilityPositionMs >= 0L &&
                now - accessibilityUpdatedAtMs < ACCESSIBILITY_FRESH_MS
            if (accessFresh) {
                val elapsed = if (accessibilityPlaying)
                    (now - accessibilityUpdatedAtMs).coerceAtLeast(0L) else 0L
                manualPositionMs = (accessibilityPositionMs + elapsed)
                    .coerceIn(0L, 24L * 60L * 60L * 1000L)
                updateSyncStatus("Accessibility/OCR (cadangan) · ${if (accessibilityPlaying) "PLAY" else "PAUSE"} · ${manualPositionMs / 1000}s")
                renderSubtitle()
            } else {
                val packageList = sessions.map { it.packageName }.distinct().take(3).joinToString()
                updateSyncStatus(
                    (if (packageList.isBlank()) "MediaSession UC belum terdeteksi" else "Sesi aktif: $packageList · posisi UC belum terbaca") +
                        "\nA11y/OCR: " + accessibilityDiagnostic
                )
                if (fallbackRunning) {
                    manualPositionMs = (SystemClock.elapsedRealtime() - fallbackStartedAtMs).coerceAtLeast(0L)
                    renderSubtitle()
                }
            }
        } catch (_: SecurityException) {
            updateSyncStatus("Akses MediaSession ditolak — periksa Akses notifikasi")
        } catch (_: Exception) {
            updateSyncStatus("Gagal membaca MediaSession")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun adjustTextSize(deltaSp: Float) {
        subtitleTextSizeSp = (subtitleTextSizeSp + deltaSp).coerceIn(12f, 40f)
        subtitleView?.textSize = subtitleTextSizeSp
        overlayPrefs.edit().putFloat("text_size", subtitleTextSizeSp).apply()
    }

    private fun adjustBackgroundAlpha(delta: Int) {
        overlayBackgroundAlpha = (overlayBackgroundAlpha + delta).coerceIn(60, 255)
        val color = Color.argb(overlayBackgroundAlpha, 17, 17, 17)
        subtitleView?.setBackgroundColor(color)
        overlayPrefs.edit().putInt("background_alpha", overlayBackgroundAlpha).apply()
    }

    private fun toggleDragMode() {
        val params = subtitleParams ?: return
        dragMode = !dragMode
        params.flags = if (dragMode) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { windowManager.updateViewLayout(overlay, params) } catch (_: Exception) { }
        android.widget.Toast.makeText(this, if (dragMode) "Mode geser aktif: tarik teks subtitle" else "Mode geser nonaktif", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun handleOverlayTouch(event: MotionEvent): Boolean {
        if (!dragMode) return false
        val params = subtitleParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = dragStartX + (event.rawX - dragStartRawX).toInt()
                params.y = dragStartY - (event.rawY - dragStartRawY).toInt()
                try { windowManager.updateViewLayout(overlay, params) } catch (_: Exception) { }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                overlayPrefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                return true
            }
        }
        return true
    }

    private fun updateSyncStatus(message: String) {
        syncStatusView?.text = message
    }

    private fun renderSubtitle() {
        val cue = SrtParser.at(cues, (manualPositionMs + manualOffsetMs).coerceAtLeast(0))
        subtitleView?.text = cue?.text ?: if (cues.isEmpty()) "File subtitle kosong/tidak terbaca" else "—"
    }

    private fun stopOverlay() {
        controlPanel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        controlPanel = null
        controlHandle?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        controlHandle = null
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        overlay = null; subtitleParams = null; dragMode = false; subtitleView = null; syncStatusView = null
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

    override fun onDestroy() {
        subtitleLoadRequestId++
        subtitleLoader.shutdownNow()
        handler.removeCallbacks(syncTask)
        stopOverlay()
        if (currentInstance === this) currentInstance = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACCESSIBILITY_FRESH_MS = 2_500L
        private const val MEDIA_SESSION_SEEK_MISMATCH_MS = 2_500L

        @Volatile private var currentInstance: SubtitleOverlayService? = null

        fun restorePickerControls() {
            currentInstance?.controlHandle?.visibility = android.view.View.VISIBLE
        }

        fun updateAccessibilityDiagnostic(message: String) {
            currentInstance?.let { it.accessibilityDiagnostic = message }
        }

        fun resetAccessibilitySyncForOrientation(message: String) {
            currentInstance?.let { service ->
                service.accessibilityPositionMs = -1L
                service.accessibilityUpdatedAtMs = 0L
                service.accessibilityPlaying = false
                service.accessibilityDiagnostic = message
            }
        }

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
