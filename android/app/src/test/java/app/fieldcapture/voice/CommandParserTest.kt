package app.fieldcapture.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

  private fun cmd(s: String) = CommandParser.parse(s)?.command

  @Test
  fun briefExamplesParse() {
    assertEquals(Command.CaptureNameplate, cmd("capture nameplate"))
    assertEquals(
        Command.CaptureDamage("kitchen", "north wall", "kitchen north wall"),
        cmd("capture damage, kitchen, north wall"),
    )
    assertEquals(Command.Note("subfloor soft by the cabinet"), cmd("note: subfloor soft by the cabinet"))
  }

  @Test
  fun nameplateVariantsFromAsr() {
    listOf(
            "Capture name plate",
            "capture the nameplate",
            "take the nameplate",
            "capture data plate",
            "capture rating plate",
            "Capture label.",
        )
        .forEach { assertEquals(it, Command.CaptureNameplate, cmd(it)) }
  }

  @Test
  fun damageSlotsAreOrderIndependentAndOptional() {
    assertEquals(Command.CaptureDamage("kitchen", "north wall", "north wall kitchen"), cmd("capture damage north wall kitchen"))
    val ceilingOnly = cmd("capture damage ceiling") as Command.CaptureDamage
    assertNull(ceilingOnly.room)
    assertEquals("ceiling", ceilingOnly.surface)
    val vanity = cmd("capture damage in the master bathroom under the sink") as Command.CaptureDamage
    assertEquals("master bathroom", vanity.room)
    assertEquals("cabinet", vanity.surface)
  }

  @Test
  fun damageKeepsUnknownWordsForTheBackOffice() {
    val d = cmd("capture damage sunroom skylight") as Command.CaptureDamage
    assertNull(d.room)
    assertNull(d.surface)
    assertEquals("sunroom skylight", d.unparsed)
  }

  @Test
  fun numberedRoomsAndTypos() {
    assertEquals("bedroom 2", (cmd("capture damage bedroom two east wall") as Command.CaptureDamage).room)
    assertEquals("kitchen", (cmd("capture damage kitchin celing") as Command.CaptureDamage).room)
    assertEquals("ceiling", (cmd("capture damage kitchin celing") as Command.CaptureDamage).surface)
  }

  @Test
  fun noteVariants() {
    assertEquals(Command.Note("musty smell in the hallway closet"), cmd("Note. Musty smell in the hallway closet"))
    assertEquals(Command.Note("water heater pan has standing water"), cmd("note that water heater pan has standing water"))
    assertEquals(Command.Note("customer says leak started tuesday"), cmd("add a note customer says leak started Tuesday"))
    assertNull("empty note is ignored", cmd("note"))
  }

  @Test
  fun lifecycleAndRecoveryCommands() {
    assertEquals(Command.PauseJob, cmd("pause job"))
    assertEquals(Command.ResumeJob, cmd("resume the job"))
    assertEquals(Command.EndJob, cmd("end job"))
    assertEquals(Command.StartJob, cmd("start job"))
    assertEquals(Command.CaptureAnyway, cmd("capture anyway"))
    assertEquals(Command.CaptureAnyway, cmd("keep it"))
    assertEquals(Command.Retake, cmd("retake"))
    assertEquals(Command.UndoLast, cmd("undo last"))
    assertEquals(Command.Status, cmd("status"))
  }

  @Test
  fun chatterIsIgnored() {
    listOf("", "hello", "the wall looks fine", "can you hand me the tape", "capture").forEach { assertNull(it, cmd(it)) }
  }

  @Test
  fun bestHypothesisIsTheFirstThatParses() {
    val p = CommandParser.parseBest(listOf("captain name plate", "capture name plate"), listOf(0.6f, 0.3f))
    assertEquals(Command.CaptureNameplate, p?.command)
    assertEquals(0.3, p!!.confidence, 1e-6)
  }

  @Test
  fun missingSlotsLowerConfidence() {
    val full = CommandParser.parse("capture damage kitchen north wall", 0.9)!!.confidence
    val partial = CommandParser.parse("capture damage", 0.9)!!.confidence
    assertTrue(partial < full)
  }

  @Test
  fun spokenNumbers() {
    assertEquals(22.5, SpokenNumbers.parse("twenty two point five")!!, 1e-9)
    assertEquals(18.0, SpokenNumbers.parse("18 percent")!!, 1e-9)
    assertEquals(105.0, SpokenNumbers.parse("one hundred and five")!!, 1e-9)
    assertEquals(7.0, SpokenNumbers.parse("seven")!!, 1e-9)
    assertNull(SpokenNumbers.parse("kitchen"))
  }
}
