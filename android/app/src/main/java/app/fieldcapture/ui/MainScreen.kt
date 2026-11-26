package app.fieldcapture.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldcapture.FieldCaptureApp
import app.fieldcapture.core.AppEvent
import app.fieldcapture.glasses.GlassesStatus
import app.fieldcapture.glasses.PhotoCodec
import app.fieldcapture.glasses.PhotoResult
import app.fieldcapture.glasses.WalkPhase
import app.fieldcapture.sync.UploadWorker
import app.fieldcapture.walk.counts
import com.meta.wearable.dat.core.types.ThermalLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

interface MainActions {
  fun startJob()

  fun endJob()

  fun register()

  suspend fun takePhoto(): PhotoResult

  suspend fun saveDebugFrame(): String
}

/**
 * One screen. A tech sees the job: status in plain words, what the glasses see, counts, uploads, and
 * one button for each thing they might do. Everything for development (glasses internals, settings,
 * Mock Device Kit controls, the event log) is folded away under "Show developer tools".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(app: FieldCaptureApp, actions: MainActions) {
  val status by app.glasses.status.collectAsStateWithLifecycle()
  val mock by app.mock.state.collectAsStateWithLifecycle()
  val events by app.events.events.collectAsStateWithLifecycle()
  val walk by app.walk.ui.collectAsStateWithLifecycle()
  val listen by app.listener.state.collectAsStateWithLifecycle()
  val pending by app.store.pending.collectAsStateWithLifecycle()
  var lastPhoto by remember { mutableStateOf<PhotoResult?>(null) }
  var debugMsg by remember { mutableStateOf<String?>(null) }
  var offline by remember { mutableStateOf(app.settings.simulateOffline) }
  var photoEvidence by remember { mutableStateOf(app.settings.usePhotoEvidence) }
  var yuv by remember { mutableStateOf(app.settings.yuvLayout) }
  var dataset by remember { mutableStateOf(app.settings.datasetMode) }
  var devOpen by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val jobActive = walk.jobId != null

  LazyColumn(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { StatusBanner(status, jobActive) }

    item {
      Section("Job") {
        if (!jobActive) {
          Button(onClick = actions::startJob, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Start job", fontSize = 22.sp)
          }
          Text("Then everything is by voice: \"capture nameplate\", \"capture damage kitchen north wall\", \"note: …\", \"end job\".")
          walk.last?.let { Text(it) }
          if (pending > 0) Text("$pending waiting for signal", color = AMBER)
        } else {
          LiveView(app, status)
          Text(
              "${walk.room?.replaceFirstChar { it.uppercase() } ?: "No room yet"} · ${counts(walk.captures, walk.notes).replace(", ", " · ")}",
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
          )
          Text(if (pending == 0) "All uploaded" else "$pending waiting for signal", color = if (pending == 0) GREEN else AMBER)
          walk.busy?.let { Text(it, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
          walk.last?.let { Text(it) }
          val heard = listen.lastPartial.ifEmpty { listen.lastFinal }
          Text(
              (if (listen.listening) "Listening" else "Microphone off") + (if (heard.isNotEmpty()) " · heard \"$heard\"" else ""),
              color = MaterialTheme.colorScheme.secondary,
          )
          walk.lastThumb?.let { bytes ->
            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
              Text("Last capture", color = MaterialTheme.colorScheme.secondary)
              Image(bmp.asImageBitmap(), "Last capture", Modifier.fillMaxWidth().height(140.dp))
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One button: the thing that applies now. Resume is the filled one, because it's what a
            // paused tech needs; End job is never highlighted (gloves, attic, no undo).
            if (status.userPaused) {
              Button(onClick = { app.glasses.resumeWalk() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Resume", fontSize = 18.sp) }
            } else {
              OutlinedButton(onClick = { app.glasses.pauseWalk() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Pause", fontSize = 18.sp) }
            }
            OutlinedButton(onClick = actions::endJob, modifier = Modifier.weight(1f).height(56.dp)) { Text("End job", fontSize = 18.sp) }
          }
        }
        if (dataset) Chip("Dataset mode ON: every frame of each capture is saved", AMBER)
        if (offline) Chip("No-signal test ON: uploads are held", AMBER)
        if (status.registration?.name != "REGISTERED" && status.registration != null) {
          OutlinedButton(onClick = actions::register) { Text("Register with Meta AI") }
        }
      }
    }

    item {
      TextButton(onClick = { devOpen = !devOpen }, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(if (devOpen) "Hide developer tools" else "Show developer tools")
      }
    }

    if (devOpen) {
      item { StatusCard(status) }

      item {
        Section("Settings (debug)") {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
              offline = !offline
              app.settings.simulateOffline = offline
              app.events.log("settings", "simulated no-signal ${if (offline) "ON" else "off"}")
              if (!offline) UploadWorker.kick(app)
            }) {
              Text(if (offline) "No-signal: ON" else "No-signal: off")
            }
            OutlinedButton(onClick = {
              dataset = !dataset
              app.settings.datasetMode = dataset
              app.events.log("settings", "dataset mode ${if (dataset) "ON" else "off"}")
            }) {
              Text(if (dataset) "Dataset: ON" else "Dataset: off")
            }
            OutlinedButton(onClick = { photoEvidence = !photoEvidence; app.settings.usePhotoEvidence = photoEvidence }) {
              Text(if (photoEvidence) "Evidence: photo" else "Evidence: stream")
            }
            OutlinedButton(onClick = {
              val all = PhotoCodec.YuvLayout.entries
              yuv = all[(all.indexOf(yuv) + 1) % all.size]
              app.settings.yuvLayout = yuv
            }) { Text("YUV: ${yuv.name}") }
          }
          Mono("thresholds ${app.settings.thresholdsVersion}: ${app.settings.thresholds}")
        }
      }

      item {
        Section("Measure (debug)") {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { lastPhoto = actions.takePhoto() } }) { Text("capturePhoto()") }
            OutlinedButton(onClick = { scope.launch { debugMsg = actions.saveDebugFrame() } }) { Text("Save raw frame") }
          }
          debugMsg?.let { Mono(it) }
          when (val p = lastPhoto) {
            is PhotoResult.Ok -> {
              Mono("photo ${p.width}x${p.height} ${p.sourceFormat} ${p.jpeg.size / 1024} KB in ${p.latencyMs} ms")
              val bmp = remember(p) { BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size) }
              if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Last captured photo",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
              }
            }
            is PhotoResult.Failed -> Mono("photo failed: ${p.reason} (${p.latencyMs} ms)")
            null -> Unit
          }
        }
      }

      item {
        Section("Mock glasses (Mock Device Kit)") {
          Mono(
              "enabled=${mock.enabled} paired=${mock.paired} power=${mock.poweredOn} " +
                  "unfolded=${mock.unfolded} donned=${mock.donned}\nfeed=${mock.feed}" +
                  (mock.testServerPort?.let { "  test-server=:$it" } ?: ""),
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.mock.quickSetup() }) { Text("Quick setup") }
            OutlinedButton(onClick = { app.mock.powerOff() }) { Text("Power off") }
            OutlinedButton(onClick = { app.mock.powerOn() }) { Text("Power on") }
            OutlinedButton(onClick = { app.mock.doff() }) { Text("Doff") }
            OutlinedButton(onClick = { app.mock.don() }) { Text("Don") }
            OutlinedButton(onClick = { app.mock.fold() }) { Text("Fold") }
            OutlinedButton(onClick = { app.mock.unfold() }) { Text("Unfold") }
            OutlinedButton(onClick = { app.mock.battery(5) }) { Text("Battery 5%") }
            OutlinedButton(onClick = { app.mock.battery(80) }) { Text("Battery 80%") }
            OutlinedButton(onClick = { app.mock.thermal(ThermalLevel.CRITICAL) }) { Text("Overheat") }
            OutlinedButton(onClick = { app.mock.thermal(ThermalLevel.NONE) }) { Text("Cool") }
            OutlinedButton(onClick = { app.mock.usePhoneCamera(back = true) }) { Text("Back cam") }
            OutlinedButton(onClick = { app.mock.usePhoneCamera(back = false) }) { Text("Front cam") }
            OutlinedButton(onClick = { app.mock.useTestFeed() }) { Text("Test feed") }
          }
        }
      }

      item { Text("Event log", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 16.dp)) }
      items(events.asReversed().take(80)) { EventRow(it) }
    }
  }
}

/** What the phase means to the person holding the phone, in their words. */
private fun plainStatus(phase: WalkPhase, jobActive: Boolean): Pair<String, Color> =
    when {
      !jobActive && (phase == WalkPhase.IDLE || phase == WalkPhase.ENDED) -> "Ready" to SLATE
      else ->
          when (phase) {
            WalkPhase.ACTIVE -> "Recording" to Color(0xFF2E7D32)
            WalkPhase.PAUSED -> "Paused" to BANNER_AMBER
            WalkPhase.SUSPENDED -> "Glasses off" to BANNER_AMBER
            WalkPhase.RECONNECTING -> "Reconnecting…" to RED
            WalkPhase.CONNECTING -> "Connecting…" to SLATE
            WalkPhase.WAITING_FOR_GLASSES -> "Waiting for the glasses" to SLATE
            WalkPhase.NEEDS_REGISTRATION -> "Connect to Meta AI" to RED
            WalkPhase.NEEDS_PERMISSION -> "Allow camera access" to RED
            WalkPhase.IDLE, WalkPhase.ENDED -> "Ready" to SLATE
          }
    }

