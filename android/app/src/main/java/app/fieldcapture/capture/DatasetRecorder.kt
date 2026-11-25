package app.fieldcapture.capture

import app.fieldcapture.glasses.PhotoCodec
import app.fieldcapture.quality.FrameMetrics
import app.fieldcapture.quality.Verdict
import app.fieldcapture.store.JobStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saves every frame the quality gate judged during a capture, with the exact on-device numbers,
 * so thresholds are fitted on what the phone actually measured (eval/qc_thresholds.py).
 * Output: <external files>/dataset/<jobId>/<captureId>/cand_<n>.jpg + .json, capture.json.
 */
class DatasetRecorder(private val root: File, private val json: Json) {

  @Serializable
  data class MetricsDto(
      val sharpness: Double,
      val tenengrad: Double,
      val meanLuma: Double,
      val p05: Int,
      val p50: Int,
      val p95: Int,
      val darkFraction: Double,
      val clippedFraction: Double,
      val width: Int,
      val height: Int,
  )

  @Serializable
  data class CandidateDto(
      val file: String,
      val seq: Long,
      /** ms relative to the moment the command was understood (negative = before). */
      val tRelMs: Long,
      val width: Int,
      val height: Int,
      val frame: MetricsDto,
      val text: MetricsDto? = null,
      val ok: Boolean,
      val issues: List<String>,
      val score: Double,
      val ocrLines: List<String> = emptyList(),
      val ocrBoxes: List<List<Double>> = emptyList(),
      val chosen: Boolean,
  )

  @Serializable
  data class CaptureDto(
      val captureId: String,
      val jobId: String,
      val purpose: String,
      val transcript: String,
      val room: String?,
      val surface: String?,
      val createdAt: String,
      val thresholdsVersion: String,
      val yuvLayout: String,
      val prompts: Int,
      val override: Boolean,
      val evidenceSource: String,
      val photoFile: String? = null,
      val candidates: List<CandidateDto>,
  )

  data class Cand(
      val frame: ScoredFrame,
      val verdict: Verdict,
      val ocr: TextDetector.Result?,
      val chosen: Boolean,
  )

  fun record(
      captureId: String,
      jobId: String,
      purpose: String,
      transcript: String,
      room: String?,
      surface: String?,
      createdAt: String,
      thresholdsVersion: String,
      yuvLayout: PhotoCodec.YuvLayout,
      prompts: Int,
      override: Boolean,
      evidenceSource: String,
      tCmdMs: Long,
      candidates: List<Cand>,
      photoJpeg: ByteArray?,
  ): File {
    val dir = File(root, "$jobId/$captureId").apply { mkdirs() }
    val dtos =
        candidates.mapIndexedNotNull { i, c ->
          val jpeg = PhotoCodec.yuvToJpeg(c.frame.frame, yuvLayout, quality = 92) ?: return@mapIndexedNotNull null
          val name = "cand_%02d.jpg".format(i)
          JobStore.writeAtomic(File(dir, name), jpeg)
          CandidateDto(
              file = name,
              seq = c.frame.seq,
              tRelMs = c.frame.tMs - tCmdMs,
              width = c.frame.frame.width,
              height = c.frame.frame.height,
              frame = c.verdict.metrics.dto(),
              text = c.verdict.textMetrics?.dto(),
              ok = c.verdict.ok,
              issues = c.verdict.issues.map { it.name },
              score = c.verdict.score,
              ocrLines = c.ocr?.lines?.map { it.text }.orEmpty(),
              ocrBoxes = c.ocr?.lines?.map { listOf(it.box.left, it.box.top, it.box.right, it.box.bottom) }.orEmpty(),
              chosen = c.chosen,
          )
        }
    val photoFile = photoJpeg?.let { JobStore.writeAtomic(File(dir, "photo.jpg"), it); "photo.jpg" }
    val dto =
        CaptureDto(
            captureId, jobId, purpose, transcript, room, surface, createdAt, thresholdsVersion, yuvLayout.name,
            prompts, override, evidenceSource, photoFile, dtos,
        )
    JobStore.writeAtomic(File(dir, "capture.json"), json.encodeToString(dto).toByteArray())
    return dir
  }

  private fun FrameMetrics.dto() =
      MetricsDto(sharpness, tenengrad, meanLuma, p05, p50, p95, darkFraction, clippedFraction, width, height)
}
