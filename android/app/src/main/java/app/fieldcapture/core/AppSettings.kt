package app.fieldcapture.core

import android.content.Context
import app.fieldcapture.BuildConfig
import app.fieldcapture.glasses.PhotoCodec
import app.fieldcapture.quality.Thresholds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Small persisted switches plus the quality thresholds the app ships with. */
class AppSettings(context: Context) {
  private val prefs = context.getSharedPreferences("fieldcapture", Context.MODE_PRIVATE)

  /** Debug "no signal" switch: uploads are held exactly as they are with airplane mode on. */
  var simulateOffline: Boolean
    get() = prefs.getBoolean("simulateOffline", false)
    set(v) = prefs.edit().putBoolean("simulateOffline", v).apply()

  /**
   * Eval data collection: every candidate frame the gate judged during a capture is saved (JPEG +
   * on-device metrics + verdict + OCR) to external storage for labelling. See eval/README.md.
   */
  var datasetMode: Boolean
    get() = prefs.getBoolean("datasetMode", false)
    set(v) = prefs.edit().putBoolean("datasetMode", v).apply()

  /**
   * Debug: the simulated glasses play the bundled synthetic clip instead of the phone camera.
   * Persisted so a scripted run that kills the app comes back to the same feed (and films nothing).
   */
  var mockTestFeed: Boolean
    get() = prefs.getBoolean("mockTestFeed", false)
    set(v) = prefs.edit().putBoolean("mockTestFeed", v).apply()

  /**
   * Where uploads go. The build default (127.0.0.1:8000) only works over USB with `adb reverse`; a
   * real phone uses the back office's network address, e.g. http://192.168.1.20:8002.
   */
  var backendUrl: String
    get() = prefs.getString("backendUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.BACKEND_URL
    set(v) = prefs.edit().putString("backendUrl", normalizeUrl(v)).apply()

  /** The back office's access code (FIELDCAPTURE_TOKEN); blank when it runs without one (USB). */
  var accessCode: String
    get() = prefs.getString("accessCode", "") ?: ""
    set(v) = prefs.edit().putString("accessCode", v.trim()).apply()

  /** Prefer the glasses' capturePhoto() still as evidence when it passes QC. */
  var usePhotoEvidence: Boolean
    get() = prefs.getBoolean("usePhotoEvidence", true)
    set(v) = prefs.edit().putBoolean("usePhotoEvidence", v).apply()

  /** Chroma layout of raw stream frames (undocumented by the SDK; measured on device). */
  var yuvLayout: PhotoCodec.YuvLayout
    get() = runCatching { PhotoCodec.YuvLayout.valueOf(prefs.getString("yuvLayout", null) ?: "") }.getOrDefault(PhotoCodec.YuvLayout.I420)
    set(v) = prefs.edit().putString("yuvLayout", v.name).apply()

  /**
   * Thresholds measured by eval/qc_thresholds.py and shipped in assets/thresholds.json.
   * Falls back to the pre-data seed values.
   */
  val thresholds: Thresholds
  val thresholdsVersion: String

  init {
    val loaded =
        runCatching {
              val text = context.assets.open("thresholds.json").bufferedReader().use { it.readText() }
              val o = Json.parseToJsonElement(text).jsonObject
              val t = o["thresholds"]!!.jsonObject
              fun d(k: String) = (t[k] ?: error("missing $k")).jsonPrimitive.double
              Thresholds(
                  minSharpness = d("minSharpness"),
                  minMeanLuma = d("minMeanLuma"),
                  maxDarkFraction = d("maxDarkFraction"),
                  maxClippedFraction = d("maxClippedFraction"),
                  maxTextClippedFraction = d("maxTextClippedFraction"),
                  minTextLineHeight = d("minTextLineHeight"),
                  minEdgeMargin = d("minEdgeMargin"),
              ) to ((o["version"]?.jsonPrimitive?.content) ?: "measured")
            }
            .getOrNull()
    thresholds = loaded?.first ?: Thresholds.SEED
    thresholdsVersion = loaded?.second ?: "seed-2026-11-25"
  }

  @Suppress("unused") private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
}

/** "192.168.1.20:8002" -> "http://192.168.1.20:8002"; trims spaces and a trailing slash. Blank stays blank. */
fun normalizeUrl(raw: String): String {
  val s = raw.trim().trimEnd('/')
  if (s.isEmpty()) return s
  return if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) s else "http://$s"
}
