package app.fieldcapture

import android.app.Application
import app.fieldcapture.core.EventLog
import app.fieldcapture.glasses.GlassesController
import app.fieldcapture.mock.MockGlassesController
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Composition root: one instance of each long-lived component, shared by the UI and services. */
class FieldCaptureApp : Application() {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val events = EventLog()

  val mock: MockGlassesController by lazy { MockGlassesController(this, events) }

  val glasses: GlassesController by lazy {
    GlassesController(
        scope = appScope,
        log = events,
        providerChanges = mock.state.map { it.enabled }.distinctUntilChanged(),
    )
  }

  @Volatile
  var sdkReady = false
    private set

  /**
   * Initialise DAT once the Android Bluetooth permission is granted (Meta's samples do the same).
   * With [useMockGlasses], Mock Device Kit is brought to a streamable state before observers start,
   * so the SDK's device flows already point at the simulated pair.
   */
  fun initSdk(useMockGlasses: Boolean) {
    if (sdkReady) return
    Wearables.initialize(this).onFailure { e, _ -> events.log("dat", "initialize: ${e.description}") }
    sdkReady = true
    events.log("dat", "SDK ready (mock glasses=$useMockGlasses)")
    if (useMockGlasses) mock.quickSetup()
    glasses.onSdkReady()
  }
}
