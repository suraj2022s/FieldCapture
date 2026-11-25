package app.fieldcapture.store

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline-first, crash-safe storage. "Got it" is only said after save returns, and save returns
 * only after every byte is fsync'd and renamed into place, so an acknowledged capture survives
 * a dead battery, a killed app, or no signal for the rest of the day.
 *
 * Layout under [root]:
 *   jobs/{jobId}/job.json, events.json
 *   jobs/{jobId}/captures/{captureId}.jpg|.json (+ {captureId}_stream.jpg)
 *   jobs/{jobId}/notes/{noteId}.json
 *   outbox/{itemId}.json           pending upload markers, deleted only after the server acks
 */
class JobStore(private val root: File) {
  val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  private val outboxDir = File(root, "outbox").apply { mkdirs() }
  private val _pending = MutableStateFlow(countPending())
  /** Number of items waiting for signal. */
  val pending: StateFlow<Int> = _pending.asStateFlow()

  fun jobDir(jobId: String) = File(root, "jobs/$jobId")

  fun newId(): String = UUID.randomUUID().toString()

  fun now(): String = Instant.now().toString()

  @Synchronized
  fun startJob(app: Map<String, String>, device: Map<String, String>): JobHeader {
    val header = JobHeader(jobId = newId(), createdAt = now(), app = app, device = device)
    writeHeader(header)
    return header
  }

  @Synchronized
  fun endJob(header: JobHeader, events: List<EventRecord>): JobHeader {
    val ended = header.copy(endedAt = now())
    writeHeader(ended)
    saveEvents(ended.jobId, events)
    enqueue(OutboxItem("finalize-${ended.jobId}", "finalize", ended.jobId, "jobs/${ended.jobId}/job.json", null, now()))
    return ended
  }

  private fun writeHeader(h: JobHeader) {
    writeAtomic(File(jobDir(h.jobId), "job.json"), json.encodeToString(h).toByteArray())
    enqueue(OutboxItem("job-${h.jobId}", "job", h.jobId, "jobs/${h.jobId}/job.json", null, now()))
  }

  @Synchronized
  fun saveEvents(jobId: String, events: List<EventRecord>) {
    writeAtomic(File(jobDir(jobId), "events.json"), json.encodeToString(events).toByteArray())
    enqueue(OutboxItem("events-$jobId", "events", jobId, "jobs/$jobId/events.json", null, now()))
  }

  fun loadEvents(jobId: String): List<EventRecord> =
      runCatching { json.decodeFromString<List<EventRecord>>(File(jobDir(jobId), "events.json").readText()) }
          .getOrDefault(emptyList())

  /**
   * Jobs that were started but never ended: the OS killed the app mid-walk (ColorOS does this).
   * The most recently active one is returned if its last activity is within [resumeWindowMs], so
   * the walk carries on under the same job id. The rest are closed, so their record is still
   * finalized; nothing is deleted. A short window matters: a job resumed hours later, at another
   * house, would file captures under the wrong address.
   */
  @Synchronized
  fun recoverOpenJobs(
      resumeWindowMs: Long,
      nowMs: Long = System.currentTimeMillis(),
      onClosed: (JobHeader) -> Unit = {},
  ): OpenJob? {
    val open =
        (File(root, "jobs").listFiles() ?: emptyArray())
            .mapNotNull { dir -> runCatching { json.decodeFromString<JobHeader>(File(dir, "job.json").readText()) }.getOrNull() }
            .filter { it.endedAt == null }
            .map { snapshot(it) }
            .sortedByDescending { it.lastActivityMs }
    val resumable = open.firstOrNull()?.takeIf { nowMs - it.lastActivityMs in 0 until resumeWindowMs }
    for (j in open) {
      if (j === resumable) continue
      endJob(j.header, j.events + EventRecord(now(), "job", "closed automatically: the app stopped mid-walk and the job was not resumed in time"))
      onClosed(j.header)
    }
    return resumable
  }

  private fun snapshot(h: JobHeader): OpenJob {
    val captures = summaries(File(jobDir(h.jobId), "captures"))
    val notes = summaries(File(jobDir(h.jobId), "notes"))
    val events = loadEvents(h.jobId)
    val times = listOf(h.createdAt) + (captures + notes).map { it.createdAt } + events.map { it.at }
    return OpenJob(
        header = h,
        captures = captures.size,
        notes = notes.size,
        room = (captures + notes).filter { it.room != null }.maxByOrNull { epochMs(it.createdAt) }?.room,
        events = events,
        lastActivityMs = times.maxOf { epochMs(it) },
    )
  }

