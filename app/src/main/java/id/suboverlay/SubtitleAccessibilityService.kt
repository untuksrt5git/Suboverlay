package id.suboverlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private val poll = object : Runnable {
        override fun run() {
            scanActiveWindow()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scanActiveWindow()
    }

    private fun scanActiveWindow() {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            // The video player may live in a non-active browser window.
            for (window in windows) window.root?.let { roots.add(it) }
            if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
            var seenPackage = ""
            var rangeCount = 0
            val candidates = linkedSetOf<String>()
            for (root in roots) {
                val packageName = root.packageName?.toString().orEmpty()
                if (!isUcBrowser(packageName)) continue
                seenPackage = packageName
                rangeCount += countRangeNodes(root, 0)
                collectControlDiagnostics(root, 0, candidates)
                // Prefer a real seekbar; some UC player versions expose only time labels.
                val position = findSeekPosition(root, 0).takeIf { it >= 0L }
                    ?: findPositionFromTimeLabels(root)
                if (position != null && position >= 0L) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastPosition < 0L || kotlin.math.abs(position - lastPosition) >= POSITION_CHANGE_THRESHOLD_MS) {
                        lastPosition = position
                        lastPositionChangeAt = now
                    }
                    val appearsPlaying = now - lastPositionChangeAt < PLAYING_WINDOW_MS
                    SubtitleOverlayService.updateAccessibilityPosition(position, appearsPlaying)
                    SubtitleOverlayService.updateAccessibilityDiagnostic(packageName + " · RangeInfo: " + rangeCount + " · posisi: " + (position / 1000) + "s")
                    return
                }
            }
            val message = if (seenPackage.isNotBlank()) {
                seenPackage + " · RangeInfo: " + rangeCount +
                    " · kontrol: " + if (candidates.isEmpty()) "tidak diekspos" else candidates.joinToString(" | ") +
                    " · waktu video belum terbaca"
            } else "Jendela UC Browser tidak terlihat · windows: " + roots.size
            SubtitleOverlayService.updateAccessibilityDiagnostic(message)
        } catch (_: Exception) {
            SubtitleOverlayService.updateAccessibilityDiagnostic("Gagal membaca pohon Accessibility")
        } finally {
            roots.forEach { try { it.recycle() } catch (_: Exception) { } }
        }
    }

    private fun countRangeNodes(node: AccessibilityNodeInfo, depth: Int): Int {
        if (depth > MAX_DEPTH) return 0
        var count = if (node.rangeInfo != null) 1 else 0
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            count += try { countRangeNodes(child, depth + 1) } finally { child.recycle() }
        }
        return count
    }
    /**
     * UC may expose its floating-player controls as buttons/text descriptions rather than
     * RangeInfo seekbars. Keep a small, UC-only sample to diagnose what Android exposes.
     */
    private fun collectControlDiagnostics(
        node: AccessibilityNodeInfo,
        depth: Int,
        output: MutableSet<String>
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_DIAGNOSTIC_NODES) return
        val className = node.className?.toString().orEmpty().substringAfterLast('.')
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val searchable = (text + " " + description + " " + viewId + " " + className).lowercase()
        val looksRelevant = node.isClickable || node.isScrollable ||
            listOf("seek", "progress", "play", "pause", "time", "duration", "video", "slider").any { searchable.contains(it) }
        if (looksRelevant) {
            val details = listOf(className, viewId, description.ifBlank { text })
                .filter { it.isNotBlank() }
                .joinToString(":")
                .replace(Regex("\\s+"), " ")
                .take(72)
            if (details.isNotBlank()) output.add(details)
        }
        for (index in 0 until node.childCount) {
            if (output.size >= MAX_DIAGNOSTIC_NODES) break
            val child = node.getChild(index) ?: continue
            try { collectControlDiagnostics(child, depth + 1, output) } finally { child.recycle() }
        }
    }

    /**
     * Fallback for UC player layouts that draw a timeline but expose no SeekBar/RangeInfo.
     * It reads the elapsed-time and total-duration labels visible in the player.
     */
    private fun findPositionFromTimeLabels(root: AccessibilityNodeInfo): Long? {
        val values = mutableListOf<Long>()
        collectTimeLabels(root, 0, values)
        val distinct = values.distinct().sorted()
        if (distinct.size < 2) return null

        val duration = distinct.last()
        val elapsed = distinct.first()
        if (duration < MIN_VIDEO_DURATION_MS || duration > MAX_VIDEO_MS) return null
        if (elapsed >= duration || duration - elapsed < MIN_DURATION_GAP_MS) return null
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

    private fun findSeekPosition(node: AccessibilityNodeInfo, depth: Int): Long {
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
                    max > 100_000f -> current.toLong()
                    max > 120f -> (current * 1000f).toLong()
                    else -> -1L
                }
                if (valueMs in 0..MAX_VIDEO_MS) return valueMs
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val result = try {
                findSeekPosition(child, depth + 1)
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
        super.onDestroy()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val PLAYING_WINDOW_MS = 3_000L
        private const val POSITION_CHANGE_THRESHOLD_MS = 250L
        private const val MAX_VIDEO_MS = 24L * 60L * 60L * 1000L
        private const val MIN_VIDEO_DURATION_MS = 10L * 60L * 1000L
        private const val MIN_DURATION_GAP_MS = 60L * 1000L
        private const val MAX_TIME_LABELS = 24
        private val VIDEO_TIME_REGEX = Regex("""^(?:(\d{1,2}):)?(\d{1,2}):(\d{2})$""")
        private const val MAX_DEPTH = 25
        private const val MAX_DIAGNOSTIC_NODES = 6
    }
}
