package id.suboverlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
        val root = rootInActiveWindow ?: return
        try {
            val packageName = root.packageName?.toString().orEmpty()
            // Limit collection to UC Browser variants; never inspect unrelated apps.
            if (!isUcBrowser(packageName)) return

            val position = findSeekPosition(root, 0)
            if (position >= 0L) {
                val now = SystemClock.elapsedRealtime()
                if (lastPosition < 0L || kotlin.math.abs(position - lastPosition) >= POSITION_CHANGE_THRESHOLD_MS) {
                    lastPosition = position
                    lastPositionChangeAt = now
                }
                // A moving timeline indicates playback; a stable timeline is treated as paused.
                val appearsPlaying = now - lastPositionChangeAt < PLAYING_WINDOW_MS
                SubtitleOverlayService.Companion.updateAccessibilityPosition(position, appearsPlaying)
            }
        } catch (_: Exception) {
            // Retry on the next poll if the browser replaces its accessibility nodes.
        } finally {
            root.recycle()
        }
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
