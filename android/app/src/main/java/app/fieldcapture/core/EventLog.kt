package app.fieldcapture.core

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One thing that happened: a lifecycle transition, a command, an error, a measurement. */
data class AppEvent(
    /** Monotonic ms (SystemClock.elapsedRealtime) — used for latency maths. */
    val tMs: Long,
    /** Wall-clock ms — used in the job record timeline. */
    val wallMs: Long,
    val type: String,
    val detail: String,
)

/**
 * Append-only event log. Everything interesting goes through here so that
 * (1) the screen can show it, (2) logcat has it under tag "FieldCapture" for measurements,
 * and (3) the job record can carry the session timeline.
 */
class EventLog(private val capacity: Int = 400) {
  private val _events = MutableStateFlow<List<AppEvent>>(emptyList())
  val events: StateFlow<List<AppEvent>> = _events.asStateFlow()

  fun log(type: String, detail: String = "") {
    val e = AppEvent(SystemClock.elapsedRealtime(), System.currentTimeMillis(), type, detail)
    Log.i(TAG, "[$type] $detail")
    _events.update { (it + e).takeLast(capacity) }
  }

  companion object {
    const val TAG = "FieldCapture"
  }
}
