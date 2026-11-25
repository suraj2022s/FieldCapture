package app.fieldcapture.glasses

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.meta.wearable.dat.camera.types.PhotoData
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Converts what the glasses hand us into JPEG, the format every downstream consumer reads. */
object PhotoCodec {

  data class Jpeg(val bytes: ByteArray, val width: Int, val height: Int, val sourceFormat: String)

  /** `capturePhoto()` returns either a decoded Bitmap or HEIC bytes; vision APIs don't take HEIC. */
  fun toJpeg(data: PhotoData, quality: Int = 92): Jpeg? =
      when (data) {
        is PhotoData.Bitmap -> encode(data.bitmap, quality, "bitmap")
        is PhotoData.HEIC -> {
          val buf = data.data.duplicate().apply { rewind() }
          val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
          // ImageDecoder applies the HEIC's orientation metadata.
          val bmp =
              ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { dec, _, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
              }
          encode(bmp, quality, "heic")
        }
        else -> null
      }

  fun encode(bmp: Bitmap, quality: Int, sourceFormat: String): Jpeg {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Jpeg(out.toByteArray(), bmp.width, bmp.height, sourceFormat)
  }

  /** Candidate 4:2:0 layouts for raw stream frames. The SDK documents only "YUV pixel data". */
  enum class YuvLayout {
    I420,
    NV21,
    NV12,
  }

  /** Encodes a raw stream frame as JPEG assuming [layout]. Returns null if the buffer is too small. */
  fun yuvToJpeg(frame: FrameRingBuffer.Frame, layout: YuvLayout, quality: Int = 90): ByteArray? {
    val w = frame.width
    val h = frame.height
    val ySize = w * h
    val cSize = ySize / 4
    if (frame.size < ySize + 2 * cSize) return null
    val nv21 = ByteArray(ySize + 2 * cSize)
    System.arraycopy(frame.data, 0, nv21, 0, ySize)
    when (layout) {
      YuvLayout.I420 -> {
        val u = ySize
        val v = ySize + cSize
        for (i in 0 until cSize) {
          nv21[ySize + 2 * i] = frame.data[v + i]
          nv21[ySize + 2 * i + 1] = frame.data[u + i]
        }
      }
      YuvLayout.NV21 -> System.arraycopy(frame.data, ySize, nv21, ySize, 2 * cSize)
      YuvLayout.NV12 ->
          for (i in 0 until cSize) {
            nv21[ySize + 2 * i] = frame.data[ySize + 2 * i + 1]
            nv21[ySize + 2 * i + 1] = frame.data[ySize + 2 * i]
          }
    }
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
    return out.toByteArray()
  }
}
