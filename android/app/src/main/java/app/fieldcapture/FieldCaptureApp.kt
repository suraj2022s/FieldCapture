package app.fieldcapture

import android.app.Application
import app.fieldcapture.capture.DatasetRecorder
import app.fieldcapture.capture.FrameScorer
import app.fieldcapture.capture.TextDetector
import app.fieldcapture.core.AppSettings
import app.fieldcapture.core.EventLog
import app.fieldcapture.glasses.GlassesController
import app.fieldcapture.mock.MockGlassesController
import app.fieldcapture.store.JobStore
import app.fieldcapture.sync.BackendClient
import app.fieldcapture.sync.UploadWorker
import app.fieldcapture.voice.SpeechListener
import app.fieldcapture.voice.Speaker
import app.fieldcapture.walk.WalkController
import app.fieldcapture.walk.WalkService
import com.meta.wearable.dat.core.Wearables
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Composition root: one instance of each long-lived component, shared by the UI and the service. */
class FieldCaptureApp : Application() {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val events = EventLog()
  val settings: AppSettings by lazy { AppSettings(this) }
  val store: JobStore by lazy { JobStore(File(filesDir, "fieldcapture")) }

  val mock: MockGlassesController by lazy { MockGlassesController(this, events) }

  val scorer: FrameScorer by lazy { FrameScorer(events, { settings.thresholds }) }

  val glasses: GlassesController by lazy {
    GlassesController(
            scope = appScope,
            log = events,
            providerChanges = mock.state.map { it.enabled }.distinctUntilChanged(),
        )
        .also { g -> g.frameListener = { frame, tMs -> scorer.onFrame(frame, tMs) } }
  }

  val speaker: Speaker by lazy { Speaker(this, events) }
  val listener: SpeechListener by lazy { SpeechListener(this, events) }
  val textDetector: TextDetector by lazy { TextDetector() }

  val walk: WalkController by lazy {
    WalkController(
        context = this,
        scope = appScope,
        log = events,
        glasses = glasses,
        scorer = scorer,
        text = textDetector,
        store = store,
        listener = listener,
        speaker = speaker,
        thresholds = { settings.thresholds },
        thresholdsVersion = { settings.thresholdsVersion },
        streamYuvLayout = { settings.yuvLayout },
        usePhotoEvidence = { settings.usePhotoEvidence },
        datasetMode = { settings.datasetMode },
        dataset = DatasetRecorder(File(getExternalFilesDir(null), "dataset"), store.json),
        kickUpload = { UploadWorker.kick(this) },
        onJobActive = { active -> if (active) WalkService.start(this) else WalkService.stop(this) },
    )
  }

  @Volatile
  var sdkReady = false
    private set

  override fun onCreate() {
    super.onCreate()
    // Anything left in the outbox from a previous run (no signal, killed app) goes out when possible.
    UploadWorker.kick(this)
    watchBackOffice()
  }

  /**
   * WorkManager backs off exponentially while the back office is unreachable (10 s, 20 s, 40 s, ...) and
   * keeps waiting after it answers again. Toxiproxy run 2026-11-26: uploads sat idle 31 s after a 40 s
   * fault, and the wait doubles with every failed try (retries at 3, 15, 37, 79 s into an outage). New
   * captures don't reset it. So while something is queued and the app is alive (a walk keeps it alive),
   * ask the back office every 15 s and upload as soon as it answers.
   */
  private fun watchBackOffice() {
    appScope.launch(Dispatchers.IO) {
      val client = BackendClient(BuildConfig.BACKEND_URL, store)
      store.pending.collectLatest { n ->
        while (n > 0) {
          delay(PROBE_MS)
          if (settings.simulateOffline) continue
          if (client.ping() && UploadWorker.retryNow(this@FieldCaptureApp)) {
            events.log("upload", "back office answers; retrying now instead of waiting out the back-off")
          }
        }
      }
    }
  }

  private companion object {
    const val PROBE_MS = 15_000L
  }

  /**
   * Initialise DAT once the Android Bluetooth permission is granted (as Meta's samples do).
   * With [useMockGlasses], Mock Device Kit is brought to a streamable state before observers start,
   * so the SDK's device flows already point at the simulated pair.
   */
  fun initSdk(useMockGlasses: Boolean) {
    if (sdkReady) return
    Wearables.initialize(this).onFailure { e, _ -> events.log("dat", "initialize: ${e.description}") }
    sdkReady = true
    events.log(
        "dat",
        "SDK ready (mock glasses=$useMockGlasses, thresholds=${settings.thresholdsVersion})",
    )
    if (useMockGlasses) mock.quickSetup(testFeed = BuildConfig.DEBUG && settings.mockTestFeed)
    glasses.onSdkReady()
    // Creates the controller (it starts listening to state) and carries on a walk the OS killed.
    walk.resumeOpenJob()
  }
}
