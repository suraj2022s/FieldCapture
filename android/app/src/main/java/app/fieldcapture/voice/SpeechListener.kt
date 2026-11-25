package app.fieldcapture.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.fieldcapture.core.EventLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One recognition result. Partial results let one-shot commands fire before the tech stops talking. */
data class Heard(
    val hypotheses: List<String>,
    val confidences: List<Float>?,
    val isFinal: Boolean,
    /** When the recogniser detected speech starting (monotonic ms) — the capture window reaches back to here. */
    val speechStartMs: Long?,
    val atMs: Long,
    /** Increments per utterance, so a command acted on from a partial isn't repeated by its final. */
    val utterance: Int,
)

data class ListenState(
    val listening: Boolean = false,
    val engine: String = "-",
    val lastPartial: String = "",
    val lastFinal: String = "",
    val lastError: String? = null,
)

/**
 * Continuous voice-command listening on the PHONE microphone.
 *
 * Why the phone mic (the brief asks us to say why):
 * - Mock Device Kit has no glasses audio path. Its camera feed is video-only, and mock audio
 *   streaming emits no PCM.
 * - DAT's glasses Speech and Audio-streaming capabilities are experimental in 1.1, so apps using
 *   them can't be published.
 * - On real glasses, the HFP/SCO microphone competes with the camera stream over Bluetooth
 *   (issues #159, #206, #196).
 *
 * The on-device recogniser is preferred so commands still work with no signal (the attic).
 * Android's recogniser stops after each utterance, so we restart it.
 */
class SpeechListener(
    private val context: Context,
    private val log: EventLog,
    private val language: String = "en-US",
) {
  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  @Volatile private var wanted = false
  @Volatile private var muted = false
  private var speechStartMs: Long? = null
  private var utterance = 0
  private var consecutiveErrors = 0
  private var useOnDevice = true

  private val _heard = MutableSharedFlow<Heard>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val heard: SharedFlow<Heard> = _heard.asSharedFlow()

  private val _state = MutableStateFlow(ListenState())
  val state: StateFlow<ListenState> = _state.asStateFlow()

  fun start() {
    wanted = true
    main.post {
      ensureRecognizer()
      listen()
    }
  }

  fun stop() {
    wanted = false
    main.post {
      runCatching { recognizer?.cancel() }
      runCatching { recognizer?.destroy() }
      recognizer = null
      _state.update { it.copy(listening = false) }
    }
  }

  /** Mute while the app is talking, so the recogniser doesn't transcribe our own prompts. */
  fun setMuted(value: Boolean) {
    if (muted == value) return
    muted = value
    main.post {
      if (value) {
        runCatching { recognizer?.cancel() }
        _state.update { it.copy(listening = false) }
      } else if (wanted) {
        main.postDelayed({ listen() }, 150)
      }
    }
  }

  private fun ensureRecognizer() {
    if (recognizer != null) return
    val onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val anyAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    val onDevice = useOnDevice && onDeviceAvailable
    recognizer =
        if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
    recognizer?.setRecognitionListener(listener)
    val engine = if (onDevice) "on-device" else "default service"
    _state.update { it.copy(engine = engine) }
    log.log("asr", "engine=$engine (onDeviceAvailable=$onDeviceAvailable anyAvailable=$anyAvailable lang=$language)")
  }

  private fun listen() {
    if (!wanted || muted) return
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
          putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
    speechStartMs = null
    runCatching { recognizer?.startListening(intent) }
        .onFailure {
          log.log("asr", "startListening failed: ${it.message}")
          main.postDelayed({ listen() }, 1000)
        }
  }

  private fun restart(delayMs: Long) {
    if (!wanted || muted) return
    main.postDelayed({ listen() }, delayMs)
  }

  private fun results(bundle: Bundle?): Pair<List<String>, List<Float>?> {
    val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    val conf = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList()
    return texts to conf
  }

  private val listener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          _state.update { it.copy(listening = true, lastError = null) }
        }

        override fun onBeginningOfSpeech() {
          speechStartMs = SystemClock.elapsedRealtime()
          utterance++
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
          _state.update { it.copy(listening = false) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
          val (texts, conf) = results(partialResults)
          val top = texts.firstOrNull()?.trim().orEmpty()
          if (top.isEmpty()) return
          _state.update { it.copy(lastPartial = top) }
          _heard.tryEmit(Heard(texts, conf, false, speechStartMs, SystemClock.elapsedRealtime(), utterance))
        }

        override fun onResults(results: Bundle?) {
          consecutiveErrors = 0
          val (texts, conf) = results(results)
          val top = texts.firstOrNull()?.trim().orEmpty()
          if (top.isNotEmpty()) {
            _state.update { it.copy(lastFinal = top, lastPartial = "") }
            log.log("heard", "\"$top\"" + (conf?.firstOrNull()?.let { " (%.2f)".format(it) } ?: ""))
            _heard.tryEmit(Heard(texts, conf, true, speechStartMs, SystemClock.elapsedRealtime(), utterance))
          }
          restart(50)
        }

        override fun onError(error: Int) {
          _state.update { it.copy(listening = false) }
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50) // silence: just listen again
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              runCatching { recognizer?.cancel() }
              restart(400)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
              log.log("asr", "microphone permission missing; listening stopped")
              _state.update { it.copy(lastError = "microphone permission missing") }
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> fallBackToDefaultEngine("language $language unavailable on device")
            else -> {
              consecutiveErrors++
              val name = errorName(error)
              _state.update { it.copy(lastError = name) }
              log.log("asr", "error $name (#$consecutiveErrors)")
              if (useOnDevice && consecutiveErrors >= 3) fallBackToDefaultEngine("repeated $name")
              else restart(minOf(3000L, 300L * consecutiveErrors))
            }
          }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
      }

  private fun fallBackToDefaultEngine(reason: String) {
    if (!useOnDevice) {
      restart(2000)
      return
    }
    // The on-device engine exists but lacks this language: ask the system to fetch the offline model
    // (API 33+), so the next walk works with no signal. Meanwhile use the default service.
    if (Build.VERSION.SDK_INT >= 33 && reason.contains("unavailable")) {
      runCatching {
            recognizer?.triggerModelDownload(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language),
            )
          }
          .onSuccess { log.log("asr", "requested offline $language model download for the on-device engine") }
          .onFailure { log.log("asr", "offline model download request failed: ${it.message}") }
    }
    log.log("asr", "falling back to the default recognition service: $reason")
    useOnDevice = false
    runCatching { recognizer?.destroy() }
    recognizer = null
    ensureRecognizer()
    restart(300)
  }

  private fun errorName(e: Int) =
      when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        else -> "code $e"
      }
}
