package app.fieldcapture.capture

import android.graphics.Bitmap
import android.os.SystemClock
import app.fieldcapture.glasses.FrameRingBuffer
import app.fieldcapture.quality.NormBox
import app.fieldcapture.quality.TextLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device OCR (ML Kit, bundled Latin model — no network). Two jobs:
 * 1. the nameplate framing check (is there text, is it cut off, is it big enough), and
 * 2. an offline read-back plus a cross-check signal for the cloud extractor's confidence.
 */
class TextDetector {
  private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  data class Line(val text: String, val box: NormBox, val confidence: Float?)

  data class Result(val lines: List<Line>, val ms: Long) {
    val layout: TextLayout
      get() = TextLayout(lines.map { it.box })

    val fullText: String
      get() = lines.joinToString("\n") { it.text }
  }

  /** OCR on a raw stream frame, using its real luma with neutral chroma (works for any 4:2:0 layout). */
  suspend fun detect(frame: FrameRingBuffer.Frame, stride: Int): Result {
    val w = frame.width
    val h = frame.height
    val nv21 = ByteArray(w * h * 3 / 2)
    for (y in 0 until h) System.arraycopy(frame.data, y * stride, nv21, y * w, w)
    java.util.Arrays.fill(nv21, w * h, nv21.size, 128.toByte())
    return run(InputImage.fromByteArray(nv21, w, h, 0, InputImage.IMAGE_FORMAT_NV21), w, h)
  }

  suspend fun detect(bitmap: Bitmap): Result = run(InputImage.fromBitmap(bitmap, 0), bitmap.width, bitmap.height)

  private suspend fun run(image: InputImage, w: Int, h: Int): Result {
    val t0 = SystemClock.elapsedRealtime()
    val text =
        suspendCancellableCoroutine<Text> { cont ->
          client
              .process(image)
              .addOnSuccessListener { cont.resume(it) }
              .addOnFailureListener { cont.resumeWithException(it) }
        }
    val lines =
        text.textBlocks.flatMap { block ->
          block.lines.mapNotNull { line ->
            val r = line.boundingBox ?: return@mapNotNull null
            Line(
                line.text,
                NormBox(
                    r.left.toDouble() / w,
                    r.top.toDouble() / h,
                    r.right.toDouble() / w,
                    r.bottom.toDouble() / h,
                ),
                line.confidence,
            )
          }
        }
    return Result(lines, SystemClock.elapsedRealtime() - t0)
  }

  companion object {
    private val SERIAL_LABEL = Regex("\\b(?:serial|ser\\.?|s/?n|sn)\\b[\\s.:#-]*(?:no\\.?|number)?[\\s.:#-]*([A-Z0-9][A-Z0-9-]{4,})", RegexOption.IGNORE_CASE)

    /** Best-effort serial number from OCR text, for the spoken offline read-back only. */
    fun guessSerial(result: Result): String? {
      for (line in result.lines) SERIAL_LABEL.find(line.text)?.let { return it.groupValues[1].trim('-') }
      // Label and value often sit on separate lines: take the line after a "serial" label.
      val idx = result.lines.indexOfFirst { Regex("\\b(serial|s/?n)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
      if (idx >= 0 && idx + 1 < result.lines.size) {
        Regex("[A-Z0-9][A-Z0-9-]{5,}").find(result.lines[idx + 1].text.uppercase())?.let { return it.value }
      }
      return null
    }
  }
}
