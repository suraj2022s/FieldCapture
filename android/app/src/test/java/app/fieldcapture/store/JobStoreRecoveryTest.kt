package app.fieldcapture.store

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A walk the OS killed is resumed under the same job id; stale ones are closed, never lost. */
class JobStoreRecoveryTest {
  private val root: File = Files.createTempDirectory("jobstore").toFile()
  private val store = JobStore(root)
  private val window = 30 * 60 * 1000L

  private fun ago(seconds: Long) = Instant.now().minusSeconds(seconds).toString()

  private fun note(jobId: String, room: String?) =
      store.saveNote(NoteRecord(noteId = store.newId(), jobId = jobId, createdAt = store.now(), transcript = "t", text = "t", room = room, asrConfidence = 0.9))

  /** Only the fields recovery reads; a real capture record carries many more (ignored on read). */
  private fun capture(jobId: String, createdAt: String, room: String?, deleted: Boolean = false) {
    val roomJson = room?.let { "\"$it\"" } ?: "null"
    File(store.jobDir(jobId), "captures/${store.newId()}.json").apply {
      parentFile?.mkdirs()
      writeText("""{"captureId":"x","createdAt":"$createdAt","room":$roomJson,"deleted":$deleted,"type":"damage"}""")
    }
  }

  @Test
  fun resumesTheOpenJobWithItsCountsRoomAndTimeline() {
    val h = store.startJob(emptyMap(), emptyMap())
    capture(h.jobId, ago(300), "kitchen")
    capture(h.jobId, ago(120), "bathroom")
    capture(h.jobId, ago(60), "garage", deleted = true) // undone: neither counted nor the room
    note(h.jobId, room = null)
    store.saveEvents(h.jobId, listOf(EventRecord(ago(400), "job", "started")))

    val open = store.recoverOpenJobs(window)

    assertNotNull(open)
    assertEquals(h.jobId, open!!.header.jobId)
    assertEquals(2, open.captures)
    assertEquals(1, open.notes)
    assertEquals("bathroom", open.room)
    assertEquals(listOf("started"), open.events.map { it.detail })
  }

  @Test
  fun recentActivityNotStartTimeDecides() {
    val h = store.startJob(emptyMap(), emptyMap())
    val start = Instant.parse(h.createdAt)
    // Two hours into a long walk, with a capture a minute ago.
    capture(h.jobId, start.plusSeconds(7_140).toString(), "attic")
    val now = start.plusSeconds(7_200).toEpochMilli()

    assertEquals(h.jobId, store.recoverOpenJobs(window, nowMs = now)?.header?.jobId)
  }

  @Test
  fun onlyTheMostRecentlyActiveJobIsResumedTheRestAreFinalized() {
    val old = store.startJob(emptyMap(), emptyMap())
    Thread.sleep(5)
    val newest = store.startJob(emptyMap(), emptyMap())

    val open = store.recoverOpenJobs(window)

    assertEquals(newest.jobId, open?.header?.jobId)
    assertTrue(store.pendingItems().any { it.kind == "finalize" && it.jobId == old.jobId })
    assertTrue(store.pendingItems().none { it.kind == "finalize" && it.jobId == newest.jobId })
  }

  @Test
  fun aJobIdleLongerThanTheWindowIsClosedInstead() {
    val h = store.startJob(emptyMap(), emptyMap())
    val later = Instant.parse(h.createdAt).toEpochMilli() + window + 1

    assertNull(store.recoverOpenJobs(window, nowMs = later))
    assertTrue(store.pendingItems().any { it.kind == "finalize" && it.jobId == h.jobId })
    assertNull(store.recoverOpenJobs(window)) // closed for good
  }

  @Test
  fun anEndedJobIsLeftAlone() {
    val h = store.startJob(emptyMap(), emptyMap())
    store.endJob(h, emptyList())
    assertNull(store.recoverOpenJobs(window))
  }
}