  /** Live (not undone) records in a captures/ or notes/ folder, read just far enough to count them. */
  private fun summaries(dir: File): List<RecordSummary> =
      (dir.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith(".") } ?: emptyArray())
          .mapNotNull { f -> runCatching { json.decodeFromString<RecordSummary>(f.readText()) }.getOrNull() }
          .filter { !it.deleted }

  /** Writes evidence, then metadata, then the outbox marker, each fsync'd. */
  @Synchronized
  fun saveCapture(record: CaptureRecord, evidenceJpeg: ByteArray, streamJpeg: ByteArray?): CaptureRecord {
    val dir = File(jobDir(record.jobId), "captures")
    writeAtomic(File(dir, record.evidence.file), evidenceJpeg)
    if (streamJpeg != null && record.evidence.streamFrameFile != null) {
      writeAtomic(File(dir, record.evidence.streamFrameFile), streamJpeg)
    }
    val metaPath = "jobs/${record.jobId}/captures/${record.captureId}.json"
    writeAtomic(File(root, metaPath), json.encodeToString(record).toByteArray())
    enqueue(
        OutboxItem(
            record.captureId,
            "capture",
            record.jobId,
            metaPath,
            "jobs/${record.jobId}/captures/${record.evidence.file}",
            now(),
        ),
    )
    return record
  }

  @Synchronized
  fun saveNote(note: NoteRecord): NoteRecord {
    val metaPath = "jobs/${note.jobId}/notes/${note.noteId}.json"
    writeAtomic(File(root, metaPath), json.encodeToString(note).toByteArray())
    enqueue(OutboxItem(note.noteId, "note", note.jobId, metaPath, null, now()))
    return note
  }

  /** Re-uploads a record with deleted=true ("undo last"); the server drops it from the job record. */
  @Synchronized
  fun markCaptureDeleted(record: CaptureRecord) =
      saveCaptureMeta(record.copy(deleted = true))

  @Synchronized
  fun markNoteDeleted(note: NoteRecord) = saveNote(note.copy(deleted = true))

  private fun saveCaptureMeta(record: CaptureRecord): CaptureRecord {
    val metaPath = "jobs/${record.jobId}/captures/${record.captureId}.json"
    writeAtomic(File(root, metaPath), json.encodeToString(record).toByteArray())
    enqueue(
        OutboxItem(record.captureId, "capture", record.jobId, metaPath, "jobs/${record.jobId}/captures/${record.evidence.file}", now()),
    )
    return record
  }

  // ------------------------------------------------------------------ outbox

  @Synchronized
  fun pendingItems(): List<OutboxItem> =
      (outboxDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
          .mapNotNull { f -> runCatching { json.decodeFromString<OutboxItem>(f.readText()) }.getOrNull() }
          // jobs first, then captures/notes, events, finalize last — order the server finds natural
          .sortedWith(compareBy({ KIND_ORDER.indexOf(it.kind) }, { it.createdAt }))

  @Synchronized
  fun ack(item: OutboxItem) {
    File(outboxDir, "${item.id}.json").delete()
    _pending.value = countPending()
  }

  /** A permanently rejected item moves to outbox-rejected/ (kept for inspection, never silently lost). */
  @Synchronized
  fun reject(item: OutboxItem) {
    val dead = File(root, "outbox-rejected").apply { mkdirs() }
    File(outboxDir, "${item.id}.json").renameTo(File(dead, "${item.id}.json"))
    _pending.value = countPending()
  }

  fun file(relative: String) = File(root, relative)

  private fun enqueue(item: OutboxItem) {
    // Same id = same file: re-enqueueing an upsert (e.g. events.json) just refreshes the marker.
    writeAtomic(File(outboxDir, "${item.id}.json"), json.encodeToString(item).toByteArray())
    _pending.value = countPending()
  }

  private fun countPending(): Int = outboxDir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

  companion object {
    private val KIND_ORDER = listOf("job", "capture", "note", "events", "finalize")

    /** ISO-8601 strings from Instant.toString() drop trailing zeros, so they don't sort as text. */
    private fun epochMs(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Write to a temp file, fsync, then rename: readers see the old file or the whole new one. */
    fun writeAtomic(file: File, bytes: ByteArray) {
      file.parentFile?.mkdirs()
      val tmp = File(file.parentFile, ".${file.name}.tmp")
      FileOutputStream(tmp).use {
        it.write(bytes)
        it.fd.sync()
      }
      if (!tmp.renameTo(file)) {
        file.delete()
        check(tmp.renameTo(file)) { "could not move $tmp to $file" }
      }
    }
  }
}
