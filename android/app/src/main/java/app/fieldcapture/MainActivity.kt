package app.fieldcapture

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.fieldcapture.glasses.PhotoCodec
import app.fieldcapture.glasses.PhotoResult
import app.fieldcapture.ui.FieldCaptureTheme
import app.fieldcapture.ui.MainActions
import app.fieldcapture.ui.MainScreen
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private val app: FieldCaptureApp
    get() = application as FieldCaptureApp

  private val androidPermissions: Array<String> =
      buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.CAMERA) // Mock Device Kit phone-camera feed
            add(Manifest.permission.RECORD_AUDIO) // voice commands (phone mic)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
          }
          .toTypedArray()

  private val androidPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        app.events.log(
            "permissions",
            results.entries.joinToString { "${it.key.substringAfterLast('.')}=${if (it.value) "granted" else "denied"}" },
        )
        app.initSdk(useMockGlasses = USE_MOCK_GLASSES)
      }

  // DAT permissions (camera on the glasses) are granted through the Meta AI app.
  private var datContinuation: CancellableContinuation<PermissionStatus>? = null
  private val datMutex = Mutex()
  private val datPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        datContinuation?.resume(status)
        datContinuation = null
      }

  private suspend fun requestDatPermission(permission: Permission): PermissionStatus =
      datMutex.withLock {
        suspendCancellableCoroutine { cont ->
          datContinuation = cont
          cont.invokeOnCancellation { datContinuation = null }
          datPermissionLauncher.launch(permission)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Debug builds launched with `--ez mwdat_ui_testing true` expose the MockDevice test server so
    // Meta's MockDevice MCP (Claude Code plugin) can drive the simulated glasses over adb.
    if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_UI_TESTING, false)) app.mock.startTestServer()
    enableEdgeToEdge()
    setContent { FieldCaptureTheme { MainScreen(app, actions) } }
    androidPermissionLauncher.launch(androidPermissions)
  }

  private val actions =
      object : MainActions {
        override fun startWalk() {
          lifecycleScope.launch {
            val current = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
            val status = if (current == PermissionStatus.Granted) current else requestDatPermission(Permission.CAMERA)
            app.events.log("dat_permission", "camera=$status")
            if (status == PermissionStatus.Granted) app.glasses.startWalk()
          }
        }

        override fun register() = Wearables.startRegistration(this@MainActivity)

        override suspend fun takePhoto(): PhotoResult = app.glasses.capturePhoto()

        /** Measurement aid: writes the latest raw frame under each candidate YUV layout. */
        override suspend fun saveDebugFrame(): String =
            withContext(Dispatchers.IO) {
              val frame = app.glasses.frames.latest() ?: return@withContext "no frame yet"
              val dir = File(getExternalFilesDir(null), "debug").apply { mkdirs() }
              val written =
                  PhotoCodec.YuvLayout.entries.mapNotNull { layout ->
                    PhotoCodec.yuvToJpeg(frame, layout)?.let { bytes ->
                      File(dir, "frame_${frame.seq}_${layout.name}.jpg").apply { writeBytes(bytes) }.name
                    }
                  }
              val msg = "${frame.width}x${frame.height} ${frame.size} B -> ${dir.absolutePath} $written"
              app.events.log("debug_frame", msg)
              msg
            }
      }

  companion object {
    private const val EXTRA_UI_TESTING = "mwdat_ui_testing"
    /** Mock Device Kit stands in for the glasses (the brief's setup). */
    const val USE_MOCK_GLASSES = true
  }
}
