package app.fieldcapture.walk

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import app.fieldcapture.BuildConfig
import app.fieldcapture.capture.DatasetRecorder
import app.fieldcapture.capture.FrameScorer
import app.fieldcapture.capture.ScoredFrame
import app.fieldcapture.capture.TextDetector
import app.fieldcapture.core.EventLog
import app.fieldcapture.glasses.GlassesController
import app.fieldcapture.glasses.PhotoCodec
import app.fieldcapture.glasses.PhotoResult
import app.fieldcapture.glasses.WalkPhase
import app.fieldcapture.quality.BestFrame
import app.fieldcapture.quality.Candidate
import app.fieldcapture.quality.FrameMetrics
import app.fieldcapture.quality.LumaImage
import app.fieldcapture.quality.Metrics
import app.fieldcapture.quality.NormBox
import app.fieldcapture.quality.Purpose
import app.fieldcapture.quality.QualityGate
import app.fieldcapture.quality.TextLayout
import app.fieldcapture.quality.Thresholds
import app.fieldcapture.quality.Verdict
import app.fieldcapture.store.CaptureRecord
import app.fieldcapture.store.EventRecord
import app.fieldcapture.store.EvidenceInfo
import app.fieldcapture.store.GlassesInfo
import app.fieldcapture.store.JobHeader
import app.fieldcapture.store.JobStore
import app.fieldcapture.store.NoteRecord
import app.fieldcapture.store.OcrInfo
import app.fieldcapture.store.OcrLineInfo
import app.fieldcapture.store.QualityInfo
import app.fieldcapture.store.TimingInfo
import app.fieldcapture.voice.Command
import app.fieldcapture.voice.CommandParser
import app.fieldcapture.voice.Heard
import app.fieldcapture.voice.Parsed
import app.fieldcapture.voice.SpeechListener
import app.fieldcapture.voice.Speaker
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class WalkUi(
    val jobId: String? = null,
    val room: String? = null,
    val captures: Int = 0,
    val notes: Int = 0,
    /** What the app is doing right now ("Hold still.") — mirrors the last prompt. */
    val busy: String? = null,
    val last: String? = null,
    val lastThumb: ByteArray? = null,
    val heard: String = "",
)

/**
 * Turns what the tech says into durable job data. Owns the capture loop:
 * 1. A command reaches back to frames from when the tech started speaking.
 * 2. The best frame that passes the quality gate wins. Most captures never prompt.
 * 3. Otherwise it gives one specific prompt and keeps watching; the first good frame is accepted
 *    without repeating the command.
 * 4. After [MAX_PROMPTS] prompts or [MAX_WAIT_MS] it keeps the least-bad frame and flags it. The
 *    tech is never trapped, and "capture anyway" accepts immediately.
 */
