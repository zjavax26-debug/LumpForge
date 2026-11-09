package com.lumpforge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * Decodes common Doom PC graphics:
 * - classic column-post Doom patches using PLAYPAL
 * - PNG and other bitmap formats supported by Android BitmapFactory
 */
object WadGraphicDecoder {
    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 4_194_304L

    fun decodeGraphic(data: ByteArray, palette: ByteArray?): Bitmap? {
        if (data.isEmpty()) return null

        // Doom 64 PC ports store their graphic resources as standalone PNG images.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width > MAX_DIMENSION || height > MAX_DIMENSION ||
                width.toLong() * height.toLong() > MAX_PIXELS
            ) return null

            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bitmap != null) return bitmap
        }

        // Fall back to the classic Doom picture patch format.
        return palette?.let { decodePatch(data, it) }
    }

    fun decodePatch(data: ByteArray, palette: ByteArray): Bitmap? {
        if (data.size < 8 || palette.size < 768) return null

        val width = u16(data, 0)
        val height = u16(data, 2)
        if (width !in 1..2048 || height !in 1..2048) return null
        if (width.toLong() * height.toLong() > MAX_PIXELS) return null
        if (8L + width * 4L > data.size.toLong()) return null

        val pixels = IntArray(width * height) { Color.TRANSPARENT }
        val columnTable = 8
        var decodedPosts = 0

        for (x in 0 until width) {
            var cursor = u32(data, columnTable + x * 4)
            if (cursor < 8 || cursor >= data.size) return null
            var previousTop = -1
            var postsInColumn = 0

            while (cursor < data.size) {
                val topDelta = data[cursor].toInt() and 0xff
                cursor++
                if (topDelta == 255) break
                if (cursor + 2 > data.size) return null

                val length = data[cursor].toInt() and 0xff
                cursor++ // post length
                cursor++ // unused leading byte
                if (cursor + length + 1 > data.size) return null

                val top = if (topDelta <= previousTop) previousTop + topDelta else topDelta
                previousTop = top
                for (i in 0 until length) {
                    val y = top + i
                    val paletteIndex = data[cursor + i].toInt() and 0xff
                    if (y in 0 until height) {
                        val p = paletteIndex * 3
                        val red = palette[p].toInt() and 0xff
                        val green = palette[p + 1].toInt() and 0xff
                        val blue = palette[p + 2].toInt() and 0xff
                        pixels[y * width + x] = Color.rgb(red, green, blue)
                    }
                }
                cursor += length
                cursor++ // unused trailing byte
                postsInColumn++
                decodedPosts++
                if (postsInColumn > height + 1 || decodedPosts > data.size) return null
            }
        }

        if (decodedPosts == 0) return null
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
        return value.toInt()
    }
}
