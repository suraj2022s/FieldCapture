package app.fieldcapture.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.fieldcapture.core.EventLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the tech hears. Rules: one short sentence, specific and actionable, never chatty.
 * Earcons carry most confirmations so the speech channel stays free for problems.
 * On real glasses this audio routes to the glasses' speakers over Bluetooth; on the mock it plays
 * from the phone.
 */
class Speaker(context: Context, private val log: EventLog) {
  private val _speaking = MutableStateFlow(false)
  /** True while TTS is talking; the speech listener mutes itself so it doesn't hear the app. */
  val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

  private val pending = AtomicInteger(0)
  private val ids = AtomicInteger(0)
  @Volatile private var ready = false
  private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) configure()
        log.log("tts", if (ready) "ready" else "init failed ($status)")
      }

  private fun configure() {
    tts.language = Locale.US
    tts.setSpeechRate(1.15f)
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            _speaking.value = true
          }

          override fun onDone(utteranceId: String?) = finished()

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) = finished()

          override fun onError(utteranceId: String?, errorCode: Int) = finished()

          override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        },
    )
  }

  private fun finished() {
    if (pending.decrementAndGet() <= 0) {
      pending.set(0)
      _speaking.value = false
    }
  }

  /** Speak [text]. [interrupt] drops anything still queued (prompts should never pile up). */
  fun say(text: String, interrupt: Boolean = true) {
    log.log("say", text)
    if (!ready) return
    if (interrupt) pending.set(0)
    pending.incrementAndGet()
    _speaking.value = true
    tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "fc-${ids.incrementAndGet()}")
  }

  /** Command understood, working on it. */
  fun tick() = beep(ToneGenerator.TONE_PROP_BEEP, 60)

  /** Saved durably. */
  fun success() = beep(ToneGenerator.TONE_PROP_ACK, 160)

  /** Something needs the tech's attention. */
  fun failure() = beep(ToneGenerator.TONE_PROP_NACK, 220)

  private fun beep(type: Int, ms: Int) {
    runCatching { tone?.startTone(type, ms) }
  }

  fun shutdown() {
    runCatching { tts.shutdown() }
    runCatching { tone?.release() }
  }
}
