package app.fieldcapture.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGateTest {

  private fun image(w: Int, h: Int, f: (Int, Int) -> Int): LumaImage {
    val px = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) px[y * w + x] = f(x, y).coerceIn(0, 255).toByte()
    return LumaImage(w, h, px)
  }

  /** Text-like high-frequency pattern at mid exposure. */
  private fun sharp(w: Int = 360, h: Int = 640) =
      image(w, h) { x, y -> if (((x / 4) + (y / 4)) % 2 == 0) 60 else 200 }

  private fun boxBlur(img: LumaImage, radius: Int): LumaImage =
      image(img.width, img.height) { x, y ->
        var s = 0
        var n = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
          val xx = (x + dx).coerceIn(0, img.width - 1)
          val yy = (y + dy).coerceIn(0, img.height - 1)
          s += img.at(xx, yy)
          n++
        }
        s / n
      }

  private val t = Thresholds.SEED

  @Test
  fun sharpWellLitFramePasses() {
    val v = QualityGate.evaluate(Metrics.compute(sharp()), Purpose.DAMAGE, t)
    assertTrue(v.issues.toString(), v.ok)
  }

  @Test
  fun blurDropsSharpnessByOrdersOfMagnitude() {
    val s = Metrics.compute(sharp()).sharpness
    val b = Metrics.compute(boxBlur(sharp(), 4)).sharpness
    assertTrue("sharp=$s blurred=$b", b < s / 20)
  }

  @Test
  fun blurryFrameAsksToHoldStill() {
    val v = QualityGate.evaluate(Metrics.compute(boxBlur(sharp(), 6)), Purpose.DAMAGE, t)
    assertFalse(v.ok)
    assertEquals(Issue.BLURRY, v.issues.first())
    assertEquals("Hold still.", v.prompt)
  }

  @Test
  fun darkFrameAsksForLightFirst() {
    val dark = image(360, 640) { x, y -> if (((x / 4) + (y / 4)) % 2 == 0) 2 else 20 }
    val v = QualityGate.evaluate(Metrics.compute(dark), Purpose.DAMAGE, t)
    assertFalse(v.ok)
    assertEquals(Issue.TOO_DARK, v.issues.first())
  }

  @Test
  fun glareOnDamageFrameIsCaught() {
    val glare = image(360, 640) { x, y -> if (y < 200) 255 else if (((x / 4) + (y / 4)) % 2 == 0) 60 else 200 }
    val v = QualityGate.evaluate(Metrics.compute(glare), Purpose.DAMAGE, t)
    assertTrue(v.issues.contains(Issue.GLARE))
  }

  @Test
  fun nameplateTouchingEdgeIsCutOff() {
    val m = Metrics.compute(sharp())
    val text = TextLayout(listOf(NormBox(0.0, 0.40, 0.70, 0.45), NormBox(0.10, 0.46, 0.80, 0.51)))
    val v = QualityGate.evaluate(m, Purpose.NAMEPLATE, t, text, m)
    assertTrue(v.issues.contains(Issue.CUT_OFF))
  }

  @Test
  fun tinyTextIsTooFar() {
    val m = Metrics.compute(sharp())
    val text = TextLayout(listOf(NormBox(0.45, 0.50, 0.55, 0.505), NormBox(0.45, 0.51, 0.55, 0.515)))
    val v = QualityGate.evaluate(m, Purpose.NAMEPLATE, t, text, m)
    assertTrue(v.issues.contains(Issue.TOO_FAR))
  }

  @Test
  fun nameplateWithNoTextFound() {
    val m = Metrics.compute(sharp())
    val v = QualityGate.evaluate(m, Purpose.NAMEPLATE, t, TextLayout(emptyList()), m)
    assertEquals(listOf(Issue.NO_TEXT), v.issues)
  }

  @Test
  fun goodNameplatePasses() {
    val m = Metrics.compute(sharp())
    val text = TextLayout(listOf(NormBox(0.2, 0.40, 0.8, 0.43), NormBox(0.2, 0.45, 0.8, 0.48)))
    val v = QualityGate.evaluate(m, Purpose.NAMEPLATE, t, text, m)
    assertTrue(v.issues.toString(), v.ok)
  }

  @Test
  fun downscaleKeepsMetricsComparableAcrossResolutions() {
    // Same pattern at 720p and 360p should land near the same working-scale sharpness.
    val big = image(720, 1280) { x, y -> if (((x / 8) + (y / 8)) % 2 == 0) 60 else 200 }
    val small = image(360, 640) { x, y -> if (((x / 4) + (y / 4)) % 2 == 0) 60 else 200 }
    val a = Metrics.compute(LumaImage.fromPlane(big.pixels, 720, 1280)).sharpness
    val b = Metrics.compute(LumaImage.fromPlane(small.pixels, 360, 640)).sharpness
    assertTrue("720p=$a 360p=$b", a / b in 0.8..1.25)
  }

  @Test
  fun bestFramePrefersPassingThenSharpest() {
    val good = Metrics.compute(sharp())
    val soft = Metrics.compute(boxBlur(sharp(), 1))
    val bad = Metrics.compute(boxBlur(sharp(), 6))
    val cands =
        listOf(
            Candidate("bad", QualityGate.evaluate(bad, Purpose.DAMAGE, t), 1),
            Candidate("soft", QualityGate.evaluate(soft, Purpose.DAMAGE, t), 2),
            Candidate("good", QualityGate.evaluate(good, Purpose.DAMAGE, t), 3),
        )
    assertEquals("good", BestFrame.bestPassing(cands)?.payload)
    assertEquals("bad", BestFrame.leastBad(cands.take(1))?.payload)
  }
}
