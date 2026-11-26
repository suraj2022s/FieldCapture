package app.fieldcapture.glasses

import android.os.SystemClock
import app.fieldcapture.core.EventLog
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.CameraState
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.ThermalLevel
import java.util.Collections
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** App-level walk state. The job stays open in every phase except ENDED. */
enum class WalkPhase {
  IDLE,
  NEEDS_REGISTRATION,
  /** The glasses camera permission isn't granted in Meta AI (checked before every session). */
  NEEDS_PERMISSION,
  WAITING_FOR_GLASSES,
  CONNECTING,
  ACTIVE,
  /** The tech said "pause job". */
  PAUSED,
  /** The glasses paused us: taken off, folded, too hot, cap-touch tap... */
  SUSPENDED,
  /** The glasses dropped off mid-walk; we are re-creating the session. */
  RECONNECTING,
  ENDED,
}

data class DeviceSnapshot(
    val id: String,
    val name: String,
    val link: LinkState,
    val don: DonState,
    val hinge: HingeState,
    val battery: Int,
    val thermal: ThermalLevel,
    val compatibility: DeviceCompatibility,
)

data class GlassesStatus(
    val phase: WalkPhase = WalkPhase.IDLE,
    val registration: RegistrationState? = null,
    val sessionState: DeviceSessionState? = null,
    val streamState: StreamState? = null,
    val device: DeviceSnapshot? = null,
    val framesReceived: Long = 0,
    val fps: Double = 0.0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameBytes: Int = 0,
    val firstFrameMs: Long? = null,
    val reconnects: Int = 0,
    /** One short sentence the tech should hear/see about the glasses, if any. */
    val guidance: String? = null,
    val lastError: String? = null,
    /** The tech asked for a pause (the session may still be getting there): the screen offers Resume. */
    val userPaused: Boolean = false,
)

sealed interface PhotoResult {
  data class Ok(
      val jpeg: ByteArray,
      val width: Int,
      val height: Int,
      val sourceFormat: String,
      val latencyMs: Long,
  ) : PhotoResult

  data class Failed(val reason: String, val latencyMs: Long) : PhotoResult
}

