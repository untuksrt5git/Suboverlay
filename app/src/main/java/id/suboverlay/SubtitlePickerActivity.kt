package id.suboverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/**
 * Opens the system document picker from the floating overlay and returns
 * directly to the app that was playing the video. The overlay service is
 * never stopped while a replacement subtitle is selected.
 */
class SubtitlePickerActivity : Activity() {
    private var pickerActivityStartedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerActivityStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(DIAGNOSTIC_TAG, "Picker activity created; requesting Android file picker")
        try {
            val launchStartedAtMs = SystemClock.elapsedRealtime()
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQUEST_PICK)
            Log.i(DIAGNOSTIC_TAG, "startActivityForResult returned in ${SystemClock.elapsedRealtime() - launchStartedAtMs} ms")
        } catch (e: Exception) {
            Log.e(DIAGNOSTIC_TAG, "Could not launch Android file picker", e)
            Toast.makeText(this, "Tidak dapat membuka pemilih file.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        Log.i(DIAGNOSTIC_TAG, "Picker result received after ${SystemClock.elapsedRealtime() - pickerActivityStartedAtMs} ms; resultCode=$resultCode")
        SubtitleOverlayService.restorePickerControls()
        if (resultCode != RESULT_OK) {
            finish()
            return
        }
        val source = data?.data
        if (source == null) {
            finish()
            return
        }
        try {
            val displayName = queryDisplayName(source)
            val extension = displayName.substringAfterLast('.', "").lowercase()
            if (extension != "srt" && extension != "vtt") {
                Toast.makeText(this, "Pilih file subtitle .srt atau .vtt", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val directory = File(filesDir, "subtitles").apply { mkdirs() }
            val target = File(directory, "subtitle_${System.currentTimeMillis()}.$extension")
            val copyStartedAtMs = SystemClock.elapsedRealtime()
            contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "File tidak dapat dibaca" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(DIAGNOSTIC_TAG, "Copied $displayName: ${target.length()} bytes in ${SystemClock.elapsedRealtime() - copyStartedAtMs} ms")
            val localUri = Uri.fromFile(target)
            getSharedPreferences("subtitle_selection", MODE_PRIVATE).edit()
                .putString("uri", localUri.toString())
                .putString("display_name", displayName)
                .apply()
            val serviceIntent = Intent(this, SubtitleOverlayService::class.java).apply {
                action = SubtitleOverlayService.ACTION_START
                this.data = localUri
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            Toast.makeText(this, "Subtitle diganti: $displayName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memuat subtitle: ${e.message ?: "file tidak dapat dibaca"}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Subtitle"
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

    companion object {
        private const val REQUEST_PICK = 3101
        private const val DIAGNOSTIC_TAG = "SuboverlayPicker"
    }
}
