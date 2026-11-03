package id.suboverlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

/** Two independently movable/resizable OCR windows; no full-screen touch-blocking layer. */
class OcrAreaSelectionService : Service() {
    private lateinit var wm: WindowManager
    private val views = mutableMapOf<String, BoxView>()
    private var controls: View? = null
    private var orientationKey = "portrait"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (views.isNotEmpty()) return START_NOT_STICKY
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        orientationKey = if (landscape) "landscape" else "portrait"
        val prefs = getSharedPreferences("ocr_area_settings", MODE_PRIVATE)
        val elapsedDefault = if (landscape) RectF(.02f, .81f, .10f, .865f)
            else RectF(.145f, .235f, .275f, .275f)
        val durationDefault = if (landscape) RectF(.905f, .81f, .985f, .865f)
            else RectF(.735f, .235f, .845f, .275f)
        addBox("elapsed", "Waktu awal", load(prefs, "elapsed", elapsedDefault))
        addBox("duration", "Durasi total", load(prefs, "duration", durationDefault))
        addControls(prefs, landscape)
        return START_NOT_STICKY
    }

    private fun load(prefs: android.content.SharedPreferences, kind: String, d: RectF) = RectF(
        prefs.getFloat("${orientationKey}_${kind}_v3_left", d.left),
        prefs.getFloat("${orientationKey}_${kind}_v3_top", d.top),
        prefs.getFloat("${orientationKey}_${kind}_v3_right", d.right),
        prefs.getFloat("${orientationKey}_${kind}_v3_bottom", d.bottom)
    )

    private fun fullScreenSize(): Point {
        val point = Point()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(point)
        if (point.x <= 0 || point.y <= 0) {
            point.x = resources.displayMetrics.widthPixels
            point.y = resources.displayMetrics.heightPixels
        }
        return point
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBox(kind: String, label: String, r: RectF) {
        val v = BoxView(kind, label)
        val screen = fullScreenSize()
        val screenW = screen.x
        val screenH = screen.y
        val savedW = (r.width() * screenW).toInt().coerceIn(dp(44), dp(240))
        val savedH = (r.height() * screenH).toInt().coerceIn(dp(36), dp(120))
        val p = WindowManager.LayoutParams(savedW, savedH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (r.left * screenW).toInt().coerceIn(0, (screenW - savedW).coerceAtLeast(0))
            y = (r.top * screenH).toInt().coerceIn(0, (screenH - savedH).coerceAtLeast(0))
        }
        wm.addView(v, p)
        views[kind] = v
        // Overlay window coordinates can start inside a system inset. Shift the window
        // origin so normalized screen coordinates can truly reach X=0 / Y=0.
        v.post {
            if (v.isAttachedToWindow) {
                val location = IntArray(2)
                v.getLocationOnScreen(location)
                val offsetX = location[0] - p.x
                val offsetY = location[1] - p.y
                p.x = (r.left * screenW).toInt() - offsetX
                p.y = (r.top * screenH).toInt() - offsetY
                p.x = p.x.coerceIn(-offsetX, screenW - p.width - offsetX)
                p.y = p.y.coerceIn(-offsetY, screenH - p.height - offsetY)
                wm.updateViewLayout(v, p)
            }
        }
    }

    private fun addControls(prefs: android.content.SharedPreferences, landscape: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(0xEE202020.toInt()) }
        row.addView(Button(this).apply {
            text = "Simpan"
            setOnClickListener {
                val savedBoxes = views.mapValues { (_, view) -> view.normalized() }
                val e = prefs.edit()
                savedBoxes.forEach { (kind, r) ->
                    e.putFloat("${orientationKey}_${kind}_v3_left", r.left)
                        .putFloat("${orientationKey}_${kind}_v3_top", r.top)
                        .putFloat("${orientationKey}_${kind}_v3_right", r.right)
                        .putFloat("${orientationKey}_${kind}_v3_bottom", r.bottom)
                }
                val saved = e.commit()
                val coordinateSummary = savedBoxes.entries.joinToString(" · ") { (kind, r) ->
                    val name = if (kind == "elapsed") "Waktu" else "Durasi"
                    "$name X${(r.left * 100).toInt()}% Y${(r.top * 100).toInt()}%"
                }
                Toast.makeText(
                    this@OcrAreaSelectionService,
                    if (saved) "OCR $orientationKey tersimpan: $coordinateSummary" else "Gagal menyimpan area OCR",
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
            }
        })
        row.addView(Button(this).apply {
            text = "Reset"
            setOnClickListener {
                val elapsedDefault = if (landscape) RectF(.02f, .81f, .10f, .865f)
                    else RectF(.145f, .235f, .275f, .275f)
                val durationDefault = if (landscape) RectF(.905f, .81f, .985f, .865f)
                    else RectF(.735f, .235f, .845f, .275f)
                views["elapsed"]?.reset(elapsedDefault)
                views["duration"]?.reset(durationDefault)
            }
        })
        row.addView(Button(this).apply { text = "Batal"; setOnClickListener { stopSelf() } })
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        controls = row
        wm.addView(row, p)
    }

    override fun onDestroy() {
        views.values.forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        views.clear()
        controls?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        controls = null
        super.onDestroy()
    }

    private inner class BoxView(private val kind: String, private val label: String) : View(this@OcrAreaSelectionService) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x6633B5E5 }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(12).toFloat(); isFakeBoldText = true }
        private val coordinateText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(9).toFloat()
            isFakeBoldText = true
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var startWidth = 0
        private var startHeight = 0
        private var resizing = false

        fun normalized(): RectF {
            val p = layoutParams as WindowManager.LayoutParams
            val screen = fullScreenSize()
            val w = screen.x.toFloat().coerceAtLeast(1f)
            val h = screen.y.toFloat().coerceAtLeast(1f)
            val location = IntArray(2)
            getLocationOnScreen(location)
            val left = (location[0] / w).coerceIn(0f, .999f)
            val top = (location[1] / h).coerceIn(0f, .999f)
            return RectF(
                left,
                top,
                ((location[0] + width) / w).coerceIn(left + .001f, 1f),
                ((location[1] + height) / h).coerceIn(top + .001f, 1f)
            )
        }
        fun reset(r: RectF) {
            val p = layoutParams as WindowManager.LayoutParams
            val screen = fullScreenSize()
            val location = IntArray(2)
            getLocationOnScreen(location)
            val offsetX = location[0] - p.x
            val offsetY = location[1] - p.y
            p.width = (r.width() * screen.x).toInt().coerceIn(dp(44), dp(240))
            p.height = (r.height() * screen.y).toInt().coerceIn(dp(36), dp(120))
            p.x = ((r.left * screen.x).toInt() - offsetX).coerceIn(-offsetX, screen.x - p.width - offsetX)
            p.y = ((r.top * screen.y).toInt() - offsetY).coerceIn(-offsetY, screen.y - p.height - offsetY)
            wm.updateViewLayout(this, p)
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), bg)
            c.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), border)
            c.drawText(label, dp(8).toFloat(), dp(20).toFloat(), txt)
            c.drawText(if (kind == "elapsed") "00:00" else "02:10:05", dp(8).toFloat(), dp(43).toFloat(), txt)
            val position = normalized()
            val xPercent = (position.left * 100).toInt()
            val yPercent = (position.top * 100).toInt()
            c.drawText("X $xPercent%  Y $yPercent%", dp(8).toFloat(), height - dp(5).toFloat(), coordinateText)
            c.drawCircle(width - dp(9).toFloat(), height - dp(9).toFloat(), dp(8).toFloat(), handle)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = layoutParams as WindowManager.LayoutParams
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    startWidth = p.width; startHeight = p.height
                    resizing = e.x > width - dp(28) && e.y > height - dp(28)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (resizing) {
                        p.width = (startWidth + dx).coerceIn(dp(44), dp(240))
                        p.height = (startHeight + dy).coerceIn(dp(36), dp(120))
                    } else {
                        val screen = fullScreenSize()
                        val location = IntArray(2)
                        getLocationOnScreen(location)
                        val offsetX = location[0] - p.x
                        val offsetY = location[1] - p.y
                        p.x = (startX + dx).coerceIn(-offsetX, screen.x - p.width - offsetX)
                        p.y = (startY + dy).coerceIn(-offsetY, screen.y - p.height - offsetY)
                    }
                    wm.updateViewLayout(this, p)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return true
        }
    }
}