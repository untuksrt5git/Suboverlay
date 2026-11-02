package id.suboverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val pickSubtitle = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "Suboverlay"; textSize = 28f; gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = "Subtitle mengambang untuk video di aplikasi lain"
            textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        })
        layout.addView(Button(this).apply {
            text = "Pilih file subtitle (.srt)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, pickSubtitle)
            }
        })
        setContentView(layout)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickSubtitle || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }
        if (!Settings.canDrawOverlays(this)) {
            pendingUri = uri
            Toast.makeText(this, "Izinkan Suboverlay tampil di atas aplikasi lain", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        } else startOverlay(uri)
    }

    override fun onResume() {
        super.onResume()
        pendingUri?.let {
            if (Settings.canDrawOverlays(this)) {
                startOverlay(it)
                pendingUri = null
            }
        }
    }

    private fun startOverlay(uri: Uri) {
        val intent = Intent(this, SubtitleOverlayService::class.java).apply {
            action = SubtitleOverlayService.ACTION_START
            data = uri
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Subtitle overlay dimulai", Toast.LENGTH_SHORT).show()
    }

    companion object { private var pendingUri: Uri? = null }
}