@Composable
private fun StatusBanner(s: GlassesStatus, jobActive: Boolean) {
  val (title, color) = plainStatus(s.phase, jobActive)
  val detail =
      s.guidance?.removePrefix("$title. ") // "Paused" + "Paused. Say ..." read twice
          ?: when {
            !jobActive -> "Tap Start job, then talk."
            s.phase == WalkPhase.ACTIVE -> "Say a command."
            else -> null
          }
  // Read at a glance in daylight: dark text on amber (white on amber is ~2:1), and full white elsewhere
  // (the 80% white app name measured 3.91:1 on green; Accessibility Test Framework, 2026-11-26).
  val onColor = if (color == BANNER_AMBER) Color(0xFF1B1B1B) else Color.White
  Column(Modifier.fillMaxWidth().background(color).padding(16.dp)) {
    Text("FieldCapture", fontSize = 14.sp, color = onColor)
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = onColor)
    detail?.let { Text(it, fontSize = 16.sp, color = onColor) }
  }
}

private class Preview(val bitmap: ImageBitmap?, val ageMs: Long)

/**
 * What the glasses see, ~4 times a second: the newest stream frame, half size. Sampled from the ring
 * buffer's copy, so the capture path is untouched, and only while the screen is visible
 * (collectAsStateWithLifecycle stops below STARTED).
 */
