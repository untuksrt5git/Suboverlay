package id.suboverlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Bitmap
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
    private var screenshotInProgress = false
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val poll = object : Runnable {
        override fun run() {
            scanActiveWindow()
            if (android.os.Build.VERSION.SDK_INT >= 30) captureScreenForTime()
            publishEstimatedPosition()
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
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        buffer.close()
                        if (bitmap == null) {
                            screenshotInProgress = false
                            return
                        }
                        textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener(screenshotExecutor) { visionText ->
                                val times = visionText.textBlocks.flatMap { block ->
                                    block.lines.mapNotNull { line -> parseVideoTime(line.text) }
                                }.distinct()
                                val duration = times.filter { it in MIN_VIDEO_DURATION_MS..MAX_VIDEO_MS }.maxOrNull()
                                val elapsed = duration?.let { total ->
                                    // Prefer the largest plausible elapsed timestamp below the
                                    // total duration; short page timestamps must not win.
                                    times.filter { it < total && total - it >= MIN_DURATION_GAP_MS }
                                        .maxOrNull()
                                }
                                if (duration != null && elapsed != null) {
                                    recordPosition(elapsed, screenshotCapturedAt)
                                    SubtitleOverlayService.updateAccessibilityDiagnostic(
                                        "OCR layar · posisi: ${elapsed / 1000}s / ${duration / 1000}s"
                                    )
                                }
                                bitmap.recycle()
                                screenshotInProgress = false
                            }
                            .addOnFailureListener(screenshotExecutor) {
                                bitmap.recycle()
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
        if (lastPosition < 0L) {
            lastPosition = positionMs
            lastPositionObservedAt = observedAtMs
            lastPositionChangeAt = now
            return
        }
        val delta = positionMs - lastPosition
        // OCR reads whole-second labels, which can wobble by 1 second between
        // frames. Ignore small backwards readings instead of making the
        // estimated clock run backwards.
        if (delta < -OCR_BACKWARD_NOISE_MS) {
            // A genuine seek-back changes the anchor, but normal playback
            // continues forward from that new position.
            lastPosition = positionMs
            lastPositionObservedAt = observedAtMs
            lastPositionChangeAt = now
            lastKnownPlaying = true
            lastPlaybackDirection = 1L
        } else if (delta >= POSITION_CHANGE_THRESHOLD_MS) {
            lastPosition = positionMs
            // Anchor to screenshot capture time, not OCR completion time.
            lastPositionObservedAt = observedAtMs
            lastPositionChangeAt = now
            lastKnownPlaying = true
            lastPlaybackDirection = 1L
        } else if (delta >= 0L && now - lastPositionChangeAt >= PLAYING_WINDOW_MS) {
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
        val estimated = if (lastKnownPlaying) {
            (lastPosition + (now - lastPositionObservedAt).coerceAtLeast(0L) * lastPlaybackDirection).coerceAtLeast(0L)
        } else {
            lastPosition
        }
        SubtitleOverlayService.updateAccessibilityPosition(estimated, lastKnownPlaying)
        if (lastKnownPlaying) {
            SubtitleOverlayService.updateAccessibilityDiagnostic("Estimasi berjalan · posisi: ${estimated / 1000}s")
        }
    }

    private fun scanActiveWindow() {
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
                        packageName + " · posisi: " + (position / 1000) + "s" +
                            (duration?.let { " / ${it / 1000}s" } ?: "")
                    )
                    return
                }
            }
            val message = if (seenPackage.isNotBlank()) {
                seenPackage + " · waktu video belum terbaca"
            } else "Jendela UC Browser tidak terlihat · windows: " + roots.size
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
        private const val OCR_BACKWARD_NOISE_MS = 1_500L
        private const val MAX_VIDEO_MS = 24L * 60L * 60L * 1000L
        private const val MIN_VIDEO_DURATION_MS = 10L * 60L * 1000L
        private const val MIN_DURATION_GAP_MS = 60L * 1000L
        private const val MAX_TIME_LABELS = 24
        private val VIDEO_TIME_REGEX = Regex("""^(?:(\d{1,2}):)?(\d{1,2}):(\d{2})$""")
        private const val MAX_DEPTH = 25
        private const val MAX_DIAGNOSTIC_NODES = 6
    }
}
