package app.fieldcapture.capture

import app.fieldcapture.core.EventLog
import app.fieldcapture.glasses.FrameRingBuffer
import app.fieldcapture.quality.FrameMetrics
import app.fieldcapture.quality.LumaImage
import app.fieldcapture.quality.Metrics
import app.fieldcapture.quality.Purpose
import app.fieldcapture.quality.QualityGate
import app.fieldcapture.quality.Thresholds
import app.fieldcapture.quality.Verdict
import com.meta.wearable.dat.camera.types.VideoFrame

/** One frame that was scored, with an owned copy of its pixels. */
class ScoredFrame(
    val seq: Long,
    val tMs: Long,
    val frame: FrameRingBuffer.Frame,
    val stride: Int,
    val metrics: FrameMetrics,
    /** Generic (purpose-free) verdict; nameplates are re-judged with OCR at capture time. */
    val verdict: Verdict,
) {
  fun luma(): LumaImage = LumaImage.fromPlane(frame.data, frame.width, frame.height, stride)
}

/**
 * Scores every stream frame on the frame thread and keeps the best frame of each [bucketMs] slice
 * for the last [horizonMs]. A capture command can then reach back to when the tech started
 * speaking, and the "retake" wait simply watches new keepers arrive. No prompt is needed when one
 * good frame exists anywhere in the window.
 *
 * Memory: about 13 keepers × 1.4 MB at 720p ≈ 18 MB, which is fine on the 4 GB test phone.
 */
class FrameScorer(
    private val log: EventLog,
    private val thresholds: () -> Thresholds,
    private val bucketMs: Long = 400,
    private val horizonMs: Long = 5_000,
) {
  private val keepers = ArrayDeque<ScoredFrame>()
  private var lumaScratch = ByteArray(0)
  private var seq = 0L
  private var layoutLogged = false

  @Volatile var latest: FrameMetrics? = null
    private set

  /** Called for every raw frame (frame thread). */
  fun onFrame(f: VideoFrame, tMs: Long) {
    val w = f.width
    val h = f.height
    val buf = f.buffer.duplicate()
    val total = buf.remaining()
    val stride = inferStride(total, w, h)
    if (!layoutLogged) {
      layoutLogged = true
      log.log("frame_layout", "${w}x$h bytes=$total inferredStride=$stride (tight=${total == w * h * 3 / 2})")
    }
    val lumaBytes = stride * (h - 1) + w
    if (total < lumaBytes) return
    if (lumaScratch.size < lumaBytes) lumaScratch = ByteArray(lumaBytes)
    buf.get(lumaScratch, 0, lumaBytes)
    val metrics = Metrics.compute(LumaImage.fromPlane(lumaScratch, w, h, stride))
    latest = metrics
    val verdict = QualityGate.evaluate(metrics, Purpose.DAMAGE, thresholds())
    val bucket = tMs / bucketMs
    seq++

    synchronized(keepers) {
      val last = keepers.lastOrNull()
      if (last != null && last.tMs / bucketMs == bucket) {
        if (verdict.score <= last.verdict.score) return
        keepers.removeLast()
      }
      keepers.addLast(ScoredFrame(seq, tMs, copy(f.buffer, w, h, f.presentationTimeUs, tMs), stride, metrics, verdict))
      while (keepers.isNotEmpty() && tMs - keepers.first().tMs > horizonMs) keepers.removeFirst()
    }
  }

  fun keepersSince(tMs: Long): List<ScoredFrame> = synchronized(keepers) { keepers.filter { it.tMs >= tMs } }

  fun clear() = synchronized(keepers) { keepers.clear() }

  private fun copy(buffer: java.nio.ByteBuffer, w: Int, h: Int, ptsUs: Long, tMs: Long): FrameRingBuffer.Frame {
    val src = buffer.duplicate()
    val bytes = ByteArray(src.remaining())
    src.get(bytes)
    return FrameRingBuffer.Frame(bytes, bytes.size, w, h, ptsUs, tMs, seq)
  }

  companion object {
    /** 4:2:0 frames are 1.5 × stride × height bytes; a padded stride shows up as extra bytes. */
    fun inferStride(totalBytes: Int, width: Int, height: Int): Int {
      if (totalBytes == width * height * 3 / 2) return width
      val candidate = (totalBytes * 2) / (3 * height)
      return if (candidate >= width && candidate * height * 3 / 2 == totalBytes) candidate else width
    }
  }
}
