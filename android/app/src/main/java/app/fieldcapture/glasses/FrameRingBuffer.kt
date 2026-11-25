package app.fieldcapture.glasses

import java.nio.ByteBuffer

/**
 * The last ~1.5 s of raw stream frames, in reused buffers (no per-frame allocation on a 4 GB phone).
 *
 * A voice command arrives after the tech has already looked at the thing (speech recognition takes
 * ~0.5–1.5 s), so a capture must be able to reach back in time: the best frame is chosen from what
 * the glasses saw while the words were being spoken, not only from what comes after.
 */
class FrameRingBuffer(private val capacity: Int = 24) {

  /** An owned copy of one frame. */
  class Frame(
      val data: ByteArray,
      val size: Int,
      val width: Int,
      val height: Int,
      val ptsUs: Long,
      val tMs: Long,
      val seq: Long,
  )

  private class Slot {
    var data = ByteArray(0)
    var size = 0
    var width = 0
    var height = 0
    var ptsUs = 0L
    var tMs = 0L
    var seq = 0L
  }

  private val slots = Array(capacity) { Slot() }
  private var next = 0
  private var seq = 0L

  @Synchronized
  fun push(buffer: ByteBuffer, width: Int, height: Int, ptsUs: Long, tMs: Long): Long {
    val slot = slots[next]
    val src = buffer.duplicate()
    val n = src.remaining()
    if (slot.data.size < n) slot.data = ByteArray(n)
    src.get(slot.data, 0, n)
    slot.size = n
    slot.width = width
    slot.height = height
    slot.ptsUs = ptsUs
    slot.tMs = tMs
    slot.seq = ++seq
    next = (next + 1) % capacity
    return slot.seq
  }

  @Synchronized fun latest(): Frame? = slots.filter { it.seq > 0 }.maxByOrNull { it.seq }?.copy()

  /** Copy of a specific frame if it is still in the buffer. */
  @Synchronized fun bySeq(seq: Long): Frame? = slots.firstOrNull { it.seq == seq }?.copy()

  @Synchronized
  fun clear() {
    slots.forEach { it.seq = 0 }
  }

  private fun Slot.copy() = Frame(data.copyOf(size), size, width, height, ptsUs, tMs, seq)
}
