package id.suboverlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

/**
 * Reads only an actual seek/progress control exposed by the foreground app.
 * Time labels are intentionally ignored because they may be the video duration,
 * an advertisement timer, or unrelated page text.
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
            val position = findSeekPosition(root, 0)
            if (position >= 0L) {
                val now = SystemClock.elapsedRealtime()
                if (lastPosition < 0L || kotlin.math.abs(position - lastPosition) >= POSITION_CHANGE_THRESHOLD_MS) {
                    lastPosition = position
                    lastPositionChangeAt = now
                }
                // Infer playback only from a changing seekbar. A static seekbar is treated as paused.
                val appearsPlaying = now - lastPositionChangeAt < PLAYING_WINDOW_MS
                SubtitleOverlayService.updateAccessibilityPosition(position, appearsPlaying)
            }
        } catch (_: Exception) {
            // Some apps expose transient or invalid accessibility nodes; retry on next poll.
        } finally {
            root.recycle()
        }
    }

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
                    max > 100_000f -> current.toLong() // already milliseconds
                    max > 120f -> (current * 1000f).toLong() // typical seconds-based range
                    else -> -1L // likely volume/percentage, not a trustworthy timeline
                }
                if (valueMs >= 0L && valueMs <= MAX_VIDEO_MS) return valueMs
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val result = try { findSeekPosition(child, depth + 1) } finally { child.recycle() }
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
        private const val PLAYING_WINDOW_MS = 1_500L
        private const val POSITION_CHANGE_THRESHOLD_MS = 250L
        private const val MAX_VIDEO_MS = 24L * 60L * 60L * 1000L
        private const val MAX_DEPTH = 25
    }
}
