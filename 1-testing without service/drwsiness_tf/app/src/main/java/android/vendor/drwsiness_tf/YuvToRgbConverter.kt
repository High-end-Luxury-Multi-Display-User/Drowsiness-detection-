package android.vendor.drwsiness_tf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

object YuvToRgbConverter {

    fun yuvToRgb(context: Context, image: Image): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val jpegBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        yBuffer.get(nv21, 0, ySize)

        val rowStride = image.planes[2].rowStride
        val pixelStride = image.planes[2].pixelStride

        var offset = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = row * rowStride + col * pixelStride
                nv21[offset++] = vBuffer.get(vuPos)
                nv21[offset++] = uBuffer.get(vuPos)
            }
        }

        return nv21
    }
}
