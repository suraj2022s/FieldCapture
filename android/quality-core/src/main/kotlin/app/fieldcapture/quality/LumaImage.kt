package app.fieldcapture.quality

/**
 * An 8-bit luma (Y) plane, row-major and tightly packed (stride == width).
 *
 * Quality metrics only need brightness, so every source (glasses stream frame, still photo,
 * eval JPEG) is reduced to luma at a fixed working scale first. That is what lets one blur
 * threshold work for the 360p, 504p and 720p stream qualities the SDK can deliver.
 */
class LumaImage(val width: Int, val height: Int, val pixels: ByteArray) {
  init {
    require(width > 0 && height > 0 && pixels.size >= width * height) {
      "bad luma image ${width}x$height (bytes=${pixels.size})"
    }
  }

  fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

  companion object {
    /** Short side of the working image all metrics are computed at. */
    const val WORKING_SHORT_SIDE = 360

    /**
     * Area-average resample of a luma plane so its short side is [targetShort] pixels.
     * Images already at or below the target are copied (dropping any row padding).
     */
    fun fromPlane(
        src: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        targetShort: Int = WORKING_SHORT_SIDE,
    ): LumaImage {
      require(rowStride >= width && src.size >= rowStride * (height - 1) + width) {
        "plane too small for ${width}x$height stride=$rowStride (bytes=${src.size})"
      }
      val shortSide = minOf(width, height)
      if (shortSide <= targetShort) {
        val out = ByteArray(width * height)
        for (y in 0 until height) System.arraycopy(src, y * rowStride, out, y * width, width)
        return LumaImage(width, height, out)
      }
      val scale = shortSide.toDouble() / targetShort
      val outW = (width / scale).toInt().coerceAtLeast(1)
      val outH = (height / scale).toInt().coerceAtLeast(1)
      val out = ByteArray(outW * outH)
      for (oy in 0 until outH) {
        val y0 = (oy * scale).toInt()
        val y1 = minOf(height, maxOf(y0 + 1, ((oy + 1) * scale).toInt()))
        for (ox in 0 until outW) {
          val x0 = (ox * scale).toInt()
          val x1 = minOf(width, maxOf(x0 + 1, ((ox + 1) * scale).toInt()))
          var sum = 0
          var n = 0
          for (y in y0 until y1) {
            val row = y * rowStride
            for (x in x0 until x1) {
              sum += src[row + x].toInt() and 0xFF
              n++
            }
          }
          out[oy * outW + ox] = (sum / n).toByte()
        }
      }
      return LumaImage(outW, outH, out)
    }

    /** Luma from packed ARGB pixels (e.g. android.graphics.Bitmap.getPixels or java BufferedImage). */
    fun fromArgb(argb: IntArray, width: Int, height: Int, targetShort: Int = WORKING_SHORT_SIDE): LumaImage {
      val y = ByteArray(width * height)
      for (i in 0 until width * height) {
        val c = argb[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        // BT.601 luma, the same weighting the camera's YUV uses
        y[i] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
      }
      return fromPlane(y, width, height, width, targetShort)
    }
  }
}

/** Integer pixel box in a [LumaImage]'s coordinates; right/bottom exclusive. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top

  fun clampTo(img: LumaImage): Box =
      Box(
          left.coerceIn(0, img.width),
          top.coerceIn(0, img.height),
          right.coerceIn(0, img.width),
          bottom.coerceIn(0, img.height),
      )

  companion object {
    fun full(img: LumaImage) = Box(0, 0, img.width, img.height)
  }
}

/** Box in normalised image coordinates (0..1), e.g. an OCR text line, independent of resolution. */
data class NormBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
  val height: Double get() = bottom - top
  val width: Double get() = right - left

  fun toBox(img: LumaImage): Box =
      Box(
          (left * img.width).toInt(),
          (top * img.height).toInt(),
          (right * img.width).toInt(),
          (bottom * img.height).toInt(),
      )

  companion object {
    fun union(boxes: List<NormBox>): NormBox? =
        if (boxes.isEmpty()) null
        else
            NormBox(
                boxes.minOf { it.left },
                boxes.minOf { it.top },
                boxes.maxOf { it.right },
                boxes.maxOf { it.bottom },
            )
  }
}
