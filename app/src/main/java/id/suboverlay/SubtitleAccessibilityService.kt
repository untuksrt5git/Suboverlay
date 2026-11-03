package id.suboverlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads UC Browser playback position from a seekbar, or from paired elapsed/duration
 * labels when the player does not expose a RangeInfo seekbar.
 */
class SubtitleAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPosition = -1L
    private var lastPositionChangeAt = 0L
    private var lastPositionObservedAt = 0L
    private var lastKnownPlaying = false
    private var lastPlaybackDirection = 1L
    // Temporarily stop extrapolating after a detected forward/backward seek.
    private var justSeekedUntil = 0L
    private var screenshotInProgress = false
    private var lastOrientationLabel = ""
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val poll = object : Runnable {
        override fun run() {
            reportOrientationIfChanged()
            // The floating control panel can cover the browser's time labels and
            // its own button captions can be mistaken for OCR time text.
            // Keep the last good diagnostic/position while the panel is visible.
            if (!SubtitleOverlayService.isControlPanelOpen()) {
                scanActiveWindow()
                if (android.os.Build.VERSION.SDK_INT >= 30) captureScreenForTime()
                publishEstimatedPosition()
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    // Avoid scanning the UC Browser tree for every accessibility event.
    // The scheduled poll below is the only source of tree scans.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit


    /** OCR fallback using Android's Accessibility screenshot API (Android 11+). */
    @android.annotation.TargetApi(30)
    private fun captureScreenForTime() {
        if (screenshotInProgress) return
        screenshotInProgress = true
        val screenshotCapturedAt = SystemClock.elapsedRealtime()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val buffer = result.hardwareBuffer
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        buffer.close()
                        if (hardwareBitmap == null) {
                            screenshotInProgress = false
                            return
                        }

                        // Convert to a software bitmap, then OCR only the bottom control
                        // strip where UC Browser/Supjav displays elapsed and total time.
                        val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap.recycle()
                        if (bitmap == null) {
                            screenshotInProgress = false
                            return
                        }
                        // Full-screen rotates the display: the control bar moves
                        // close to the bottom edge in landscape, so use a wider vertical band.
                        val isLandscape = bitmap.width > bitmap.height
                        val areaPrefs = getSharedPreferences("ocr_area_settings", MODE_PRIVATE)
                        val areaKey = if (isLandscape) "landscape" else "portrait"
                        fun cropFor(kind: String, default: RectF): Bitmap {
                            val l = areaPrefs.getFloat("${areaKey}_${kind}_v3_left", default.left)
                            val t = areaPrefs.getFloat("${areaKey}_${kind}_v3_top", default.top)
                            val r = areaPrefs.getFloat("${areaKey}_${kind}_v3_right", default.right)
                            val b = areaPrefs.getFloat("${areaKey}_${kind}_v3_bottom", default.bottom)
                            val x1 = (bitmap.width * l).toInt().coerceIn(0, bitmap.width - 1)
                            val y1 = (bitmap.height * t).toInt().coerceIn(0, bitmap.height - 1)
                            val x2 = (bitmap.width * r).toInt().coerceIn(x1 + 1, bitmap.width)
                            val y2 = (bitmap.height * b).toInt().coerceIn(y1 + 1, bitmap.height)
                            return Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
                        }
                        // Calibrated to the supplied UC Browser screenshots:
                        // portrait is an embedded player in the page; landscape is fullscreen.
                        val elapsedDefault = if (isLandscape) RectF(.02f, .81f, .10f, .865f)
                            else RectF(.145f, .235f, .275f, .275f)
                        val durationDefault = if (isLandscape) RectF(.72f, .76f, .995f, .93f)
                            else RectF(.735f, .235f, .845f, .275f)
                        val elapsedCrop = cropFor("elapsed", elapsedDefault)
                        val durationCrop = cropFor("duration", durationDefault)
                        bitmap.recycle()

                        textRecognizer.process(InputImage.fromBitmap(elapsedCrop, 0))
                            .addOnSuccessListener(screenshotExecutor) { elapsedText ->
                                textRecognizer.process(InputImage.fromBitmap(durationCrop, 0))
                                    .addOnSuccessListener(screenshotExecutor) { durationText ->
                                        val elapsed = elapsedText.textBlocks.flatMap { block ->
                                            block.lines.mapNotNull { line -> parseVideoTime(line.text) }
                                        }.filter { it in 0L..MAX_VIDEO_MS }.maxOrNull()
                                        val duration = durationText.textBlocks.flatMap { block ->
                                            block.lines.mapNotNull { line -> parseVideoTime(line.text) }
                                        }.filter { it in MIN_VIDEO_DURATION_MS..MAX_VIDEO_MS }.maxOrNull()
                                        if (elapsed != null && duration != null && elapsed < duration) {
                                            recordPosition(elapsed, screenshotCapturedAt)
                                            SubtitleOverlayService.updateAccessibilityDiagnostic(
                                                "OCR ${if (isLandscape) "LANDSCAPE" else "PORTRAIT"} · posisi: ${elapsed / 1000}s / ${duration / 1000}s"
                                            )
                                        } else {
                                            // Report each OCR crop separately so a missed elapsed-time
                                            // box can be distinguished from a missed duration box.
                                            val elapsedRaw = elapsedText.text.replace("\n", " ").trim().take(28)
                                            val durationRaw = durationText.text.replace("\n", " ").trim().take(28)
                                            val elapsedState = if (elapsed != null) "terbaca" else "TIDAK terbaca [" + elapsedRaw.ifBlank { "kosong" } + "]"
                                            val durationState = if (duration != null) "terbaca" else "TIDAK terbaca [" + durationRaw.ifBlank { "kosong" } + "]"
                                            SubtitleOverlayService.updateAccessibilityDiagnostic(
                                                "OCR " + (if (isLandscape) "LANDSCAPE" else "PORTRAIT") + " · posisi: " + elapsedState + " · durasi: " + durationState
                                            )
                                        }
                                        elapsedCrop.recycle()
                                        durationCrop.recycle()
                                        screenshotInProgress = false
                                    }
                                    .addOnFailureListener(screenshotExecutor) {
                                        elapsedCrop.recycle(); durationCrop.recycle()
                                        screenshotInProgress = false
                                    }
                            }
                            .addOnFailureListener(screenshotExecutor) {
                                elapsedCrop.recycle(); durationCrop.recycle()
                                screenshotInProgress = false
                            }
                                                } catch (_: Exception) {
                        screenshotInProgress = false
                    }
                }
                override fun onFailure(errorCode: Int) {
                    screenshotInProgress = false
                    SubtitleOverlayService.updateAccessibilityDiagnostic("OCR screenshot gagal · kode $errorCode")
                }
            })
        } catch (_: Exception) {
            screenshotInProgress = false
        }
    }

    /**
     * Cache the last confirmed video time. Movement means playback is active;
     * repeated identical readings for a short grace period indicate pause.
     */
    private fun recordPosition(
        positionMs: Long,
        observedAtMs: Long = SystemClock.elapsedRealtime()
    ) {
        val now = SystemClock.elapsedRealtime()
        // OCR runs asynchronously. A screenshot captured before a newer
        // Accessibility reading can finish later; never let that older frame
        // overwrite the newer playback position (especially after rapid seeks).
        if (lastPositionObservedAt > 0L && observedAtMs < lastPositionObservedAt) return

        if (lastPosition < 0L) {
            lastPosition = positionMs
            lastPositionObservedAt = observedAtMs
            lastPositionChangeAt = now
            return
        }

        val delta = positionMs - lastPosition

        // A jump of 2 seconds or more in either direction is treated as a seek,
        // not as normal playback or OCR drift. Re-anchor immediately and pause
        // extrapolation briefly so the old playback clock cannot keep showing
        // subtitles from the pre-seek position.
        if (kotlin.math.abs(delta) >= SEEK_THRESHOLD_MS) {
            lastPosition = positionMs
            lastPositionObservedAt = observedAtMs
            lastPositionChangeAt = now
            lastKnownPlaying = true
            lastPlaybackDirection = 1L
            justSeekedUntil = now + SEEK_COOLDOWN_MS
            return
        }

        // Small negative changes are one-second OCR label wobble, not a seek.
        if (delta < 0L) return

        if (delta >= POSITION_CHANGE_THRESHOLD_MS) {
            lastPosition = positionMs
            // Anchor to screenshot capture time, not OCR completion time.
            lastPositionObservedAt = observedAtMs
            lastPositionChangeAt = now
            lastKnownPlaying = true
            lastPlaybackDirection = 1L
        } else if (now - lastPositionChangeAt >= PLAYING_WINDOW_MS) {
            lastKnownPlaying = false
            lastPosition = positionMs
            lastPositionObservedAt = now
        }
    }

    /**
     * When controls/time labels disappear, extrapolate cheaply from the last
     * confirmed position. No browser-tree traversal or screenshot is done here.
     */
    private fun publishEstimatedPosition() {
        if (lastPosition < 0L) return
        val now = SystemClock.elapsedRealtime()
        val seekCooldownActive = now < justSeekedUntil
        val estimated = when {
            seekCooldownActive -> lastPosition
            lastKnownPlaying -> (
                lastPosition + (now - lastPositionObservedAt).coerceAtLeast(0L) * lastPlaybackDirection
            ).coerceAtLeast(0L)
            else -> lastPosition
        }

        // Send playing=false during the cooldown too; otherwise the overlay
        // would add elapsed time again and defeat this short freeze.
        SubtitleOverlayService.updateAccessibilityPosition(estimated, lastKnownPlaying && !seekCooldownActive)
        if (seekCooldownActive) {
            SubtitleOverlayService.updateAccessibilityDiagnostic("${orientationLabel()} · seek terdeteksi · posisi: ${estimated / 1000}s")
        } else if (lastKnownPlaying) {
            SubtitleOverlayService.updateAccessibilityDiagnostic("${orientationLabel()} · estimasi berjalan · posisi: ${estimated / 1000}s")
        }
    }

    private fun scanActiveWindow() {
        // In landscape UC's fullscreen player exposes stale/duplicated hidden time
        // labels in its accessibility tree. Prefer the two calibrated screen OCR
        // regions there; the tree labels remain useful for embedded portrait mode.
        if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            SubtitleOverlayService.updateAccessibilityDiagnostic(
                "LANDSCAPE · membaca waktu dari dua kotak OCR"
            )
            return
        }

        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            // The video player may live in a non-active browser window.
            for (window in windows) window.root?.let { roots.add(it) }
            if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
            var seenPackage = ""
            for (root in roots) {
                val packageName = root.packageName?.toString().orEmpty()
                if (!isUcBrowser(packageName)) continue
                seenPackage = packageName
                // Ignore generic RangeInfo seekbars: UC pages can expose ad,
                // page-scroll, or unrelated progress controls before the actual video.
                // Use paired elapsed/total labels instead; screenshot OCR is the
                // fallback when the player hides those labels from Accessibility.
                val duration = findVideoDuration(root)
                val position = findPositionFromTimeLabels(root)
                if (position != null && position >= 0L &&
                    (duration == null || position < duration)) {
                    recordPosition(position)
                    SubtitleOverlayService.updateAccessibilityDiagnostic(
                        orientationLabel() + " · " + packageName + " · posisi: " + (position / 1000) + "s" +
                            (duration?.let { " / ${it / 1000}s" } ?: "")
                    )
                    return
                }
            }
            val message = if (seenPackage.isNotBlank()) {
                orientationLabel() + " · " + seenPackage + " · waktu video belum terbaca"
            } else orientationLabel() + " · jendela UC Browser tidak terlihat · windows: " + roots.size
            SubtitleOverlayService.updateAccessibilityDiagnostic(message)
        } catch (_: Exception) {
            SubtitleOverlayService.updateAccessibilityDiagnostic("Gagal membaca pohon Accessibility")
        } finally {
            roots.forEach { try { it.recycle() } catch (_: Exception) { } }
        }
    }

    /**
     * Fallback for UC player layouts that draw a timeline but expose no SeekBar/RangeInfo.
     * It reads the elapsed-time and total-duration labels visible in the player.
     */
    private fun findVideoDuration(root: AccessibilityNodeInfo): Long? {
        val values = mutableListOf<Long>()
        collectTimeLabels(root, 0, values)
        return values.distinct()
            .filter { it in MIN_VIDEO_DURATION_MS..MAX_VIDEO_MS }
            .maxOrNull()
    }

    private fun findPositionFromTimeLabels(root: AccessibilityNodeInfo): Long? {
        val values = mutableListOf<Long>()
        collectTimeLabels(root, 0, values)
        val distinct = values.distinct().sorted()
        if (distinct.size < 2) return null

        // Only accept a pair that resembles elapsed + total duration.
        // Short timestamps elsewhere on the page (for example "01:04") are
        // not allowed to become the playback position by themselves.
        val duration = distinct.lastOrNull { it in MIN_VIDEO_DURATION_MS..MAX_VIDEO_MS }
            ?: return null
        val elapsed = distinct.lastOrNull {
            it < duration && duration - it >= MIN_DURATION_GAP_MS
        } ?: return null
        return elapsed
    }

    private fun collectTimeLabels(node: AccessibilityNodeInfo, depth: Int, output: MutableList<Long>) {
        if (depth > MAX_DEPTH || output.size >= MAX_TIME_LABELS) return
        parseVideoTime(node.text?.toString())?.let { output.add(it) }
        parseVideoTime(node.contentDescription?.toString())?.let { output.add(it) }
        for (index in 0 until node.childCount) {
            if (output.size >= MAX_TIME_LABELS) break
            val child = node.getChild(index) ?: continue
            try { collectTimeLabels(child, depth + 1, output) } finally { child.recycle() }
        }
    }

    private fun parseVideoTime(raw: String?): Long? {
        val value = raw?.trim() ?: return null
        val match = VIDEO_TIME_REGEX.matchEntire(value) ?: return null
        val hours = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        if (minutes > 59L || seconds > 59L) return null
        val total = (hours * 3600L + minutes * 60L + seconds) * 1000L
        return total.takeIf { it in 0..MAX_VIDEO_MS }
    }
    private fun orientationLabel(): String = when (resources.configuration.orientation) {
        android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
        android.content.res.Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
        else -> "ORIENTASI-TIDAK-DIKETAHUI"
    }

    private fun reportOrientationIfChanged() {
        val current = orientationLabel()
        if (current != lastOrientationLabel) {
            val previous = lastOrientationLabel
            lastOrientationLabel = current
            if (previous.isNotBlank()) {
                // Drop the previous orientation's cached playback anchor.
                lastPosition = -1L
                lastPositionObservedAt = 0L
                lastPositionChangeAt = 0L
                lastKnownPlaying = false
                justSeekedUntil = SystemClock.elapsedRealtime() + ORIENTATION_REFRESH_MS
                screenshotInProgress = false
                SubtitleOverlayService.resetAccessibilitySyncForOrientation(
                    "Layar berubah: $previous -> $current · membaca ulang posisi"
                )
            }
            val message = if (previous.isBlank()) {
                "Orientasi terdeteksi: $current"
            } else {
                "Layar berubah: $previous → $current"
            }
            SubtitleOverlayService.updateAccessibilityDiagnostic(message)
        }
    }

    private fun isUcBrowser(packageName: String): Boolean =
        packageName.startsWith("com.UCMobile", ignoreCase = true) ||
            packageName.startsWith("com.uc.browser", ignoreCase = true)

    private fun findSeekPosition(
        node: AccessibilityNodeInfo,
        depth: Int,
        durationMs: Long?
    ): Long {
        if (depth > MAX_DEPTH) return -1L

        val range = node.rangeInfo
        if (range != null) {
            val className = node.className?.toString()?.lowercase().orEmpty()
            val looksLikeSeekBar = className.contains("seekbar") ||
                className.contains("seek_bar") ||
                className.contains("progressbar")
            val max = range.max
            val current = range.current
            if (looksLikeSeekBar && max > 0f && current >= 0f && current <= max) {
                val valueMs = when {
                    // Native players commonly expose milliseconds.
                    max > 100_000f -> current.toLong()
                    // Some players expose seconds as the range.
                    max > 1_000f -> (current * 1000f).toLong()
                    // Small ranges are usually a percentage/progress scale.
                    // Convert only when a credible video duration is available.
                    durationMs != null && max > 0f ->
                        (current.toDouble() / max.toDouble() * durationMs.toDouble()).toLong()
                    else -> -1L
                }
                if (valueMs in 0..MAX_VIDEO_MS &&
                    (durationMs == null || valueMs < durationMs)) return valueMs
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val result = try {
                findSeekPosition(child, depth + 1, durationMs)
            } finally {
                child.recycle()
            }
            if (result >= 0L) return result
        }
        return -1L
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        screenshotExecutor.shutdown()
        textRecognizer.close()
        super.onDestroy()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val PLAYING_WINDOW_MS = 3_000L
        private const val POSITION_CHANGE_THRESHOLD_MS = 250L
        private const val SEEK_THRESHOLD_MS = 2_000L
        private const val SEEK_COOLDOWN_MS = 1_500L
        private const val ORIENTATION_REFRESH_MS = 2_000L
        private const val MAX_VIDEO_MS = 24L * 60L * 60L * 1000L
        private const val MIN_VIDEO_DURATION_MS = 10L * 60L * 1000L
        private const val MIN_DURATION_GAP_MS = 60L * 1000L
        // Relative to full-screen screenshot; matches the visible player controls
        // in the supplied UC Browser screenshot.
        private const val PORTRAIT_CROP_TOP_RATIO = 0.69f
        private const val PORTRAIT_CROP_BOTTOM_RATIO = 0.755f
        // In landscape fullscreen, player controls are usually near the lower edge.
        private const val LANDSCAPE_CROP_TOP_RATIO = 0.78f
        private const val LANDSCAPE_CROP_BOTTOM_RATIO = 0.98f
        private const val OCR_CROP_LEFT_RATIO = 0.02f
        private const val OCR_CROP_RIGHT_RATIO = 0.98f
        private const val MAX_TIME_LABELS = 24
        private val VIDEO_TIME_REGEX = Regex("""^(?:(\d{1,2}):)?(\d{1,2}):(\d{2})$""")
        private const val MAX_DEPTH = 25
        private const val MAX_DIAGNOSTIC_NODES = 6
    }
}
