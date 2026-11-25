package app.fieldcapture.quality

import kotlin.math.abs

/** Raw, threshold-free measurements of one frame (or one region of it). */
data class FrameMetrics(
    /** Variance of the 4-neighbour Laplacian at working scale. Low = blurry. */
    val sharpness: Double,
    /** Mean squared Sobel gradient magnitude (Tenengrad). A second blur signal, less noise-sensitive. */
    val tenengrad: Double,
    val meanLuma: Double,
    val p05: Int,
    val p50: Int,
    val p95: Int,
    /** Fraction of pixels darker than [Metrics.DARK_LEVEL]. */
    val darkFraction: Double,
    /** Fraction of pixels at or above [Metrics.CLIP_LEVEL] (blown highlights / glare). */
    val clippedFraction: Double,
    val width: Int,
    val height: Int,
)

object Metrics {
  const val DARK_LEVEL = 32
  const val CLIP_LEVEL = 250

  /** Measures [roi] of [img] (whole image by default). */
  fun compute(img: LumaImage, roi: Box = Box.full(img)): FrameMetrics {
    val r = roi.clampTo(img)
    require(r.width > 2 && r.height > 2) { "roi too small: $r" }
    val w = img.width
    val p = img.pixels

    val hist = IntArray(256)
    var sum = 0L
    for (y in r.top until r.bottom) {
      val row = y * w
      for (x in r.left until r.right) {
        val v = p[row + x].toInt() and 0xFF
        hist[v]++
        sum += v
      }
    }
    val n = r.width * r.height
    var dark = 0
    for (i in 0 until DARK_LEVEL) dark += hist[i]
    var clipped = 0
    for (i in CLIP_LEVEL..255) clipped += hist[i]

    // Laplacian and Sobel need a 1-px border.
    val x0 = maxOf(1, r.left)
    val x1 = minOf(img.width - 1, r.right)
    val y0 = maxOf(1, r.top)
    val y1 = minOf(img.height - 1, r.bottom)
    var lapSum = 0.0
    var lapSq = 0.0
    var gradSum = 0.0
    var m = 0
    for (y in y0 until y1) {
      val row = y * w
      val up = row - w
      val down = row + w
      for (x in x0 until x1) {
        val c = p[row + x].toInt() and 0xFF
        val u = p[up + x].toInt() and 0xFF
        val d = p[down + x].toInt() and 0xFF
        val l = p[row + x - 1].toInt() and 0xFF
        val rt = p[row + x + 1].toInt() and 0xFF
        val lap = (u + d + l + rt - 4 * c).toDouble()
        lapSum += lap
        lapSq += lap * lap

        val ul = p[up + x - 1].toInt() and 0xFF
        val ur = p[up + x + 1].toInt() and 0xFF
        val dl = p[down + x - 1].toInt() and 0xFF
        val dr = p[down + x + 1].toInt() and 0xFF
        val gx = (ur + 2 * rt + dr) - (ul + 2 * l + dl)
        val gy = (dl + 2 * d + dr) - (ul + 2 * u + ur)
        gradSum += (gx * gx + gy * gy).toDouble()
        m++
      }
    }
    val lapMean = if (m > 0) lapSum / m else 0.0
    val lapVar = if (m > 0) lapSq / m - lapMean * lapMean else 0.0

    return FrameMetrics(
        sharpness = lapVar,
        tenengrad = if (m > 0) gradSum / m else 0.0,
        meanLuma = sum.toDouble() / n,
        p05 = percentile(hist, n, 0.05),
        p50 = percentile(hist, n, 0.50),
        p95 = percentile(hist, n, 0.95),
        darkFraction = dark.toDouble() / n,
        clippedFraction = clipped.toDouble() / n,
        width = r.width,
        height = r.height,
    )
  }

  /** Mean absolute luma difference between two same-size working images: a cheap camera-motion signal. */
  fun meanAbsDiff(a: LumaImage, b: LumaImage): Double {
    require(a.width == b.width && a.height == b.height) { "size mismatch" }
    var s = 0L
    for (i in 0 until a.width * a.height) {
      s += abs((a.pixels[i].toInt() and 0xFF) - (b.pixels[i].toInt() and 0xFF))
    }
    return s.toDouble() / (a.width * a.height)
  }

  private fun percentile(hist: IntArray, n: Int, q: Double): Int {
    val target = (q * n).toLong().coerceAtLeast(1)
    var acc = 0L
    for (i in 0..255) {
      acc += hist[i]
      if (acc >= target) return i
    }
    return 255
  }
}
