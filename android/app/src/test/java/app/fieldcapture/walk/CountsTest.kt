package app.fieldcapture.walk

import org.junit.Assert.assertEquals
import org.junit.Test

/** The counts are spoken ("Job ended. 1 capture, 0 notes."), so the grammar is tested. */
class CountsTest {
  @Test
  fun singularAndPlural() {
    assertEquals("1 capture, 1 note", counts(1, 1))
    assertEquals("0 captures, 2 notes", counts(0, 2))
    assertEquals("12 captures, 0 notes", counts(12, 0))
  }
}
