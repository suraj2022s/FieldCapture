package app.fieldcapture

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fieldcapture.core.EventLog
import app.fieldcapture.glasses.GlassesController
import app.fieldcapture.glasses.PhotoResult
import app.fieldcapture.glasses.WalkPhase
import com.meta.wearable.dat.core.types.ThermalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Session-lifecycle edge cases driven through Mock Device Kit (docs/PLAN.md §5.3).
 *
 * Every test prints the event timeline to logcat (tag FieldCapture). The timelines are the
 * measured behaviour of the mock that docs/RESEARCH.md §5 records.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleTest {
  @get:Rule val mock = MockGlassesRule()

  private val log = EventLog()
  private lateinit var scope: CoroutineScope
  private lateinit var controller: GlassesController

  @Before
  fun setUp() {
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    controller = GlassesController(scope, log, flowOf(true))
    controller.onSdkReady()
  }

  @After
  fun tearDown() {
    controller.endWalk()
    Thread.sleep(500)
    scope.cancel()
  }

  private suspend fun awaitPhase(vararg phases: WalkPhase, timeoutMs: Long = 25_000) =
      withTimeout(timeoutMs) { controller.status.first { it.phase in phases } }

  private suspend fun startActive() {
    controller.startWalk()
    awaitPhase(WalkPhase.ACTIVE)
    withTimeout(10_000) { controller.status.first { it.framesReceived >= 5 } }
  }

  // L1
  @Test
  fun startStreamsFramesThenStopsCleanly(): Unit = runBlocking {
    startActive()
    val s = controller.status.value
    assertTrue("first frame measured", (s.firstFrameMs ?: -1) >= 0)
    val photo = controller.capturePhoto()
    log.log("test", "capturePhoto on mock -> $photo")
    controller.endWalk()
    awaitPhase(WalkPhase.ENDED)
  }

  // L4
  @Test
  fun pauseRefusesCapturesAndResumeComesBack(): Unit = runBlocking {
    startActive()
    controller.pauseWalk()
    awaitPhase(WalkPhase.PAUSED)
    Thread.sleep(1500)
    val p = controller.capturePhoto()
    assertTrue("capture while paused must not succeed: $p", p is PhotoResult.Failed)
    controller.resumeWalk()
    awaitPhase(WalkPhase.ACTIVE, timeoutMs = 30_000)
  }

  // L5
  @Test
  fun takingGlassesOffSuspendsAndPuttingThemOnResumes(): Unit = runBlocking {
    startActive()
    mock.glasses.doff()
    val s = awaitPhase(WalkPhase.SUSPENDED, WalkPhase.PAUSED, WalkPhase.RECONNECTING)
    log.log("test", "doff -> ${s.phase} (${s.guidance})")
    mock.glasses.don()
    awaitPhase(WalkPhase.ACTIVE, timeoutMs = 40_000)
  }

  // L6
  @Test
  fun foldingSuspendsAndUnfoldingResumes(): Unit = runBlocking {
    startActive()
    mock.glasses.fold()
    val s = awaitPhase(WalkPhase.SUSPENDED, WalkPhase.PAUSED, WalkPhase.RECONNECTING)
    log.log("test", "fold -> ${s.phase} (${s.guidance}) lastError=${s.lastError}")
    mock.glasses.unfold()
    mock.glasses.don()
    awaitPhase(WalkPhase.ACTIVE, timeoutMs = 40_000)
  }

  // L7: the device drops off halfway through a walk
  @Test
  fun powerLossMidWalkReconnectsTheSameWalk(): Unit = runBlocking {
    startActive()
    mock.glasses.powerOff()
    awaitPhase(WalkPhase.RECONNECTING, timeoutMs = 30_000)
    Thread.sleep(2000)
    mock.glasses.powerOn()
    mock.glasses.unfold()
    mock.glasses.don()
    awaitPhase(WalkPhase.ACTIVE, timeoutMs = 60_000)
    assertTrue("reconnect counted", controller.status.value.reconnects >= 1)
    withTimeout(10_000) { controller.status.first { it.framesReceived >= 5 } }
  }

  // L8
  @Test
  fun overheatingIsSurfacedToTheTech(): Unit = runBlocking {
    startActive()
    mock.glasses.setThermalLevel(ThermalLevel.CRITICAL)
    val s = withTimeout(20_000) { controller.status.first { it.device?.thermal == ThermalLevel.CRITICAL } }
    log.log("test", "thermal CRITICAL -> phase=${s.phase} guidance=${s.guidance} lastError=${s.lastError}")
    mock.glasses.setThermalLevel(ThermalLevel.NONE)
  }

  // L9
  @Test
  fun outdatedGlassesAppIsReportedNotLooped(): Unit = runBlocking {
    mock.glasses.services.setDATAppUpdateRequirementEnabled(true)
    controller.startWalk()
    withTimeout(30_000) {
      log.events.first { list -> list.any { it.detail.contains("DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED") } }
    }
    assertTrue(controller.status.value.phase != WalkPhase.ACTIVE)
    mock.glasses.services.setDATAppUpdateRequirementEnabled(false)
  }
}
