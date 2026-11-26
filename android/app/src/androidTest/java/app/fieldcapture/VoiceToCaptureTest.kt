package app.fieldcapture

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fieldcapture.glasses.WalkPhase
import app.fieldcapture.voice.Heard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end on the device: a recognised voice command -> quality-gated frame -> durable capture
 * -> outbox marker waiting for upload. Uses the real app graph (FieldCaptureApp) with Mock Device
 * Kit replaying the synthetic test feed; the recogniser result is injected at the same entry
 * point the microphone uses.
 */
@RunWith(AndroidJUnit4::class)
class VoiceToCaptureTest {
  @get:Rule val mock = MockGlassesRule()

  private fun heard(text: String, utterance: Int) =
      SystemClock.elapsedRealtime().let { now -> Heard(listOf(text), listOf(0.9f), true, now - 1200, now, utterance) }

  @Test
  fun spokenCommandsBecomeDurableQueuedCaptures(): Unit = runBlocking {
    val app = mock.context.applicationContext as FieldCaptureApp
    app.settings.usePhotoEvidence = true
    app.initSdk(useMockGlasses = false) // the rule already set up the simulated pair + test feed
    app.walk.startJob()
    withTimeout(30_000) { app.glasses.status.first { it.phase == WalkPhase.ACTIVE } }
    delay(2000) // let the scorer collect keeper frames

    val before = app.store.pendingItems().count { it.kind == "capture" }
    app.walk.simulateHeard(heard("capture damage, kitchen, north wall", 9001))
    withTimeout(20_000) { app.walk.ui.first { it.captures == 1 } }
    app.walk.simulateHeard(heard("note: subfloor soft by the cabinet", 9002))
    withTimeout(10_000) { app.walk.ui.first { it.notes == 1 } }

    val pending = app.store.pendingItems()
    assertEquals(before + 1, pending.count { it.kind == "capture" })
    assertTrue(pending.any { it.kind == "note" })
    assertEquals("kitchen", app.walk.ui.value.room)
    app.walk.endJob()
    assertTrue(app.store.pendingItems().any { it.kind == "finalize" })
  }

  /**
   * Fixes from the 2026-11-26 dataset walk: a damage command with no room says the room was kept, and
   * a spoken "end job" (once misheard from "enter the job") must be said twice.
   */
  @Test
  fun keptRoomIsSpokenAndSpokenEndJobNeedsConfirming(): Unit = runBlocking {
    val app = mock.context.applicationContext as FieldCaptureApp
    app.initSdk(useMockGlasses = false)
    app.walk.startJob()
    withTimeout(30_000) { app.glasses.status.first { it.phase == WalkPhase.ACTIVE } }
    delay(2000)

    app.walk.simulateHeard(heard("capture damage bathroom ceiling", 9101))
    withTimeout(20_000) { app.walk.ui.first { it.captures == 1 } }
    app.walk.simulateHeard(heard("capture damage", 9102))
    val second = withTimeout(20_000) { app.walk.ui.first { it.captures == 2 } }
    assertTrue(second.last.orEmpty(), second.last.orEmpty().contains("Room kept from before: bathroom"))

    app.walk.simulateHeard(heard("end job", 9103))
    delay(1500)
    assertTrue("one 'end job' must not end the walk", app.walk.isJobActive)
    app.walk.simulateHeard(heard("please end the job", 9104))
    withTimeout(10_000) { app.walk.ui.first { it.jobId == null } }
    assertTrue(app.store.pendingItems().any { it.kind == "finalize" })
  }
}
