package app.fieldcapture.mock

import android.content.Context
import android.net.Uri
import app.fieldcapture.core.EventLog
import com.meta.wearable.dat.core.types.ThermalLevel
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MockState(
    val enabled: Boolean = false,
    val paired: Boolean = false,
    val poweredOn: Boolean = false,
    val unfolded: Boolean = false,
    val donned: Boolean = false,
    val feed: String = "none",
    val testServerPort: Int? = null,
)

/**
 * Drives Meta's Mock Device Kit: a simulated Ray-Ban Meta pair the rest of the app cannot tell
 * apart from real glasses. Everything the walk-through needs to survive (power loss, doffing,
 * folding, heat, low battery, an out-of-date glasses app) can be triggered from here, by the debug
 * panel, by instrumentation tests, or by the MockDevice MCP through the test server.
 */
class MockGlassesController(context: Context, private val log: EventLog) {
  private val kit = MockDeviceKit.getInstance(context.applicationContext)
  private var glasses: MockGlasses? = null

  private val _state = MutableStateFlow(MockState(enabled = kit.isEnabled))
  val state: StateFlow<MockState> = _state.asStateFlow()

  fun enable(initiallyRegistered: Boolean = true, permissionsGranted: Boolean = true) {
    if (kit.isEnabled) {
      _state.update { it.copy(enabled = true) }
      return
    }
    kit.enable(MockDeviceKitConfig(initiallyRegistered, permissionsGranted, null))
    _state.update { it.copy(enabled = true) }
    log.log("mock", "enabled (registered=$initiallyRegistered, permissions=$permissionsGranted)")
  }

  fun pair(): Boolean {
    if (glasses != null) return true
    return kit.pairGlasses(GlassesModel.RAYBAN_META)
        .fold(
            onSuccess = { g ->
              glasses = g
              _state.update { it.copy(paired = true) }
              log.log("mock", "paired simulated Ray-Ban Meta")
              true
            },
            onFailure = { e, _ ->
              log.log("mock", "pair failed: ${e.description}")
              false
            },
        )
  }

  /** One tap to a streamable mock: enable, pair, power on, unfold, don, phone back camera as feed. */
  fun quickSetup() {
    enable()
    if (!pair()) return
    powerOn()
    unfold()
    don()
    usePhoneCamera(back = true)
  }

  fun powerOn() = act("power on") {
    it.powerOn()
    _state.update { s -> s.copy(poweredOn = true) }
  }

  fun powerOff() = act("power off") {
    it.powerOff()
    _state.update { s -> s.copy(poweredOn = false, donned = false, unfolded = false) }
  }

  fun don() = act("don") {
    it.don()
    _state.update { s -> s.copy(donned = true, unfolded = true) }
  }

  fun doff() = act("doff") {
    it.doff()
    _state.update { s -> s.copy(donned = false) }
  }

  fun fold() = act("fold") {
    it.fold()
    _state.update { s -> s.copy(unfolded = false, donned = false) }
  }

  fun unfold() = act("unfold") {
    it.unfold()
    _state.update { s -> s.copy(unfolded = true) }
  }

  fun battery(level: Int) = act("battery $level%") { it.setBatteryLevel(level) }

  fun thermal(level: ThermalLevel) = act("thermal $level") { it.setThermalLevel(level) }

  fun requireDatAppUpdate(required: Boolean) =
      act("DAT glasses-app update required=$required") {
        it.services.setDATAppUpdateRequirementEnabled(required)
      }

  fun usePhoneCamera(back: Boolean) =
      act("feed = phone ${if (back) "back" else "front"} camera") {
        it.services.camera.setCameraFeed(if (back) CameraFacing.BACK else CameraFacing.FRONT)
        _state.update { s -> s.copy(feed = if (back) "phone back camera" else "phone front camera") }
      }

  /** Replays an H.265 file as the glasses camera (repeatable runs for eval and the failure drill). */
  fun useVideo(uri: Uri, label: String) =
      act("feed = video $label") {
        it.services.camera.setCameraFeed(uri)
        _state.update { s -> s.copy(feed = label) }
      }

  fun setCapturedImage(uri: Uri) = act("captured image set") { it.services.camera.setCapturedImage(uri) }

  /** Lets the MockDevice MCP (Meta's Claude Code plugin) drive this simulated device over adb. */
  fun startTestServer(port: Int = TEST_SERVER_PORT) {
    enable()
    kit.startTestServer(port)
        .fold(
            onSuccess = { p ->
              _state.update { it.copy(testServerPort = p) }
              log.log("mock", "MockDevice test server listening on $p")
            },
            onFailure = { e, _ -> log.log("mock", "test server failed: ${e.description}") },
        )
  }

  /** Exposed for instrumentation tests that need levers this class doesn't wrap. */
  fun glassesOrNull(): MockGlasses? = glasses

  private inline fun act(name: String, block: (MockGlasses) -> Unit) {
    val g = glasses
    if (g == null) {
      log.log("mock", "$name ignored: no simulated glasses paired")
      return
    }
    runCatching { block(g) }
        .onSuccess { log.log("mock", name) }
        .onFailure { log.log("mock", "$name failed: ${it.message}") }
  }

  companion object {
    const val TEST_SERVER_PORT = 8237
  }
}
