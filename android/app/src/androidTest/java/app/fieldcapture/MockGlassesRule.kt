package app.fieldcapture

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import org.junit.rules.ExternalResource

/**
 * Brings Mock Device Kit to a streamable state for a test: enabled, registered, permissions granted,
 * a Ray-Ban Meta paired, powered on, unfolded, worn, and streaming the synthetic test feed
 * (androidTest/assets/test_feed.mp4: H.265 test pattern with a fake label — test-only, never eval data).
 */
class MockGlassesRule(private val registered: Boolean = true) : ExternalResource() {
  val instrumentation = InstrumentationRegistry.getInstrumentation()
  val context: Context = instrumentation.targetContext
  lateinit var kit: MockDeviceKitInterface
  lateinit var glasses: MockGlasses

  override fun before() {
    val pkg = context.packageName
    for (p in listOf("BLUETOOTH_CONNECT", "CAMERA", "RECORD_AUDIO", "POST_NOTIFICATIONS")) {
      // The platform API, not `pm grant` in a shell: ColorOS's permission monitoring can block the shell route.
      runCatching { instrumentation.uiAutomation.grantRuntimePermission(pkg, "android.permission.$p") }
    }
    kit = MockDeviceKit.getInstance(context)
    kit.enable(MockDeviceKitConfig(registered, true, null))
    glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    glasses.powerOn()
    glasses.unfold()
    glasses.don()
    glasses.services.camera.setCameraFeed(assetUri("test_feed.mp4"))
  }

  override fun after() {
    runCatching { kit.unpairDevice(glasses) }
    runCatching { kit.disable() }
  }

  /** Mock Device Kit takes a file URI: copy the asset out of the test APK first. */
  fun assetUri(name: String): Uri {
    val out = File(context.cacheDir, name)
    instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
    return Uri.fromFile(out)
  }
}
