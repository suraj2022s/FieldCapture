package app.fieldcapture.quality

import kotlin.math.ln

/** What the capture is for. Framing rules differ: a nameplate must have readable text fully in view. */
enum class Purpose {
  NAMEPLATE,
  DAMAGE,
}

/**
 * Why a frame was rejected, with the one short spoken prompt a tech hears.
 * Listed in the order prompts are given: fix the light first, then motion, then framing.
 */
enum class Issue(val prompt: String) {
  TOO_DARK("Too dark. Turn on your light."),
  BLURRY("Hold still."),
  CUT_OFF("Label is cut off. Step back a little."),
  TOO_FAR("Too far. Move closer to the label."),
  GLARE("Glare on the label. Tilt your head a little."),
  NO_TEXT("I can't see a label. Point at the nameplate."),
}

/** OCR text lines found in a frame, in normalised coordinates. */
data class TextLayout(val lines: List<NormBox>) {
  val union: NormBox?
    get() = NormBox.union(lines)

  fun medianLineHeight(): Double {
    if (lines.isEmpty()) return 0.0
    val hs = lines.map { it.height }.sorted()
    return hs[hs.size / 2]
  }
}

/**
 * Decision thresholds. [SEED] values are placeholders chosen before any data was collected;
 * the shipped values are measured on labelled frames (eval/qc_thresholds.py) and loaded from
 * thresholds.json. Units: sharpness = Laplacian variance at 360 px working scale; luma 0..255.
 */
data class Thresholds(
    val minSharpness: Double,
    val minMeanLuma: Double,
    val maxDarkFraction: Double,
    /** Glare limit for damage frames (whole frame). */
    val maxClippedFraction: Double,
    /** Glare limit inside the text region of a nameplate. */
    val maxTextClippedFraction: Double,
    /** Median OCR line height as a fraction of frame height; below this the text is too small to read. */
    val minTextLineHeight: Double,
    /** Text must stay this far (fraction of frame) from every edge, or it is probably cut off. */
    val minEdgeMargin: Double,
) {
  companion object {
    val SEED =
        Thresholds(
            minSharpness = 40.0,
            minMeanLuma = 45.0,
            maxDarkFraction = 0.60,
            maxClippedFraction = 0.10,
            maxTextClippedFraction = 0.06,
            minTextLineHeight = 0.018,
            minEdgeMargin = 0.02,
        )
  }
}

data class Verdict(
    val ok: Boolean,
    val issues: List<Issue>,
    /** Ranking score for best-frame selection; only comparable between frames of the same capture. */
    val score: Double,
    val metrics: FrameMetrics,
    val textMetrics: FrameMetrics?,
) {
  /** The single prompt to speak, if any. */
  val prompt: String?
    get() = issues.firstOrNull()?.prompt
}

object QualityGate {
  /**
   * Judges one frame.
   *
   * @param frame metrics of the whole frame.
   * @param text OCR layout (nameplates only). Null means OCR did not run; empty means it found nothing.
   * @param textMetrics metrics measured inside the text region. Blur and glare are judged there for
   *   nameplates, because a sharp label in front of a blurry background is still a good capture.
   */
  fun evaluate(
      frame: FrameMetrics,
      purpose: Purpose,
      t: Thresholds,
      text: TextLayout? = null,
      textMetrics: FrameMetrics? = null,
  ): Verdict {
    val issues = linkedSetOf<Issue>()
    if (frame.meanLuma < t.minMeanLuma || frame.darkFraction > t.maxDarkFraction) {
      issues += Issue.TOO_DARK
    }
    val focus = textMetrics ?: frame
    if (focus.sharpness < t.minSharpness) issues += Issue.BLURRY

    when (purpose) {
      Purpose.NAMEPLATE -> {
        if (text != null) {
          val union = text.union
          if (union == null) {
            issues += Issue.NO_TEXT
          } else {
            val m = t.minEdgeMargin
            if (union.left < m || union.top < m || union.right > 1 - m || union.bottom > 1 - m) {
              issues += Issue.CUT_OFF
            }
            if (text.medianLineHeight() < t.minTextLineHeight) issues += Issue.TOO_FAR
          }
        }
        if (focus.clippedFraction > t.maxTextClippedFraction) issues += Issue.GLARE
      }
      Purpose.DAMAGE -> if (frame.clippedFraction > t.maxClippedFraction) issues += Issue.GLARE
    }

    val ordered = issues.sortedBy { it.ordinal }
    return Verdict(ordered.isEmpty(), ordered, score(frame, focus, t), frame, textMetrics)
  }

  /**
   * Higher is better. Dominated by sharpness of the region that matters, with penalties for
   * darkness and glare so a sharp-but-black frame never wins.
   */
  private fun score(frame: FrameMetrics, focus: FrameMetrics, t: Thresholds): Double {
    var s = ln(1.0 + focus.sharpness)
    if (frame.meanLuma < t.minMeanLuma) s -= 2.0 * (t.minMeanLuma - frame.meanLuma) / t.minMeanLuma
    s -= 10.0 * focus.clippedFraction
    return s
  }
}

/** A frame under consideration for one capture. */
data class Candidate<T>(val payload: T, val verdict: Verdict, val timeMs: Long)

object BestFrame {
  /** Best passing frame, or null when none passes. */
  fun <T> bestPassing(candidates: List<Candidate<T>>): Candidate<T>? =
      candidates.filter { it.verdict.ok }.maxByOrNull { it.verdict.score }

  /** When nothing passes: fewest issues, then highest score. Used for "capture anyway" and timeouts. */
  fun <T> leastBad(candidates: List<Candidate<T>>): Candidate<T>? =
      candidates.minWithOrNull(
          compareBy<Candidate<T>> { it.verdict.issues.size }.thenByDescending { it.verdict.score },
      )
}
