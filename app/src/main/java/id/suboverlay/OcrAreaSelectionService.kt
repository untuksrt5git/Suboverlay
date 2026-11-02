package id.suboverlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

class OcrAreaSelectionService : Service() {
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var selector: AreaView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel != null) return START_NOT_STICKY
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("ocr_area_settings", MODE_PRIVATE)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val suffix = if (landscape) "landscape" else "portrait"
        val left = prefs.getFloat("${suffix}_left", 0.02f)
        val top = prefs.getFloat("${suffix}_top", if (landscape) 0.78f else 0.69f)
        val right = prefs.getFloat("${suffix}_right", 0.98f)
        val bottom = prefs.getFloat("${suffix}_bottom", if (landscape) 0.98f else 0.755f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x55000000)
        }
        val area = AreaView(left, top, right, bottom)
        selector = area
        root.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        controls.addView(Button(this).apply {
            text = "Simpan"
            setOnClickListener {
                val r = area.normalizedRect()
                prefs.edit().putFloat("${suffix}_left", r.left).putFloat("${suffix}_top", r.top)
                    .putFloat("${suffix}_right", r.right).putFloat("${suffix}_bottom", r.bottom).apply()
                Toast.makeText(this@OcrAreaSelectionService, "Area OCR tersimpan", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        })
        controls.addView(Button(this).apply {
            text = "Reset"
            setOnClickListener { area.reset() }
        })
        controls.addView(Button(this).apply {
            text = "Batal"
            setOnClickListener { stopSelf() }
        })
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        panel = root
        params = WindowManager.LayoutParams(-1, -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(root, params)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        selector = null
        super.onDestroy()
    }

    private inner class AreaView(l: Float, t: Float, r: Float, b: Float) : View(this@OcrAreaSelectionService) {
        private val rect = RectF(l, t, r, b)
        private val fill = Paint( Paint.ANTI_ALIAS_FLAG).apply { color = 0x5533B5E5 }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var mode = 0
        private var downX = 0f
        private var downY = 0f
        private var start = RectF()

        fun normalizedRect(): RectF {
            val w = width.coerceAtLeast(1).toFloat()
            val h = height.coerceAtLeast(1).toFloat()
            return RectF((rect.left / w).coerceIn(0f, .99f), (rect.top / h).coerceIn(0f, .99f),
                (rect.right / w).coerceIn(.01f, 1f), (rect.bottom / h).coerceIn(.01f, 1f))
        }
        fun reset() {
            rect.set(width * .02f, height * .70f, width * .98f, height * .90f)
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (rect.width() <= 1f || rect.height() <= 1f) rect.set(width*.02f,height*.70f,width*.98f,height*.90f)
            c.drawRect(rect, fill); c.drawRect(rect, stroke); c.drawCircle(rect.right, rect.bottom, 22f, handle)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
            c.drawText("Geser kotak • tarik titik kanan bawah untuk ubah ukuran", 20f, 48f, label)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX=e.x; downY=e.y; start.set(rect)
                    mode = if (kotlin.math.abs(e.x-rect.right)<70 && kotlin.math.abs(e.y-rect.bottom)<70) 2 else 1
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=e.x-downX; val dy=e.y-downY
                    if (mode==2) { rect.right=(start.right+dx).coerceIn(rect.left+80f,width.toFloat()); rect.bottom=(start.bottom+dy).coerceIn(rect.top+50f,height.toFloat()) }
                    else {
                        val x=dx.coerceIn(-start.left,width-start.right); val y=dy.coerceIn(-start.top,height-start.bottom)
                        rect.set(start.left+x,start.top+y,start.right+x,start.bottom+y)
                    }
                    invalidate(); return true
                }
            }
            return true
        }
    }
}