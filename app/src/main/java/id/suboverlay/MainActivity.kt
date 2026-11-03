package id.suboverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val pickSubtitle = 1001
    private lateinit var syncButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var subtitleStatus: TextView

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
        syncButton = Button(this).apply {
            setOnClickListener {
                if (isNotificationListenerEnabled()) {
                    Toast.makeText(this@MainActivity, "Akses sinkronisasi sudah aktif.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Aktifkan akses notifikasi Suboverlay untuk membaca sesi media Android.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        }
        layout.addView(syncButton)
        accessibilityButton = Button(this).apply {
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Pastikan layanan Suboverlay Video Position diaktifkan.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        layout.addView(accessibilityButton)
        layout.addView(Button(this).apply {
            text = "Atur area OCR (posisi waktu video)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Izinkan Suboverlay tampil di atas aplikasi lain terlebih dahulu.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                } else {
                    startService(Intent(this@MainActivity, OcrAreaSelectionService::class.java))
                }
            }
        })
        subtitleStatus = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 8)
        }
        layout.addView(subtitleStatus)
        layout.addView(Button(this).apply {
            text = "Pilih / Ganti subtitle (.srt / .vtt)"
            setOnClickListener { openSubtitlePicker() }
        })
        setContentView(layout)
        refreshSubtitleStatus()
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
        val displayName = queryDisplayName(uri)
        getSharedPreferences("subtitle_selection", MODE_PRIVATE).edit()
            .putString("uri", uri.toString())
            .putString("display_name", displayName)
            .commit()
        refreshSubtitleStatus()
        if (!Settings.canDrawOverlays(this)) {
            pendingUri = uri
            Toast.makeText(this, "Izinkan Suboverlay tampil di atas aplikasi lain", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        } else startOverlay(uri)
    }

    override fun onResume() {
        super.onResume()
        if (::syncButton.isInitialized) {
            syncButton.text = if (isNotificationListenerEnabled()) "MediaSession: AKTIF" else "Aktifkan akses MediaSession"
            accessibilityButton.text = if (isAccessibilityServiceEnabled()) "Accessibility: AKTIF" else "Aktifkan Accessibility seekbar"
        }
        refreshSubtitleStatus()
        pendingUri?.let {
            if (Settings.canDrawOverlays(this)) {
                startOverlay(it)
                pendingUri = null
            }
        }
    }

    private fun openSubtitlePicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, pickSubtitle)
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Subtitle dipilih"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: name
                }
            }
        } catch (_: Exception) { }
        return name
    }

    private fun refreshSubtitleStatus() {
        if (!::subtitleStatus.isInitialized) return
        val prefs = getSharedPreferences("subtitle_selection", MODE_PRIVATE)
        subtitleStatus.text = prefs.getString("display_name", null)?.let {
            "Subtitle aktif: $it"
        } ?: "Belum ada file subtitle dipilih"
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val target = ComponentName(this, SessionAccessService::class.java)
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == target }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val target = ComponentName(this, SubtitleAccessibilityService::class.java)
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == target }
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