/**
 * Owns the DAT device session for one walk-through and keeps it alive.
 *
 * Design (see docs/PLAN.md §5 and docs/RESEARCH.md §2–3):
 * - A supervisor loop creates a [DeviceSession] on a *named* device ([SpecificDeviceSelector];
 *   building an AutoDeviceSelector at tap time races and fails NO_ELIGIBLE_DEVICE).
 * - The camera permission is checked before every session (a resumed or reconnecting walk never
 *   went through the Activity's permission flow).
 * - `state`/`errors` collectors are subscribed before `start()` (start is fire-and-forget, and
 *   its failures only arrive on `errors`); same for the stream's flows before `stream.start()`.
 * - The camera is attached once, at STARTED; PAUSED -> STARTED keeps it. A failed `addCamera`
 *   or `stream.start()` ends the session so the loop retries, instead of idling with no camera.
 * - STOPPED is terminal: the loop makes a new session, same walk, with back-off.
 * - A stream that stops while its camera is still started is started again (the camera can't be
 *   reused once stopped, but its stream can).
 * - A watchdog ends a session stuck in STARTING (Mock Device Kit issue #237), and link loss or an
 *   unexpected camera stop ends the session so it can be rebuilt instead of hanging.
 * - pause()/resume() only take effect once the device confirms; a "resume job" that arrives before
 *   the pause settled is re-issued when PAUSED is observed.
 *
 * These rules follow Meta's DAT skills (dat-conventions, session-lifecycle, camera-streaming) and
 * the DAT docs ("Handle stream stops and errors"), checked against this code on 2026-11-25.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesController(
    private val scope: CoroutineScope,
    private val log: EventLog,
    /** Re-subscribe to the SDK's device/registration flows when Mock Device Kit is switched on/off. */
    private val providerChanges: Flow<Boolean>,
    private val streamConfig: StreamConfiguration =
        StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15),
) {
  private val _status = MutableStateFlow(GlassesStatus())
  val status: StateFlow<GlassesStatus> = _status.asStateFlow()

  private val _alerts = MutableSharedFlow<String>(extraBufferCapacity = 8)
  /**
   * Warnings worth saying out loud that don't change the walk phase (heat, battery, power limit).
   * Phase changes are announced by WalkController. A tech wearing glasses never looks at the phone,
   * so a banner alone isn't enough (UI test 2026-11-26: overheat and 5% battery were silent).
   */
  val alerts: SharedFlow<String> = _alerts.asSharedFlow()
  private val lastAlertAt = HashMap<String, Long>()

  private fun alert(message: String) {
    val now = SystemClock.elapsedRealtime()
    synchronized(lastAlertAt) {
      val last = lastAlertAt[message]
      if (last != null && now - last < ALERT_REPEAT_MS) return
      lastAlertAt[message] = now
    }
    log.log("alert", message)
    _alerts.tryEmit(message)
  }

  val frames = FrameRingBuffer()

  /** Called on the frame thread for every raw frame (quality scoring hooks in here). */
  @Volatile var frameListener: ((VideoFrame, Long) -> Unit)? = null

  @Volatile private var session: DeviceSession? = null
  @Volatile private var camera: Camera? = null
  @Volatile private var userPaused = false
    set(value) {
      field = value
      _status.update { it.copy(userPaused = value) }
    }
  /** We called pause() and the session hasn't come back to STARTED since (tells our pause from the device's). */
  @Volatile private var ourPause = false
  /** The glasses refused the camera stream (Meta AI permission): wait for the grant before retrying. */
  @Volatile private var permissionDenied = false
  @Volatile private var cameraStarted = false
  @Volatile private var streamedOnce = false
  @Volatile private var streamRestarts = 0
  private var supervisor: Job? = null
  private var observersStarted = false
  private val cameraJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf())
  private val captureMutex = Mutex()
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

  // frame stats (frame thread only)
  @Volatile private var sessionStartMs = 0L
  @Volatile private var firstFrameAt: Long? = null
  private var frameCount = 0L
  private var fpsWindowStart = 0L
  private var fpsWindowFrames = 0
  private var lastStatusPush = 0L

  // ---------------------------------------------------------------- observation

  /** Start mirroring registration and device state. Call after Wearables.initialize. */
  fun onSdkReady() {
    if (observersStarted) return
    observersStarted = true
    scope.launch {
      providerChanges
          .flatMapLatest { Wearables.registrationState }
          .collect { st ->
            if (_status.value.registration != st) log.log("registration", st.name)
            _status.update { it.copy(registration = st) }
          }
    }
    scope.launch {
      var previous: DeviceSnapshot? = null
      providerChanges
          .flatMapLatest { deviceReadings() }
          .collect { list ->
            val best = list.minByOrNull { rank(it.second) }
            val snap = best?.let { (id, d) -> d.toSnapshot(id) }
            logDeviceChanges(previous, snap)
            onWearChange(previous, snap)
            previous = snap
            _status.update { it.copy(device = snap) }
          }
    }
  }

  private fun deviceReadings(): Flow<List<Pair<DeviceIdentifier, Device>>> =
      Wearables.devices.flatMapLatest { ids ->
        val flows = ids.mapNotNull { id -> Wearables.devicesMetadata[id]?.map { id to it } }
        if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
      }

  /** Worn + connected first, then connected. */
  private fun rank(d: Device): Int =
      when {
        d.linkState == LinkState.CONNECTED && d.donState == DonState.DONNED -> 0
        d.linkState == LinkState.CONNECTED -> 1
        else -> 9
      }

  private fun Device.toSnapshot(id: DeviceIdentifier) =
      DeviceSnapshot(
          id = id.toString(),
          name = name.ifBlank { "Meta glasses" },
          link = linkState,
          don = donState,
          hinge = hingeState,
          battery = batteryLevel,
          thermal = thermalLevel,
          compatibility = compatibility,
      )

  private fun logDeviceChanges(old: DeviceSnapshot?, new: DeviceSnapshot?) {
    if (new == null) {
      if (old != null) log.log("device", "no glasses visible")
      return
    }
    if (old == null || old.id != new.id) {
      log.log(
          "device",
          "${new.name}: link=${new.link} don=${new.don} hinge=${new.hinge} " +
              "battery=${new.battery} thermal=${new.thermal} compat=${new.compatibility}",
      )
      return
    }
    if (old.link != new.link) log.log("device", "link ${old.link} -> ${new.link}")
    if (old.don != new.don) log.log("device", "don ${old.don} -> ${new.don}")
    if (old.hinge != new.hinge) log.log("device", "hinge ${old.hinge} -> ${new.hinge}")
    if (old.thermal != new.thermal) log.log("device", "thermal ${old.thermal} -> ${new.thermal}")
    if (old.battery != new.battery && (new.battery <= 20 || old.battery <= 20)) {
      log.log("device", "battery ${old.battery}% -> ${new.battery}%")
    }
    // 0 is "not reported yet" (every device starts there), not an empty battery.
    if (new.battery in 1..LOW_BATTERY_PCT && old.battery > LOW_BATTERY_PCT) {
      alert("Glasses battery at ${new.battery} percent.")
    }
    if (old.compatibility != new.compatibility) {
      log.log("device", "compatibility ${old.compatibility} -> ${new.compatibility}")
      // Otherwise the tech only hears "Glasses disconnected" on a loop (scenario test 2026-11-26).
      when (new.compatibility) {
        DeviceCompatibility.DEVICE_UPDATE_REQUIRED -> alert("The glasses need a software update. Update them in the Meta AI app.")
        DeviceCompatibility.SDK_UPDATE_REQUIRED -> alert("This FieldCapture version is too old for these glasses. Update FieldCapture.")
        else -> Unit
      }
    }
  }

  /**
   * Glasses off (or folded) = no capturing, whatever the stream does. On real glasses the SDK
   * pauses the stream on doff; Mock Device Kit does not (measured: the stream keeps STREAMING
   * after doff()), so the app reads the device's own don/hinge state instead. A capture taken
   * while the glasses dangle from a collar would be evidence of the floor.
   */
  private fun glassesOff(): Boolean =
      _status.value.device?.let { it.don == DonState.DOFFED || it.hinge == HingeState.CLOSED } ?: false

  private fun onWearChange(old: DeviceSnapshot?, new: DeviceSnapshot?) {
    if (supervisor?.isActive != true || new == null) return
    val offNow = new.don == DonState.DOFFED || new.hinge == HingeState.CLOSED
    val offBefore = old != null && (old.don == DonState.DOFFED || old.hinge == HingeState.CLOSED)
    val phase = _status.value.phase
    if (offNow && !offBefore && (phase == WalkPhase.ACTIVE || phase == WalkPhase.CONNECTING)) {
      setPhase(
          WalkPhase.SUSPENDED,
          if (new.hinge == HingeState.CLOSED) "Glasses folded. Unfold them to keep capturing."
          else "Glasses are off. Put them on to keep capturing.",
      )
    } else if (!offNow && offBefore && phase == WalkPhase.SUSPENDED && !userPaused) {
      if (_status.value.streamState == StreamState.STREAMING) setPhase(WalkPhase.ACTIVE)
    }
  }

  // ---------------------------------------------------------------- walk control

  fun startWalk() {
    if (supervisor?.isActive == true) {
      log.log("walk", "already running")
      return
    }
    userPaused = false
    permissionDenied = false
    _status.update {
      it.copy(phase = WalkPhase.WAITING_FOR_GLASSES, reconnects = 0, guidance = null, lastError = null)
    }
    log.log("walk", "started")
    supervisor = scope.launch { superviseWalk() }
  }

  /** "pause job": DAT 1.1 DeviceSession.pause(). The job and the session stay open. */
  fun pauseWalk() {
    val s = session
    userPaused = true
    log.log("walk", "pause requested (session=${s?.state?.value})")
    setPhase(WalkPhase.PAUSED, "Paused. Say \"resume job\" to continue.")
    s?.let { reconcilePause(it, userAsked = true) }
  }

  /** "resume job": DAT 1.1 DeviceSession.resume(). */
  fun resumeWalk() {
    val s = session
    userPaused = false
    log.log("walk", "resume requested (session=${s?.state?.value})")
    if (s == null) setPhase(WalkPhase.RECONNECTING, "Reconnecting to the glasses.")
    else reconcilePause(s, userAsked = true)
  }

  /**
   * Moves the session one settled step towards the pause state the tech asked for ([userPaused]).
   * pause() only on a STARTED session whose stream is STREAMING; resume() only once our pause has
   * reached the stream. A request that lands mid-transition is applied by the state change that ends
   * it, which calls this again ("call pause() or resume() again once it settles": DAT docs).
   * UI test 2026-11-26: taps 0.1 s apart used to resume() the instant the session said PAUSED, or pause
   * a session whose camera was still starting. Mock Device Kit then stopped the camera, and the walk
   * reconnected, in an endless loop while paused.
   */
  private fun reconcilePause(s: DeviceSession, userAsked: Boolean = false) {
    val ss = s.state.value
    val stream = _status.value.streamState
    if (userPaused && ss == DeviceSessionState.STARTED && stream == StreamState.STREAMING) {
      ourPause = true
      s.pause()
    } else if (!userPaused && ss == DeviceSessionState.PAUSED && (stream == StreamState.PAUSED || (userAsked && !ourPause))) {
      // Our own pause: wait until it reached the stream. The device's pause: resume when the tech asks.
      s.resume()
    }
  }

  /** "end job": stop camera and session; the supervisor's finally-block does the teardown. */
  fun endWalk() {
    val job = supervisor ?: return
    supervisor = null
    log.log("walk", "end requested")
    scope.launch {
      job.cancelAndJoin()
      _status.update {
        it.copy(phase = WalkPhase.ENDED, sessionState = null, streamState = null, fps = 0.0, guidance = null)
      }
      log.log("walk", "ended")
    }
  }

  val isWalking: Boolean
    get() = supervisor?.isActive == true

  private fun setPhase(phase: WalkPhase, guidance: String? = null) {
    val before = _status.value.phase
    _status.update { it.copy(phase = phase, guidance = guidance) }
    if (before != phase) log.log("phase", "$before -> $phase${guidance?.let { " ($it)" } ?: ""}")
  }

  // ---------------------------------------------------------------- supervisor

  private suspend fun superviseWalk() {
    var attempt = 0
    while (currentCoroutineContext().isActive) {
      if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
        setPhase(WalkPhase.NEEDS_REGISTRATION, "Connect FieldCapture to Meta AI first.")
        Wearables.registrationState.first { it == RegistrationState.REGISTERED }
      }
      awaitCameraPermission()
      if (attempt == 0) setPhase(WalkPhase.WAITING_FOR_GLASSES, "Waiting for the glasses.")
      else setPhase(WalkPhase.RECONNECTING, "Glasses disconnected. Reconnecting.")

      val deviceId = awaitUsableDevice()
      val ranMs = runSession(deviceId)

      // A session that ran for a while resets the back-off; quick failures back off harder.
      attempt = if (ranMs > STABLE_SESSION_MS) 1 else attempt + 1
      _status.update { it.copy(reconnects = it.reconnects + 1) }
      val backoff = (500L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
      log.log("reconnect", "session ended after ${ranMs} ms; next attempt in $backoff ms (#$attempt)")
      // A refused camera isn't a disconnect: keep telling the tech what to fix.
      if (!permissionDenied) setPhase(WalkPhase.RECONNECTING, "Glasses disconnected. Reconnecting.")
      // Wake early when the glasses become wearable again (connected, unfolded, on), so a tech
      // doesn't wait out the back-off after putting them back on (UI test 2026-11-26: 3.7 s idle).
      // Only a change counts: glasses that already look ready but keep failing still back off.
      val woke =
          withTimeoutOrNull(backoff) {
            _status
                .map { st -> st.device?.let { it.link == LinkState.CONNECTED && it.don != DonState.DOFFED && it.hinge != HingeState.CLOSED } == true }
                .distinctUntilChanged()
                .drop(1)
                .first { it }
          }
      if (woke != null) log.log("reconnect", "glasses ready again; retrying now")
    }
  }

  /**
   * Streaming needs the glasses camera permission (granted in Meta AI). Granting it needs an
   * Activity, so here we only check, say what to do, and re-check until it is granted. A failed
   * check (we can't tell) lets the session try; the stream then reports PERMISSIONS_DENIED.
   */
  private suspend fun awaitCameraPermission() {
    var asked = false
    var unknown = 0
    while (true) {
      val granted =
          Wearables.checkPermissionStatus(Permission.CAMERA)
              .fold(
                  onSuccess = { it == PermissionStatus.Granted },
                  onFailure = { e, _ ->
                    // After the stream was refused, "can't tell" means wait, not try again (each try
                    // would announce the same denial), but not forever: the stream itself will say.
                    unknown++
                    val tryAnyway = !permissionDenied || unknown >= UNKNOWN_PERMISSION_CHECKS
                    log.log("permission", "camera check failed: ${e.description}; ${if (tryAnyway) "trying anyway" else "waiting"}")
                    tryAnyway
                  },
              )
      if (granted) {
        if (permissionDenied) log.log("permission", "glasses camera granted again (or can't tell for ${unknown * PERMISSION_RECHECK_MS / 1000} s)")
        permissionDenied = false
        return
      }
      if (!asked) {
        log.log("permission", "glasses camera not granted")
        setPhase(WalkPhase.NEEDS_PERMISSION, "Allow camera access for FieldCapture in Meta AI.")
        asked = true
      }
      delay(PERMISSION_RECHECK_MS)
    }
  }

  private suspend fun awaitUsableDevice(): DeviceIdentifier =
      deviceReadings()
          .map { list ->
            list.filter { it.second.linkState == LinkState.CONNECTED }.minByOrNull { rank(it.second) }?.first
          }
          .filterNotNull()
          .first()

  /** Runs one DeviceSession until it stops. Returns how long it lived (ms). */
  private suspend fun runSession(deviceId: DeviceIdentifier): Long = coroutineScope {
    val t0 = SystemClock.elapsedRealtime()
    val created = Wearables.createSession(SpecificDeviceSelector(deviceId))
    val s = created.getOrNull()
    if (s == null) {
      val err = created.errorOrNull()
      log.log("session", "createSession failed: ${err?.name} ${err?.description ?: ""}")
      err?.let { onSessionError(it) }
      return@coroutineScope 0L
    }
    session = s
    sessionStartMs = t0
    setPhase(WalkPhase.CONNECTING, "Connecting to the glasses.")
    log.log("session", "created on $deviceId")

    val jobs = mutableListOf<Job>()
    val sessionScope = this
    // Failures of start() arrive here, not as a return value. UNDISPATCHED subscribes right now,
    // on this thread; a plain launch only schedules it, and an early error could be missed.
    jobs += launch(start = CoroutineStart.UNDISPATCHED) { s.errors.collect { onSessionError(it) } }
    // Watchdog: Mock Device Kit sessions can hang in STARTING with no error (issue #237).
    jobs += launch {
      delay(STARTING_WATCHDOG_MS)
      val st = s.state.value
      if (st == DeviceSessionState.IDLE || st == DeviceSessionState.STARTING) {
        log.log("watchdog", "session stuck in $st for ${STARTING_WATCHDOG_MS / 1000}s; recreating")
        s.stop()
      }
    }
    // Link loss: if the device stays disconnected, end the session so the loop can rebuild it.
    jobs += launch {
      val meta = Wearables.devicesMetadata[deviceId] ?: return@launch
      meta
          .map { it.linkState == LinkState.CONNECTED }
          .distinctUntilChanged()
          .collectLatest { connected ->
            if (!connected) {
              delay(LINK_LOSS_GRACE_MS)
              log.log("link", "glasses link lost for ${LINK_LOSS_GRACE_MS} ms; ending session")
              s.stop()
            }
          }
    }

    try {
      val states =
          launch(start = CoroutineStart.UNDISPATCHED) {
            s.state
                .transformWhile {
                  emit(it)
                  it != DeviceSessionState.STOPPED
                }
                .collect { st -> onSessionState(sessionScope, s, st) }
          }
      s.start()
      states.join()
    } finally {
      jobs.forEach { it.cancel() }
      synchronized(cameraJobs) {
        cameraJobs.forEach { it.cancel() }
        cameraJobs.clear()
      }
      camera?.let { runCatching { it.stop() } }
      camera = null
      runCatching { s.stop() }
      session = null
      frames.clear()
      _status.update { it.copy(sessionState = DeviceSessionState.STOPPED, streamState = null, fps = 0.0) }
      log.log("session", "ended after ${SystemClock.elapsedRealtime() - t0} ms")
    }
    SystemClock.elapsedRealtime() - t0
  }

  private fun onSessionState(scope: CoroutineScope, s: DeviceSession, st: DeviceSessionState) {
    _status.update { it.copy(sessionState = st) }
    log.log("session_state", st.name)
    when (st) {
      DeviceSessionState.STARTED -> {
        ourPause = false
        // Capabilities can only be attached at STARTED, and only once per session.
        if (camera == null) scope.attachCamera(s)
        // A pause wanted while the session (re)started waits for STREAMING (see reconcilePause).
        reconcilePause(s)
      }
      DeviceSessionState.PAUSED -> {
        when {
          userPaused -> setPhase(WalkPhase.PAUSED, "Paused. Say \"resume job\" to continue.")
          // "resume job" came while our pause was settling: resume once the stream has paused. If the
          // stream never says so, resume anyway after a moment rather than stay paused.
          ourPause -> {
            log.log("walk", "resume asked for before the pause settled; resuming once it has")
            reconcilePause(s)
            scope.launch {
              delay(PAUSE_SETTLE_MS)
              if (!userPaused && s.state.value == DeviceSessionState.PAUSED) s.resume()
            }
          }
          else -> setPhase(WalkPhase.SUSPENDED, _status.value.guidance ?: "The glasses paused the camera.")
        }
      }
      else -> Unit
    }
  }

  private fun CoroutineScope.attachCamera(s: DeviceSession) {
    val result = s.addCamera(streamConfig)
    val cam = result.getOrNull()
    if (cam == null) {
      // A STARTED session with no camera would sit in CONNECTING forever. End it; the loop retries.
      val e = result.errorOrNull()
      log.log("camera", "attach failed: ${e?.name} ${e?.description ?: ""}; ending session to retry")
      _status.update { it.copy(lastError = e?.name, guidance = "Couldn't start the glasses camera. Retrying.") }
      s.stop()
      return
    }
    camera = cam
    cameraStarted = false
    streamedOnce = false
    streamRestarts = 0
    firstFrameAt = null
    frameCount = 0
    fpsWindowStart = 0
    fpsWindowFrames = 0
    val stream = cam.stream
    // Subscribed (UNDISPATCHED) before start() so the first transitions and errors aren't missed.
    cameraJobs += launch(frameDispatcher, start = CoroutineStart.UNDISPATCHED) { stream.videoStream.collect { onFrame(it) } }
    cameraJobs += launch(start = CoroutineStart.UNDISPATCHED) { stream.state.collect { onStreamState(s, cam, it) } }
    cameraJobs += launch(start = CoroutineStart.UNDISPATCHED) { stream.errorStream.collect { onStreamError(s, it) } }
    cameraJobs += launch(start = CoroutineStart.UNDISPATCHED) {
      // CameraState replays STOPPED to a new collector; only a stop *after* a start means the
      // glasses went away under us.
      cam.state.collect { cs ->
        log.log("camera_state", cs.name)
        if (cs == CameraState.STARTED) cameraStarted = true
        else if (cameraStarted && cs == CameraState.STOPPED) {
          cameraStarted = false
          log.log("camera", "stopped unexpectedly; ending session to reconnect")
          s.stop()
        }
      }
    }
    startStream(s, cam)
    log.log(
        "camera",
        "attached: ${streamConfig.videoQuality} @ ${streamConfig.frameRate} fps, " +
            "compressed=${streamConfig.compressVideo}",
    )
  }

  /** A stream that can't start leaves the walk with no frames: end the session so the loop retries. */
  private fun startStream(s: DeviceSession, cam: Camera) {
    cam.stream.start().onFailure { e, _ ->
      log.log("stream", "start failed: ${e.name} ${e.description}; ending session to retry")
      _status.update { it.copy(lastError = e.name, guidance = "Couldn't start the glasses camera. Retrying.") }
      s.stop()
    }
  }

  private fun onStreamState(s: DeviceSession, cam: Camera, st: StreamState) {
    _status.update { it.copy(streamState = st) }
    log.log("stream_state", st.name)
    when (st) {
      StreamState.STREAMING -> {
        streamedOnce = true
        when {
          userPaused -> {
            setPhase(WalkPhase.PAUSED)
            reconcilePause(s) // a pause asked for while the stream was starting
          }
          glassesOff() -> setPhase(WalkPhase.SUSPENDED, "Glasses are off. Put them on to keep capturing.")
          else -> setPhase(WalkPhase.ACTIVE)
        }
      }
      StreamState.PAUSED ->
          when {
            ourPause -> reconcilePause(s) // a resume may be waiting for our pause to settle
            !userPaused -> setPhase(WalkPhase.SUSPENDED, _status.value.guidance ?: "The glasses paused the camera.")
          }
      // The state flow replays STOPPED to a new collector, so only a stop after STREAMING counts.
      // Per the DAT docs a started camera's stream can be started again; a stopped camera can't
      // (that case ends the session in the camera-state collector).
      // Never while the session itself is pausing or stopping, or the tech asked for a pause.
      StreamState.STOPPED ->
          if (streamedOnce && cameraStarted && !userPaused && s.state.value == DeviceSessionState.STARTED) {
            streamedOnce = false
            if (++streamRestarts > MAX_STREAM_RESTARTS) {
              log.log("stream", "stopped again after $MAX_STREAM_RESTARTS restarts; ending session to reconnect")
              s.stop()
            } else {
              log.log("stream", "stopped while the camera is still started; starting it again (#$streamRestarts)")
              setPhase(WalkPhase.CONNECTING, "Glasses camera stopped. Restarting it.")
              startStream(s, cam)
            }
          }
      else -> Unit
    }
  }

  private fun onStreamError(s: DeviceSession, e: StreamError) {
    log.log("stream_error", e.name)
    val guidance =
        when (e) {
          StreamError.HINGE_CLOSED -> "Glasses folded. Unfold them to keep capturing."
          StreamError.THERMAL_HOT -> "Glasses are hot. Camera paused to cool down."
          StreamError.BATTERY_LOW -> "Glasses battery is low."
          StreamError.PEAK_POWER_LIMIT -> "Glasses hit their power limit. Camera paused."
          StreamError.PERMISSIONS_DENIED -> "Camera access was denied in Meta AI."
          StreamError.TIMEOUT -> "Glasses camera timed out. Reconnecting."
          StreamError.CRITICAL_STREAM_ERROR -> "Glasses camera failed. Reconnecting."
          else -> null // STREAM_ERROR is informational and doesn't stop the stream
        }
    _status.update { it.copy(lastError = e.name, guidance = guidance ?: it.guidance) }
    if (e == StreamError.PERMISSIONS_DENIED) {
      // Nothing retries a stream that was refused, so the walk sat in CONNECTING forever, silently,
      // even after access was granted again (Mock Device Kit scenario test 2026-11-26). Say what to
      // do, end the session, and let the loop wait for the grant before it tries again.
      permissionDenied = true
      setPhase(WalkPhase.NEEDS_PERMISSION, "Allow camera access for FieldCapture in Meta AI.")
      s.stop()
      return
    }
    if (e == StreamError.CRITICAL_STREAM_ERROR || e == StreamError.TIMEOUT) s.stop()
    // Folding and stream failures are announced through the phase change; these would be silent.
    else if (guidance != null && e in setOf(StreamError.THERMAL_HOT, StreamError.BATTERY_LOW, StreamError.PEAK_POWER_LIMIT)) {
      alert(guidance)
    }
  }

  private fun onSessionError(e: DeviceSessionError) {
    log.log("session_error", "${e.name}: ${e.description}")
    val guidance =
        when (e) {
          DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
              "The glasses need an app update in Meta AI."
          DeviceSessionError.INSUFFICIENT_SDK_VERSION -> "This FieldCapture build is too old for these glasses."
          DeviceSessionError.NOT_REGISTERED -> "FieldCapture isn't connected to Meta AI."
          DeviceSessionError.THERMAL_CRITICAL,
          DeviceSessionError.THERMAL_EMERGENCY -> "The glasses overheated. Let them cool down."
          DeviceSessionError.BATTERY_CRITICAL -> "Glasses battery is critical."
          DeviceSessionError.DEVICE_DISCONNECTED,
          DeviceSessionError.SESSION_ENDED_BY_DEVICE -> "Glasses disconnected."
          else -> null
        }
    _status.update { it.copy(lastError = e.name, guidance = guidance ?: it.guidance) }
    when (e) {
      DeviceSessionError.THERMAL_CRITICAL,
      DeviceSessionError.THERMAL_EMERGENCY,
      DeviceSessionError.BATTERY_CRITICAL -> guidance?.let { alert(it) }
      else -> Unit // a disconnect is announced through the phase change
    }
  }

  // ---------------------------------------------------------------- frames

  private fun onFrame(f: VideoFrame) {
    if (f.isCompressed) return
    val now = SystemClock.elapsedRealtime()
    val bytes = f.buffer.remaining()
    frames.push(f.buffer, f.width, f.height, f.presentationTimeUs, now)
    frameListener?.invoke(f, now)
    frameCount++
    if (firstFrameAt == null) {
      firstFrameAt = now
      val sinceStart = now - sessionStartMs
      log.log(
          "first_frame",
          "$sinceStart ms after createSession; ${f.width}x${f.height}, $bytes bytes " +
              "(tight 4:2:0 would be ${f.width * f.height * 3 / 2})",
      )
      _status.update { it.copy(firstFrameMs = sinceStart) }
    }
    if (fpsWindowStart == 0L) fpsWindowStart = now
    fpsWindowFrames++
    if (now - lastStatusPush >= 500) {
      val fps = fpsWindowFrames * 1000.0 / maxOf(1L, now - fpsWindowStart)
      if (now - fpsWindowStart > 3000) {
        fpsWindowStart = now
        fpsWindowFrames = 0
      }
      lastStatusPush = now
      _status.update {
        it.copy(framesReceived = frameCount, fps = fps, frameWidth = f.width, frameHeight = f.height, frameBytes = bytes)
      }
    }
  }

  // ---------------------------------------------------------------- photos

  /**
   * One still through the running stream. Serialised (the SDK allows one capture in flight) and
   * time-boxed: a transfer that dies mid-air must not strand the tech.
   */
  suspend fun capturePhoto(timeoutMs: Long = PHOTO_TIMEOUT_MS): PhotoResult =
      captureMutex.withLock {
        val t0 = SystemClock.elapsedRealtime()
        val stream = camera?.stream ?: return@withLock PhotoResult.Failed("no camera attached", 0)
        if (stream.state.value != StreamState.STREAMING) {
          return@withLock PhotoResult.Failed("stream is ${stream.state.value}", 0)
        }
        val result = withTimeoutOrNull(timeoutMs) { stream.capturePhoto() }
        val ms = SystemClock.elapsedRealtime() - t0
        if (result == null) {
          log.log("photo", "timed out after $ms ms")
          return@withLock PhotoResult.Failed("timeout", ms)
        }
        val data = result.getOrNull()
        if (data == null) {
          val reason = result.errorOrNull()?.description ?: "unknown"
          log.log("photo", "failed after $ms ms: $reason")
          return@withLock PhotoResult.Failed(reason, ms)
        }
        val jpeg = withContext(Dispatchers.Default) { runCatching { PhotoCodec.toJpeg(data) }.getOrNull() }
        if (jpeg == null) {
          log.log("photo", "could not decode ${data::class.simpleName}")
          return@withLock PhotoResult.Failed("decode failed", ms)
        }
        log.log("photo", "${jpeg.width}x${jpeg.height} from ${jpeg.sourceFormat}, ${jpeg.bytes.size} B, $ms ms")
        PhotoResult.Ok(jpeg.bytes, jpeg.width, jpeg.height, jpeg.sourceFormat, ms)
      }

  companion object {
    const val STARTING_WATCHDOG_MS = 15_000L
    /** How long a resume waits for our pause to reach the stream before resuming anyway. */
    const val PAUSE_SETTLE_MS = 1_000L
    /** After a refused stream, failed permission checks (can't tell) before trying again anyway: 30 s. */
    const val UNKNOWN_PERMISSION_CHECKS = 6
    const val LINK_LOSS_GRACE_MS = 5_000L
    /** The same warning is spoken at most once a minute. */
    const val ALERT_REPEAT_MS = 60_000L
    const val LOW_BATTERY_PCT = 15
    const val STABLE_SESSION_MS = 30_000L
    const val MAX_BACKOFF_MS = 10_000L
    const val PHOTO_TIMEOUT_MS = 15_000L
    const val PERMISSION_RECHECK_MS = 5_000L
    const val MAX_STREAM_RESTARTS = 3
  }
}
