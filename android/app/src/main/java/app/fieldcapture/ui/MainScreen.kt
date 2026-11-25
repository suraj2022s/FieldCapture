package app.fieldcapture.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.meta.wearable.dat.core.types.ThermalLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

interface MainActions {
  fun startJob()

  fun endJob()

  fun register()

  suspend fun takePhoto(): PhotoResult

  suspend fun saveDebugFrame(): String
}

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
  val scope = rememberCoroutineScope()

  LazyColumn(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { PhaseBanner(status) }

    item {
      Section("Job") {
        if (walk.jobId == null) {
          Button(onClick = actions::startJob, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Start job", fontSize = 22.sp)
          }
          Text("Then everything is by voice: \"capture nameplate\", \"capture damage kitchen north wall\", \"note: …\", \"end job\".")
        } else {
          Text(
              "${walk.room?.replaceFirstChar { it.uppercase() } ?: "No room yet"} · ${walk.captures} captures · ${walk.notes} notes",
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
          )
          Text(if (pending == 0) "All uploaded" else "$pending waiting for signal", color = if (pending == 0) Color(0xFF81C784) else Color(0xFFFFB74D))
          walk.busy?.let { Text(it, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
          walk.last?.let { Text(it) }
          Mono("mic ${if (listen.listening) "listening" else "idle"} (${listen.engine}) heard: \"${listen.lastPartial.ifEmpty { listen.lastFinal }}\"")
          walk.lastThumb?.let { bytes ->
            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) Image(bmp.asImageBitmap(), "Last capture", Modifier.fillMaxWidth().height(200.dp))
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.glasses.pauseWalk() }) { Text("Pause") }
            OutlinedButton(onClick = { app.glasses.resumeWalk() }) { Text("Resume") }
            Button(onClick = actions::endJob) { Text("End job") }
          }
        }
        if (status.registration?.name != "REGISTERED" && status.registration != null) {
          OutlinedButton(onClick = actions::register) { Text("Register with Meta AI") }
        }
      }
    }

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
        }
      }
    }

    item { Text("Event log", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 16.dp)) }
    items(events.asReversed().take(80)) { EventRow(it) }
  }
}

@Composable
private fun PhaseBanner(s: GlassesStatus) {
  val color =
      when (s.phase) {
        WalkPhase.ACTIVE -> Color(0xFF2E7D32)
        WalkPhase.PAUSED, WalkPhase.SUSPENDED -> Color(0xFFF9A825)
        WalkPhase.RECONNECTING, WalkPhase.NEEDS_REGISTRATION -> Color(0xFFC62828)
        else -> Color(0xFF37474F)
      }
  Column(Modifier.fillMaxWidth().background(color).padding(16.dp)) {
    Text("FieldCapture", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
    Text(s.phase.name.replace('_', ' '), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
    s.guidance?.let { Text(it, fontSize = 16.sp, color = Color.White) }
  }
}

@Composable
private fun StatusCard(s: GlassesStatus) {
  Section("Glasses") {
    val d = s.device
    Mono(
        buildString {
          appendLine("registration: ${s.registration ?: "-"}")
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