@Composable
private fun LiveView(app: FieldCaptureApp, status: GlassesStatus) {
  val frames = remember {
    flow {
          while (true) {
            emit(renderPreview(app))
            delay(PREVIEW_INTERVAL_MS)
          }
        }
        .flowOn(Dispatchers.Default)
  }
  val preview by frames.collectAsStateWithLifecycle(initialValue = null)
  Box(
      Modifier.fillMaxWidth().height(300.dp).background(Color.Black, RoundedCornerShape(8.dp)),
      contentAlignment = Alignment.Center,
  ) {
    val p = preview
    p?.bitmap?.let { Image(it, "What the glasses see", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    val stale = p == null || p.ageMs > STALE_MS
    if (stale) {
      val why =
          when (status.phase) {
            WalkPhase.PAUSED -> "Paused"
            WalkPhase.SUSPENDED -> "Glasses off"
            WalkPhase.RECONNECTING, WalkPhase.CONNECTING, WalkPhase.WAITING_FOR_GLASSES -> "Connecting to the glasses…"
            else -> "No picture from the glasses"
          }
      Text(why, color = Color.White, fontSize = 18.sp, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp))
    }
  }
}

private fun renderPreview(app: FieldCaptureApp): Preview? {
  val f = app.glasses.frames.latest() ?: return null
  val age = SystemClock.elapsedRealtime() - f.tMs
  // Full stream resolution: the phone-camera feed is only 480x640 (Mock Device Kit), and halving it
  // made the view too blurry to aim with (the user, 2026-11-26).
  val jpeg = PhotoCodec.yuvToJpeg(f, app.settings.yuvLayout, quality = 85) ?: return Preview(null, age)
  val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
  return Preview(bmp?.asImageBitmap(), age)
}

@Composable
private fun Chip(text: String, color: Color) {
  Text(text, color = Color.Black, modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun StatusCard(s: GlassesStatus) {
  Section("Glasses") {
    val d = s.device
    Mono(
        buildString {
          appendLine("phase: ${s.phase}   registration: ${s.registration ?: "-"}")
          appendLine("session: ${s.sessionState ?: "-"}   stream: ${s.streamState ?: "-"}")
          if (d != null) {
            appendLine("${d.name}  link=${d.link} don=${d.don} hinge=${d.hinge}")
            appendLine("battery=${d.battery}%  thermal=${d.thermal}  compat=${d.compatibility}")
          } else {
            appendLine("no glasses visible")
          }
          appendLine(
              "frames=${s.framesReceived}  fps=${"%.1f".format(s.fps)}  ${s.frameWidth}x${s.frameHeight} (${s.frameBytes} B)",
          )
          append("first frame=${s.firstFrameMs?.let { "$it ms" } ?: "-"}  reconnects=${s.reconnects}")
          s.lastError?.let { append("\nlast error: $it") }
        },
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
      shape = RoundedCornerShape(12.dp),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
      content()
    }
  }
}

@Composable
private fun Mono(text: String) {
  Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun EventRow(e: AppEvent) {
  Row(Modifier.padding(horizontal = 16.dp)) {
    Text(
        "${timeFmt.format(Date(e.wallMs))} ${e.type}: ${e.detail}",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = Color(0xFFB0BEC5),
    )
  }
}

private val GREEN = Color(0xFF81C784)
private val AMBER = Color(0xFFFFB74D)
private val RED = Color(0xFFC62828)
private val SLATE = Color(0xFF37474F)
private val BANNER_AMBER = Color(0xFFF9A825)
private const val PREVIEW_INTERVAL_MS = 250L
private const val STALE_MS = 1_500L
