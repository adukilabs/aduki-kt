package pro.aduki.android.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Thumbnails scales a contact photo on the device before
 * `Contacts.setPhoto`: the server keeps one size and has no image codec.
 */
object Thumbnails {
    /** The longest side `scale` writes by default, pixels. */
    const val SIZE = 512

    /**
     * [bytes] (any image `BitmapFactory` reads) as a JPEG whose longest side
     * is at most [size] pixels, turned upright by its EXIF orientation.
     * Returns null when the bytes are not an image.
     */
    fun scale(bytes: ByteArray, size: Int = SIZE, quality: Int = 85): ByteArray? {
        require(size in 16..1024) { "size must be 16 to 1024" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode at the smallest power of two that stays at or above [size].
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val ratio = size.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix()
        if (ratio < 1f) matrix.postScale(ratio, ratio)
        matrix.postRotate(rotation(bytes))
        val scaled = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return out.toByteArray()
    }

    private fun rotation(bytes: ByteArray): Float = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
}
