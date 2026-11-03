package id.suboverlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        val elapsedDefault = if (landscape) RectF(.02f, .72f, .10f, .81f)
            else RectF(.13f, .47f, .25f, .54f)
        val durationDefault = if (landscape) RectF(.81f, .72f, .91f, .81f)
            else RectF(.65f, .47f, .78f, .54f)
        addBox("elapsed", "Waktu awal", load(prefs, "elapsed", elapsedDefault))
        addBox("duration", "Durasi total", load(prefs, "duration", durationDefault))
        addControls(prefs, landscape)
        return START_NOT_STICKY
    }

    private fun load(prefs: android.content.SharedPreferences, kind: String, d: RectF) = RectF(
        prefs.getFloat("${orientationKey}_${kind}_v2_left", d.left),
        prefs.getFloat("${orientationKey}_${kind}_v2_top", d.top),
        prefs.getFloat("${orientationKey}_${kind}_v2_right", d.right),
        prefs.getFloat("${orientationKey}_${kind}_v2_bottom", d.bottom)
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBox(kind: String, label: String, r: RectF) {
        val v = BoxView(kind, label)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val savedW = (r.width() * screenW).toInt().coerceIn(dp(90), dp(240))
        val savedH = (r.height() * screenH).toInt().coerceIn(dp(58), dp(120))
        val p = WindowManager.LayoutParams(savedW, savedH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (r.left * screenW).toInt().coerceIn(0, (screenW - savedW).coerceAtLeast(0))
            y = (r.top * screenH).toInt().coerceIn(0, (screenH - savedH).coerceAtLeast(0))
        }
        wm.addView(v, p)
        views[kind] = v
    }

    private fun addControls(prefs: android.content.SharedPreferences, landscape: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(0xEE202020.toInt()) }
        row.addView(Button(this).apply {
            text = "Simpan"
            setOnClickListener {
                val e = prefs.edit()
                views.forEach { (kind, view) ->
                    val r = view.normalized()
                    e.putFloat("${orientationKey}_${kind}_v2_left", r.left)
                        .putFloat("${orientationKey}_${kind}_v2_top", r.top)
                        .putFloat("${orientationKey}_${kind}_v2_right", r.right)
                        .putFloat("${orientationKey}_${kind}_v2_bottom", r.bottom)
                }
                val saved = e.commit()
                Toast.makeText(
                    this@OcrAreaSelectionService,
                    if (saved) "Posisi dan ukuran area OCR tersimpan ($orientationKey)" else "Gagal menyimpan area OCR",
                    Toast.LENGTH_SHORT
                ).show()
                stopSelf()
            }
        })
        row.addView(Button(this).apply {
            text = "Reset"
            setOnClickListener {
                val elapsedDefault = if (landscape) RectF(.02f, .72f, .10f, .81f)
                    else RectF(.13f, .47f, .25f, .54f)
                val durationDefault = if (landscape) RectF(.81f, .72f, .91f, .81f)
                    else RectF(.65f, .47f, .78f, .54f)
                views["elapsed"]?.reset(elapsedDefault)
                views["duration"]?.reset(durationDefault)
            }
        })
        row.addView(Button(this).apply { text = "Batal"; setOnClickListener { stopSelf() } })
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var resizing = false

        fun normalized(): RectF {
            val p = layoutParams as WindowManager.LayoutParams
            val w = resources.displayMetrics.widthPixels.toFloat(); val h = resources.displayMetrics.heightPixels.toFloat()
            val boxW = p.width.coerceAtLeast(dp(1)).toFloat()
            val boxH = p.height.coerceAtLeast(dp(1)).toFloat()
            val left = (p.x / w).coerceIn(0f, .99f)
            val top = (p.y / h).coerceIn(0f, .99f)
            return RectF(
                left,
                top,
                ((p.x + boxW) / w).coerceIn(left + .001f, 1f),
                ((p.y + boxH) / h).coerceIn(top + .001f, 1f)
            )
        }
        fun reset(r: RectF) {
            val p = layoutParams as WindowManager.LayoutParams
            p.x = (r.left * resources.displayMetrics.widthPixels).toInt()
            p.y = (r.top * resources.displayMetrics.heightPixels).toInt()
            p.width = dp(150); p.height = dp(76); wm.updateViewLayout(this, p)
        }
        override fun onDraw(c: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), bg)
            c.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), border)
            c.drawText(label, dp(8).toFloat(), dp(20).toFloat(), txt)
            c.drawText(if (kind == "elapsed") "00:00" else "02:10:05", dp(8).toFloat(), dp(48).toFloat(), txt)
            c.drawCircle(width - dp(9).toFloat(), height - dp(9).toFloat(), dp(8).toFloat(), handle)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = layoutParams as WindowManager.LayoutParams
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    resizing = e.x > width - dp(28) && e.y > height - dp(28)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (resizing) {
                        p.width = (dp(150) + dx).coerceIn(dp(90), dp(240))
                        p.height = (dp(76) + dy).coerceIn(dp(58), dp(120))
                    } else {
                        p.x = (startX + dx).coerceIn(0, (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0))
                        p.y = (startY + dy).coerceIn(0, (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0))
                    }
                    wm.updateViewLayout(this, p); return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return true
        }
    }
}