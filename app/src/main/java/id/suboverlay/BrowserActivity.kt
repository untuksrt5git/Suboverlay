package id.suboverlay

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class BrowserActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling || !::webView.isInitialized) return
            webView.evaluateJavascript(
                "(function(){var videos=[];var frames=0;function scan(doc){try{frames++;var list=doc.querySelectorAll('video');for(var i=0;i<list.length;i++){var v=list[i];videos.push({time:v.currentTime,playing:!v.paused&&!v.ended,ready:v.readyState,width:v.videoWidth,height:v.videoHeight});}var fs=doc.querySelectorAll('iframe,frame');for(var j=0;j<fs.length;j++){try{if(fs[j].contentDocument)scan(fs[j].contentDocument);}catch(e){}}}catch(e){}}scan(document);videos.sort(function(a,b){return (b.width*b.height)-(a.width*a.height)||b.ready-a.ready;});return JSON.stringify({videoCount:videos.length,frames:frames,best:videos.length?videos[0]:null});})()"
            ) { result ->
                try {
                    val raw = if (result == null || result == "null") "" else org.json.JSONTokener(result).nextValue() as? String ?: ""
                    if (raw.isNotBlank()) {
                        val data = JSONObject(raw)
                        val video = data.optJSONObject("best")
                        val seconds = video?.optDouble("time", -1.0) ?: -1.0
                        if (video != null && seconds >= 0.0 && seconds.isFinite()) {
                            val playing = video.optBoolean("playing", false)
                            SubtitleOverlayService.updateWebViewPosition((seconds * 1000.0).toLong(), playing)
                            status.text = "Video terdeteksi · ${seconds.toInt()}s · ${if (playing) "PLAY" else "PAUSE"}"
                        } else {
                            status.text = "Belum ada video · ${data.optInt("frames", 0)} frame diperiksa"
                        }
                    } else {
                        status.text = "Mencari elemen video di halaman…"
                    }
                } catch (_: Exception) {
                    status.text = "Membaca waktu video…"
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = EditText(this).apply {
            setSingleLine(true)
            setText("https://supjav.com/")
            hint = "Alamat situs video"
        }
        status = TextView(this).apply { text = "Buka halaman video untuk mulai membaca waktunya" }
        val open = Button(this).apply {
            text = "Buka"
            setOnClickListener {
                var url = address.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    webView.loadUrl(url)
                }
            }
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Halaman dimuat · mencari pemutar video…"
                }
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(open)
            addView(status)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        polling = true
        handler.post(pollTask)
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(pollTask)
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(pollTask)
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
