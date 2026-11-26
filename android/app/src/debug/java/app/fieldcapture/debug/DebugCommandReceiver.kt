package app.fieldcapture.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.fieldcapture.FieldCaptureApp
import app.fieldcapture.MainActivity
import app.fieldcapture.sync.UploadWorker
import app.fieldcapture.voice.Heard
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug builds only: lets an adb script drive a walk without anyone speaking, so the chaos runs
 * (tools/chaos_run.ps1) and the failure drill are repeatable. Spoken commands enter through the
 * same path the microphone uses (WalkController.simulateHeard), exactly as the instrumented tests do.
 *
 * Guarded by android.permission.DUMP: adb's shell user holds it, ordinary apps cannot.
 *
 *   adb shell "am broadcast -n app.fieldcapture/.debug.DebugCommandReceiver --es cmd say --es text 'capture nameplate'"
 *
 * cmd = start | end | say (text) | mic_off | mark (text) | offline | online | status | feed_test | feed_camera
 *       | feed_file (text = absolute path) | dataset_on | dataset_off
 */
class DebugCommandReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext as FieldCaptureApp
    val cmd = intent.getStringExtra("cmd") ?: return
    val text = intent.getStringExtra("text")
    app.events.log("debug_cmd", cmd + (text?.let { " \"$it\"" } ?: ""))
    if (!app.sdkReady) app.initSdk(useMockGlasses = MainActivity.USE_MOCK_GLASSES)
    when (cmd) {
      "start" -> app.walk.startJob()
      "end" -> app.walk.endJob()
      "say" -> {
        if (text.isNullOrBlank()) return
        val now = SystemClock.elapsedRealtime()
        // A recogniser result with speech starting 1.2 s earlier, as a short spoken command would be.
        app.walk.simulateHeard(Heard(listOf(text), listOf(0.9f), true, now - 1200, now, utterance.incrementAndGet()))
      }
      // Unattended runs: room speech must neither trigger commands nor end up in the log.
      // Holds until the next job start or resume, which turn the mic back on.
      "mic_off" -> app.listener.stop()
      "offline" -> app.settings.simulateOffline = true
      "online" -> {
        app.settings.simulateOffline = false
        UploadWorker.kick(app)
      }
      "status" -> {
        val g = app.glasses.status.value
        val w = app.walk.ui.value
        app.events.log(
            "debug_status",
            "job=${w.jobId} phase=${g.phase} session=${g.sessionState} stream=${g.streamState} " +
                "frames=${g.framesReceived} fps=${"%.1f".format(g.fps)} captures=${w.captures} notes=${w.notes} " +
                "pending=${app.store.pendingItems().size} offline=${app.settings.simulateOffline}",
        )
      }
      // Takes effect at the next stream start (a running mock stream keeps its feed; measured).
      // A timestamp on the phone's clock for something the script did on the PC ("route restored").
      "mark" -> Unit
      "feed_test" -> {
        app.settings.mockTestFeed = true
        app.mock.useTestFeed()
      }
      "feed_camera" -> {
        app.settings.mockTestFeed = false
        app.mock.usePhoneCamera(back = true)
      }
      // An H.265 file the PC pushed into the app's external files dir (e.g. a pipeline rehearsal
      // clip). Like feed_test it must be set before the stream starts. Not persisted.
      "feed_file" -> {
        val f = text?.let { java.io.File(it) }
        if (f == null || !f.isFile) app.events.log("debug_cmd", "feed_file: no such file $text")
        else app.mock.useVideo(android.net.Uri.fromFile(f), "file ${f.name}")
      }
      // Back office address (text) and access code (extra "code", never logged); backend_usb goes back
      // to the build default 127.0.0.1:8000 that `adb reverse` serves.
      "backend" -> {
        text?.let { app.settings.backendUrl = it }
        intent.getStringExtra("code")?.let { app.settings.accessCode = it }
        app.events.log("settings", "back office ${app.settings.backendUrl}")
        UploadWorker.kick(app)
      }
      "backend_usb" -> {
        app.settings.backendUrl = ""
        app.settings.accessCode = ""
        app.events.log("settings", "back office ${app.settings.backendUrl} (USB)")
      }
      "dataset_on" -> app.settings.datasetMode = true
      "dataset_off" -> app.settings.datasetMode = false
      else -> app.events.log("debug_cmd", "unknown cmd $cmd")
    }
  }

  private companion object {
    /** Far above the recogniser's own counter so injected utterances never collide with real ones. */
    val utterance = AtomicInteger(1_000_000)
  }
}
