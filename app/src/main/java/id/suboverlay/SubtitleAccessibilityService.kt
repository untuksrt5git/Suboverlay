package id.suboverlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the real seekbar range exposed by UC Browser's Android accessibility tree.
 * It deliberately ignores ordinary time labels because they may be duration/ad timers.
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
            for (root in roots) {
                val packageName = root.packageName?.toString().orEmpty()
                if (!isUcBrowser(packageName)) continue
                seenPackage = packageName
                rangeCount += countRangeNodes(root, 0)
                val position = findSeekPosition(root, 0)
                if (position >= 0L) {
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
            val message = if (seenPackage.isNotBlank()) seenPackage + " · RangeInfo: " + rangeCount + " · seekbar tidak tersedia"
                else "Jendela UC Browser tidak terlihat · windows: " + roots.size
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
        private const val MAX_DEPTH = 25
    }
}