class WalkController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: EventLog,
    private val glasses: GlassesController,
    private val scorer: FrameScorer,
    private val text: TextDetector,
    private val store: JobStore,
    private val listener: SpeechListener,
    private val speaker: Speaker,
    private val thresholds: () -> Thresholds,
    private val thresholdsVersion: () -> String,
    private val streamYuvLayout: () -> PhotoCodec.YuvLayout,
    private val usePhotoEvidence: () -> Boolean,
    private val datasetMode: () -> Boolean,
    private val dataset: DatasetRecorder,
    private val kickUpload: () -> Unit,
    private val onJobActive: (Boolean) -> Unit,
) {
  private val _ui = MutableStateFlow(WalkUi())
  val ui: StateFlow<WalkUi> = _ui.asStateFlow()

  @Volatile private var job: JobHeader? = null
  private var jobStartWall = 0L
  @Volatile private var room: String? = null
  private val captureMutex = Mutex()
  @Volatile private var capturing = false
  @Volatile private var forceAccept = false
  @Volatile private var lastActedUtterance = -1
  private val history = ArrayDeque<Any>()
  private var lastCaptureRequest: Triple<Purpose, String?, String?>? = null

  val isJobActive: Boolean
    get() = job != null

  init {
    scope.launch { listener.heard.collect { onHeard(it) } }
    scope.launch { speaker.speaking.collect { listener.setMuted(it) } }
    scope.launch {
      var previous: WalkPhase? = null
      glasses.status.map { it.phase to it.guidance }.distinctUntilChanged().collect { (phase, guidance) ->
        if (job != null && phase != previous) announcePhase(previous, phase, guidance)
        previous = phase
      }
    }
  }

  // ------------------------------------------------------------------ job lifecycle

  fun startJob() {
    if (job != null) {
      speaker.say("Job already running.")
      return
    }
    val header =
        store.startJob(
            app = mapOf("app" to "FieldCapture", "version" to BuildConfig.VERSION_NAME, "dat_sdk" to "1.1.0"),
            device = mapOf("phone" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to Build.VERSION.RELEASE),
        )
    job = header
    jobStartWall = System.currentTimeMillis()
    room = null
    history.clear()
    _ui.value = WalkUi(jobId = header.jobId)
    log.log("job", "started ${header.jobId}")
    onJobActive(true)
    glasses.startWalk()
    listener.start()
    speaker.say("Job started.")
    kickUpload()
  }

  fun endJob() {
    val h = job ?: return
    job = null
    glasses.endWalk()
    listener.stop()
    val ended = store.endJob(h, timeline())
    log.log("job", "ended ${ended.jobId}")
    kickUpload()
    val u = _ui.value
    speaker.say("Job ended. ${u.captures} captures, ${u.notes} notes. Uploading when there's signal.")
    _ui.update { it.copy(busy = null, last = "Job ended") }
    onJobActive(false)
  }

  private fun timeline(): List<EventRecord> =
      log.events.value
          .filter { it.wallMs >= jobStartWall }
          .map { EventRecord(Instant.ofEpochMilli(it.wallMs).toString(), it.type, it.detail) }

  private fun announcePhase(prev: WalkPhase?, phase: WalkPhase, guidance: String?) {
    when (phase) {
      WalkPhase.RECONNECTING -> {
        speaker.failure()
        speaker.say("Glasses disconnected. Notes still work.")
      }
      WalkPhase.SUSPENDED -> speaker.say(guidance ?: "The glasses paused the camera.")
      WalkPhase.ACTIVE ->
          if (prev == WalkPhase.RECONNECTING || prev == WalkPhase.SUSPENDED) speaker.say("Glasses back.")
      WalkPhase.NEEDS_REGISTRATION -> speaker.say("Open FieldCapture to connect to Meta AI.")
      else -> Unit
    }
  }

  // ------------------------------------------------------------------ voice

  private fun onHeard(h: Heard) {
    _ui.update { it.copy(heard = h.hypotheses.firstOrNull().orEmpty()) }
    if (job == null || h.utterance == lastActedUtterance) return
    val parsed = CommandParser.parseBest(h.hypotheses, h.confidences) ?: return
    // Partial results only fire commands that can't grow more words; damage/note wait for the final.
    val oneShot =
        when (parsed.command) {
          Command.CaptureNameplate, Command.CaptureAnyway, Command.Retake, Command.PauseJob,
          Command.ResumeJob, Command.Status -> true
          else -> false
        }
    if (!h.isFinal && !oneShot) return
    lastActedUtterance = h.utterance
    val speechToCommand = h.speechStartMs?.let { h.atMs - it }
    log.log(
        "command",
        "${parsed.command} conf=${"%.2f".format(parsed.confidence)}" +
            (if (!h.isFinal) " [from partial]" else "") +
            (speechToCommand?.let { " speech->command=${it}ms" } ?: ""),
    )
    if (parsed.command == Command.CaptureAnyway) {
      if (capturing) forceAccept = true else speaker.say("Nothing to keep.")
      return
    }
    scope.launch { handle(parsed, h) }
  }

  private suspend fun handle(p: Parsed, h: Heard) {
    when (val c = p.command) {
      Command.CaptureNameplate -> capture(Purpose.NAMEPLATE, p, h, null, null, null, null)
      is Command.CaptureDamage -> {
        c.room?.let { setRoom(it) }
        capture(Purpose.DAMAGE, p, h, c.room ?: room, c.surface, c.unparsed.ifBlank { null }, null)
      }
      is Command.Note -> note(c.text, p)
      Command.Retake -> retake(p, h)
      Command.UndoLast -> undo()
      Command.Status -> status()
      Command.PauseJob -> {
        glasses.pauseWalk()
        speaker.say("Paused.")
      }
      Command.ResumeJob -> {
        glasses.resumeWalk()
        speaker.say("Resuming.")
      }
      Command.EndJob -> endJob()
      Command.StartJob -> speaker.say("Job already running.")
      Command.CaptureAnyway -> Unit
    }
  }

  private fun setRoom(r: String) {
    if (room != r) log.log("room", r)
    room = r
    _ui.update { it.copy(room = r) }
  }

  // ------------------------------------------------------------------ capture

  private class Eval(val frame: ScoredFrame, val verdict: Verdict, val ocr: TextDetector.Result?)

  private suspend fun capture(
      purpose: Purpose,
      p: Parsed,
      h: Heard,
      room: String?,
      surface: String?,
      unparsed: String?,
      replaces: String?,
  ) {
    if (capturing) {
      speaker.say("Still saving the last one.")
      return
    }
    captureMutex.withLock {
      capturing = true
      forceAccept = false
      try {
        lastCaptureRequest = Triple(purpose, room, surface)
        doCapture(purpose, p, h, room, surface, unparsed, replaces)
      } catch (e: Exception) {
        log.log("capture", "crashed: ${e.javaClass.simpleName} ${e.message}")
        speaker.failure()
        speaker.say("Capture failed. Try again.")
      } finally {
        capturing = false
        _ui.update { it.copy(busy = null) }
      }
    }
  }

  private suspend fun doCapture(
      purpose: Purpose,
      p: Parsed,
      h: Heard,
      room: String?,
      surface: String?,
      unparsed: String?,
      replaces: String?,
  ) {
    val jobHeader = job ?: return
    val label = if (purpose == Purpose.NAMEPLATE) "nameplate" else "damage"
    val st = glasses.status.value
    if (st.phase != WalkPhase.ACTIVE) {
      speaker.failure()
      speaker.say(
          when (st.phase) {
            WalkPhase.PAUSED -> "Job is paused. Say resume job."
            WalkPhase.SUSPENDED -> "${st.guidance ?: "The glasses paused the camera."} Capture not taken."
            else -> "Glasses aren't connected. Capture not taken."
          },
      )
      log.log("capture_refused", "$label: glasses ${st.phase}")
      return
    }

    _ui.update { it.copy(busy = "Capturing $label") }
    speaker.tick()
    val tCmd = SystemClock.elapsedRealtime()
    val windowStart = (h.speechStartMs ?: tCmd) - REACH_BACK_MS
    val evaluated = mutableListOf<Candidate<Eval>>()
    val seen = HashSet<Long>()
    val allSeen = mutableListOf<ScoredFrame>()
    var photoBytes: ByteArray? = null
    var prompts = 0
    var lastPromptAt = 0L
    var chosen: Eval? = null
    var override = false

    while (true) {
      val fresh = scorer.keepersSince(windowStart).filter { seen.add(it.seq) }
      allSeen += fresh
      // OCR is the slow part (~100-300 ms): only run it on the most promising nameplate frames.
      val toJudge = if (purpose == Purpose.NAMEPLATE) fresh.sortedByDescending { it.verdict.score }.take(2) else fresh
      for (sf in toJudge) {
        val e = judge(sf, purpose)
        evaluated += Candidate(e, e.verdict, sf.tMs)
      }
      chosen = BestFrame.bestPassing(evaluated)?.payload
      if (chosen != null) break
      val now = SystemClock.elapsedRealtime()
      val gaveUp = now - tCmd > MAX_WAIT_MS || (prompts >= MAX_PROMPTS && now - lastPromptAt > LAST_PROMPT_GRACE_MS)
      if ((forceAccept || gaveUp) && evaluated.isNotEmpty()) {
        chosen = BestFrame.leastBad(evaluated)?.payload
        override = true
        log.log("capture", "$label: kept least-bad frame (${if (forceAccept) "capture anyway" else "timeout"})")
        break
      }
      if (gaveUp && evaluated.isEmpty()) break
      if (evaluated.isNotEmpty() && now - tCmd > FIRST_PROMPT_AFTER_MS && now - lastPromptAt > PROMPT_GAP_MS && prompts < MAX_PROMPTS) {
        BestFrame.leastBad(evaluated)?.verdict?.prompt?.let { prompt ->
          speaker.failure()
          speaker.say(prompt)
          prompts++
          lastPromptAt = now
          _ui.update { it.copy(busy = prompt) }
          log.log("retake_prompt", "$label: $prompt (${BestFrame.leastBad(evaluated)?.verdict?.issues})")
        }
      }
      delay(POLL_MS)
    }

    val e = chosen
    if (e == null) {
      speaker.failure()
      speaker.say("No picture from the glasses. Capture not taken.")
      log.log("capture", "$label: no frames in window")
      return
    }
    val tFrame = SystemClock.elapsedRealtime()

    // Evidence: the QC'd stream frame always; a high-res still if the glasses give a good one.
    val frame = e.frame.frame
    val streamJpeg =
        withContext(Dispatchers.Default) { PhotoCodec.yuvToJpeg(frame, streamYuvLayout()) }
            ?: error("stream frame could not be encoded")
    var evidenceBytes = streamJpeg
    var evidenceW = frame.width
    var evidenceH = frame.height
    var source = "stream_frame"
    var ocr = e.ocr
    var photoLatency: Long? = null
    var photoError: String? = null
    if (usePhotoEvidence()) {
      when (val photo = glasses.capturePhoto(PHOTO_TIMEOUT_MS)) {
        is PhotoResult.Ok -> {
          photoLatency = photo.latencyMs
          photoBytes = photo.jpeg
          val pj = judgePhoto(photo, purpose)
          // The head may have moved during the transfer: only prefer the still if it also passes.
          if (pj.first.ok) {
            evidenceBytes = photo.jpeg
            evidenceW = photo.width
            evidenceH = photo.height
            source = "glasses_photo"
            if (pj.second != null) ocr = pj.second
          } else {
            photoError = "still rejected by QC: ${pj.first.issues}"
          }
        }
        is PhotoResult.Failed -> {
          photoLatency = photo.latencyMs
          photoError = photo.reason
        }
      }
    }

    val captureId = store.newId()
    val v = e.verdict
    val serial = ocr?.let { TextDetector.guessSerial(it) }
    val st2 = glasses.status.value
    val record =
        CaptureRecord(
            captureId = captureId,
            jobId = jobHeader.jobId,
            type = label,
            createdAt = store.now(),
            transcript = p.transcript,
            asrConfidence = p.confidence,
            room = room,
            surface = surface,
            unparsedSlots = unparsed,
            evidence =
                EvidenceInfo(
                    file = "$captureId.jpg",
                    width = evidenceW,
                    height = evidenceH,
                    sha256 = JobStore.sha256(evidenceBytes),
                    source = source,
                    streamFrameFile = "${captureId}_stream.jpg",
                    photoLatencyMs = photoLatency,
                    photoError = photoError,
                ),
            quality = v.toQualityInfo(prompts, override, evaluated.size),
            ocr =
                ocr?.let { r ->
                  OcrInfo(
                      engine = "mlkit-text-recognition-latin",
                      lines = r.lines.map { OcrLineInfo(it.text, listOf(it.box.left, it.box.top, it.box.right, it.box.bottom), it.confidence) },
                      ms = r.ms,
                      serialGuess = serial,
                  )
                },
            glasses =
                GlassesInfo(
                    model = st2.device?.name ?: "unknown",
                    mock = true,
                    sdk = "mwdat 1.1.0",
                    streamQuality = "${frame.width}x${frame.height}",
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    battery = st2.device?.battery,
                    thermal = st2.device?.thermal?.name,
                ),
            timing =
                TimingInfo(
                    commandAt = Instant.now().toString(),
                    speechToCommandMs = h.speechStartMs?.let { h.atMs - it },
                    commandToFrameMs = tFrame - tCmd,
                    commandToSavedMs = 0,
                ),
            replaces = replaces,
        )
    val saved =
        withContext(Dispatchers.IO) {
          val withTiming = record.copy(timing = record.timing.copy(commandToSavedMs = SystemClock.elapsedRealtime() - tCmd))
          store.saveCapture(withTiming, evidenceBytes, streamJpeg)
          store.saveEvents(jobHeader.jobId, timeline())
          withTiming
        }
    history.addLast(saved)
    kickUpload()

    if (datasetMode()) {
      // Every frame the gate saw for this capture, judged ones with their purpose-specific verdict.
      val judged = evaluated.associateBy { it.payload.frame.seq }
      val cands =
          allSeen.map { sf ->
            val j = judged[sf.seq]?.payload
            DatasetRecorder.Cand(sf, j?.verdict ?: sf.verdict, j?.ocr, sf.seq == e.frame.seq)
          }
      val photo = photoBytes
      scope.launch(Dispatchers.IO) {
        runCatching {
              dataset.record(
                  captureId, jobHeader.jobId, label, p.transcript, room, surface, saved.createdAt,
                  thresholdsVersion(), streamYuvLayout(), prompts, override, source, tCmd, cands, photo,
              )
            }
            .onSuccess { log.log("dataset", "${cands.size} frames -> ${it.absolutePath}") }
            .onFailure { log.log("dataset", "save failed: ${it.message}") }
      }
    }

    speaker.success()
    val confirmation =
        when {
          override -> "Saved, but ${v.issues.firstOrNull()?.let { issueWord(it.name) } ?: "it's not great"}. Say retake to try again."
          purpose == Purpose.NAMEPLATE -> "Nameplate saved." + (serial?.let { " Serial ends ${spell(it.takeLast(4))}." } ?: "")
          else -> "Damage saved" + listOfNotNull(room, surface).joinToString(", ", prefix = ", ").takeIf { room != null || surface != null }.orEmpty() + "."
        }
    speaker.say(confirmation)
    log.log(
        "capture",
        "$label saved ${captureId.take(8)}: source=$source ${evidenceW}x$evidenceH prompts=$prompts override=$override " +
            "frame ${saved.timing.commandToFrameMs} ms, saved ${saved.timing.commandToSavedMs} ms",
    )
    _ui.update {
      it.copy(captures = it.captures + 1, last = confirmation, lastThumb = streamJpeg, busy = null)
    }
  }

  /** Purpose-specific judgement. Nameplates are judged on their text region, found by OCR. */
  private suspend fun judge(sf: ScoredFrame, purpose: Purpose): Eval {
    if (purpose == Purpose.DAMAGE) return Eval(sf, sf.verdict, null)
    val ocr = runCatching { text.detect(sf.frame, sf.stride) }.getOrNull()
    val layout = ocr?.layout ?: TextLayout(emptyList())
    val textMetrics = withContext(Dispatchers.Default) { textRegionMetrics(sf.luma(), layout) }
    return Eval(sf, QualityGate.evaluate(sf.metrics, Purpose.NAMEPLATE, thresholds(), layout, textMetrics), ocr)
  }

  private fun textRegionMetrics(luma: LumaImage, layout: TextLayout): FrameMetrics? {
    val u = layout.union ?: return null
    val pad = 0.02
    val box =
        NormBox(
                (u.left - pad).coerceAtLeast(0.0),
                (u.top - pad).coerceAtLeast(0.0),
                (u.right + pad).coerceAtMost(1.0),
                (u.bottom + pad).coerceAtMost(1.0),
            )
            .toBox(luma)
    return if (box.width > 8 && box.height > 8) Metrics.compute(luma, box) else null
  }

  /** Quality-checks the still from capturePhoto(); returns its verdict and (for nameplates) OCR. */
  private suspend fun judgePhoto(photo: PhotoResult.Ok, purpose: Purpose): Pair<Verdict, TextDetector.Result?> =
      withContext(Dispatchers.Default) {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(photo.width, photo.height) }
        val bmp = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, opts)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val luma = LumaImage.fromArgb(px, bmp.width, bmp.height)
        val m = Metrics.compute(luma)
        if (purpose == Purpose.DAMAGE) return@withContext QualityGate.evaluate(m, Purpose.DAMAGE, thresholds()) to null
        val ocr = runCatching { text.detect(bmp) }.getOrNull()
        val layout = ocr?.layout ?: TextLayout(emptyList())
        QualityGate.evaluate(m, Purpose.NAMEPLATE, thresholds(), layout, textRegionMetrics(luma, layout)) to ocr
      }

  private fun sampleSizeFor(w: Int, h: Int): Int {
    var s = 1
    while (minOf(w, h) / (s * 2) >= 720) s *= 2
    return s
  }

  private fun Verdict.toQualityInfo(prompts: Int, override: Boolean, candidates: Int) =
      QualityInfo(
          passed = ok,
          issues = issues.map { it.name },
          sharpness = metrics.sharpness,
          tenengrad = metrics.tenengrad,
          meanLuma = metrics.meanLuma,
          darkFraction = metrics.darkFraction,
          clippedFraction = metrics.clippedFraction,
          textSharpness = textMetrics?.sharpness,
          textClippedFraction = textMetrics?.clippedFraction,
          prompts = prompts,
          override = override,
          candidatesEvaluated = candidates,
          thresholdsVersion = thresholdsVersion(),
      )

  private suspend fun retake(p: Parsed, h: Heard) {
    val last = history.lastOrNull { it is CaptureRecord } as? CaptureRecord
    val req = lastCaptureRequest
    if (last == null || req == null) {
      speaker.say("Nothing to retake.")
      return
    }
    store.markCaptureDeleted(last)
    history.remove(last)
    _ui.update { it.copy(captures = (it.captures - 1).coerceAtLeast(0)) }
    log.log("retake", "replacing ${last.captureId.take(8)}")
    capture(req.first, p, h, req.second, req.third, null, last.captureId)
  }

  private fun undo() {
    when (val last = history.removeLastOrNull()) {
      is CaptureRecord -> {
        store.markCaptureDeleted(last)
        _ui.update { it.copy(captures = (it.captures - 1).coerceAtLeast(0), last = "Removed last capture") }
        speaker.say("Removed the last capture.")
      }
      is NoteRecord -> {
        store.markNoteDeleted(last)
        _ui.update { it.copy(notes = (it.notes - 1).coerceAtLeast(0), last = "Removed last note") }
        speaker.say("Removed the last note.")
      }
      else -> speaker.say("Nothing to undo.")
    }
    kickUpload()
  }

  private fun note(textValue: String, p: Parsed) {
    val h = job ?: return
    val n =
        NoteRecord(
            noteId = store.newId(),
            jobId = h.jobId,
            createdAt = store.now(),
            transcript = p.transcript,
            text = textValue,
            room = room,
            asrConfidence = p.confidence,
        )
    store.saveNote(n)
    history.addLast(n)
    kickUpload()
    speaker.success()
    speaker.say("Noted.")
    log.log("note", "\"$textValue\" room=$room")
    _ui.update { it.copy(notes = it.notes + 1, last = "Note: $textValue") }
  }

  private fun status() {
    val u = _ui.value
    val q = store.pending.value
    speaker.say(
        listOfNotNull(
                room?.replaceFirstChar { it.uppercase() },
                "${u.captures} captures, ${u.notes} notes",
                if (q > 0) "$q waiting for signal" else "all uploaded",
            )
            .joinToString(". ") + ".",
    )
  }

  private fun issueWord(issue: String) =
      when (issue) {
        "TOO_DARK" -> "it's dark"
        "BLURRY" -> "it's blurry"
        "CUT_OFF" -> "the label is cut off"
        "TOO_FAR" -> "the label is small"
        "GLARE" -> "there's glare"
        "NO_TEXT" -> "I couldn't read a label"
        else -> "it's not great"
      }

  /** "4417" -> "4 4 1 7" so TTS reads digits, not a number. */
  private fun spell(s: String) = s.toCharArray().joinToString(" ")

  companion object {
    /** How far before speech-start a frame may come from (the tech looked, then spoke). */
    const val REACH_BACK_MS = 500L
    const val POLL_MS = 250L
    const val FIRST_PROMPT_AFTER_MS = 1_200L
    const val PROMPT_GAP_MS = 2_500L
    const val MAX_PROMPTS = 2
    const val LAST_PROMPT_GRACE_MS = 3_000L
    const val MAX_WAIT_MS = 9_000L
    const val PHOTO_TIMEOUT_MS = 6_000L
  }
}
