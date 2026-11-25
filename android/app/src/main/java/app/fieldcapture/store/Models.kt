package app.fieldcapture.store

import kotlinx.serialization.Serializable

/*
 * On-device records. They are uploaded as-is and the back office assembles the job record from them,
 * so field names here are part of the upload contract (schemas/capture_upload.schema.json).
 */

@Serializable
data class JobHeader(
    val schemaVersion: Int = 1,
    val jobId: String,
    val createdAt: String,
    val endedAt: String? = null,
    val app: Map<String, String> = emptyMap(),
    val device: Map<String, String> = emptyMap(),
)

@Serializable
data class QualityInfo(
    val passed: Boolean,
    val issues: List<String>,
    val sharpness: Double,
    val tenengrad: Double,
    val meanLuma: Double,
    val darkFraction: Double,
    val clippedFraction: Double,
    val textSharpness: Double? = null,
    val textClippedFraction: Double? = null,
    val textLineHeight: Double? = null,
    /** Spoken retake prompts before this frame was accepted. */
    val prompts: Int,
    /** Tech said "capture anyway", or the wait timed out and the least-bad frame was kept. */
    val override: Boolean,
    val candidatesEvaluated: Int,
    val thresholdsVersion: String,
)

@Serializable
data class OcrLineInfo(val text: String, val box: List<Double>, val confidence: Float? = null)

@Serializable
data class OcrInfo(val engine: String, val lines: List<OcrLineInfo>, val ms: Long, val serialGuess: String? = null)

@Serializable
data class EvidenceInfo(
    val file: String,
    val width: Int,
    val height: Int,
    val sha256: String,
    /** glasses_photo (capturePhoto still) or stream_frame (best QC'd video frame). */
    val source: String,
    /** The QC'd stream frame is always kept too, so the eval can compare sources. */
    val streamFrameFile: String? = null,
    val photoLatencyMs: Long? = null,
    val photoError: String? = null,
)

@Serializable
data class TimingInfo(
    /** Wall-clock ISO-8601 of the moment the command was understood. */
    val commandAt: String,
    /** ms from speech start to command understood (recognition latency). */
    val speechToCommandMs: Long? = null,
    /** ms from command to an accepted frame (includes any retake wait). */
    val commandToFrameMs: Long,
    /** ms from command to durable save (when "Got it" is said). */
    val commandToSavedMs: Long,
)

@Serializable
data class GlassesInfo(
    val model: String,
    val mock: Boolean,
    val sdk: String,
    val streamQuality: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val battery: Int? = null,
    val thermal: String? = null,
)

@Serializable
data class CaptureRecord(
    val schemaVersion: Int = 1,
    val captureId: String,
    val jobId: String,
    /** nameplate | damage */
    val type: String,
    val createdAt: String,
    val transcript: String,
    val asrConfidence: Double,
    val room: String? = null,
    val surface: String? = null,
    val unparsedSlots: String? = null,
    val evidence: EvidenceInfo,
    val quality: QualityInfo,
    val ocr: OcrInfo? = null,
    val glasses: GlassesInfo? = null,
    val timing: TimingInfo,
    /** Set when "retake" replaced an earlier capture. */
    val replaces: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class NoteRecord(
    val schemaVersion: Int = 1,
    val noteId: String,
    val jobId: String,
    val createdAt: String,
    val transcript: String,
    val text: String,
    val room: String? = null,
    val asrConfidence: Double,
    val deleted: Boolean = false,
)

@Serializable
data class EventRecord(val at: String, val type: String, val detail: String)

@Serializable
data class OutboxItem(
    /** Idempotency key: the server upserts by this id, so a replay can never duplicate. */
    val id: String,
    /** job | capture | note | events | finalize */
    val kind: String,
    val jobId: String,
    val metaFile: String,
    val imageFile: String? = null,
    val createdAt: String,
)
