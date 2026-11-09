package com.lumpforge.app

import android.graphics.Bitmap
import android.graphics.Color

data class WadTexturePatch(
    val name: String,
    val originX: Int,
    val originY: Int
)

data class WadTextureDefinition(
    val name: String,
    val width: Int,
    val height: Int,
    val patches: List<WadTexturePatch>,
    val sourceLump: String
)

data class WadTextureRenderResult(
    val bitmap: Bitmap,
    val missingPatches: List<String>
)

/**
 * Reads Doom PNAMES and TEXTURE1/TEXTURE2 tables and composes classic textures.
 * It does not modify the source WAD.
 */
object WadTextureReader {
    private const val MAX_PATCH_NAMES = 65_535
    private const val MAX_TEXTURES = 65_535
    private const val MAX_PATCHES_PER_TEXTURE = 4_096
    private const val MAX_TEXTURE_PIXELS = 4_194_304L

    fun parsePatchNames(data: ByteArray): List<String> {
        require(data.size >= 4) { "PNAMES está incompleto." }
        val count = u32(data, 0)
        require(count <= MAX_PATCH_NAMES) { "PNAMES contiene demasiados nombres." }
        val required = 4L + count.toLong() * 8L
        require(required <= data.size.toLong()) { "La tabla PNAMES está truncada." }

        return List(count) { index ->
            val start = 4 + index * 8
            fixedName(data, start, 8)
        }
    }

    fun parseTextureLump(
        data: ByteArray,
        patchNames: List<String>,
        sourceLump: String
    ): List<WadTextureDefinition> {
        require(data.size >= 4) { "$sourceLump está incompleto." }
        val count = u32(data, 0)
        require(count <= MAX_TEXTURES) { "$sourceLump declara demasiadas texturas." }

        val directoryEnd = 4L + count.toLong() * 4L
        require(directoryEnd <= data.size.toLong()) {
            "El directorio de $sourceLump está truncado."
        }

        val textures = ArrayList<WadTextureDefinition>(count)
        repeat(count) { index ->
            val textureOffset = u32(data, 4 + index * 4)
            require(textureOffset >= directoryEnd && textureOffset + 22L <= data.size.toLong()) {
                "La definición de textura #${index + 1} está fuera de $sourceLump."
            }

            val offset = textureOffset.toInt()
            val name = fixedName(data, offset, 8)
            val width = u16(data, offset + 12)
            val height = u16(data, offset + 14)
            val patchCount = u16(data, offset + 20)

            require(name.isNotBlank()) { "$sourceLump contiene una textura sin nombre." }
            require(width in 1..4096 && height in 1..4096) {
                "La textura $name tiene dimensiones no válidas ($width × $height)."
            }
            require(width.toLong() * height.toLong() <= MAX_TEXTURE_PIXELS) {
                "La textura $name supera el límite seguro de memoria."
            }
            require(patchCount <= MAX_PATCHES_PER_TEXTURE) {
                "La textura $name contiene demasiados parches."
            }

            val patchStart = offset + 22
            val patchEnd = patchStart.toLong() + patchCount.toLong() * 10L
            require(patchEnd <= data.size.toLong()) {
                "La lista de parches de $name está truncada."
            }

            val patches = ArrayList<WadTexturePatch>(patchCount)
            repeat(patchCount) { patchIndex ->
                val entry = patchStart + patchIndex * 10
                val originX = s16(data, entry)
                val originY = s16(data, entry + 2)
                val pnamesIndex = u16(data, entry + 4)
                require(pnamesIndex < patchNames.size) {
                    "La textura $name referencia un índice PNAMES inexistente ($pnamesIndex)."
                }
                val patchName = patchNames[pnamesIndex]
                require(patchName.isNotBlank()) {
                    "La textura $name referencia un parche sin nombre."
                }
                patches += WadTexturePatch(
                    name = patchName,
                    originX = originX,
                    originY = originY
                )
            }

            textures += WadTextureDefinition(
                name = name,
                width = width,
                height = height,
                patches = patches,
                sourceLump = sourceLump
            )
        }
        return textures
    }

    fun render(
        texture: WadTextureDefinition,
        resolvePatch: (String) -> Bitmap?
    ): WadTextureRenderResult {
        val pixels = IntArray(texture.width * texture.height) { Color.TRANSPARENT }
        val missing = linkedSetOf<String>()

        texture.patches.forEach { placement ->
            val patch = resolvePatch(placement.name)
            if (patch == null) {
                missing += placement.name
            } else {
                val sourcePixels = IntArray(patch.width * patch.height)
                patch.getPixels(sourcePixels, 0, patch.width, 0, 0, patch.width, patch.height)

                for (sourceY in 0 until patch.height) {
                    val targetY = placement.originY + sourceY
                    if (targetY !in 0 until texture.height) continue

                    for (sourceX in 0 until patch.width) {
                        val targetX = placement.originX + sourceX
                        if (targetX !in 0 until texture.width) continue

                        val color = sourcePixels[sourceY * patch.width + sourceX]
                        if (Color.alpha(color) != 0) {
                            pixels[targetY * texture.width + targetX] = color
                        }
                    }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(
            pixels,
            texture.width,
            texture.height,
            Bitmap.Config.ARGB_8888
        )
        return WadTextureRenderResult(bitmap, missing.toList())
    }

    private fun fixedName(data: ByteArray, start: Int, length: Int): String {
        return String(data, start, length, Charsets.US_ASCII)
            .substringBefore('\u0000')
            .trim()
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun s16(data: ByteArray, offset: Int): Int {
        return u16(data, offset).toShort().toInt()
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        val value = (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
        require(value <= Int.MAX_VALUE) { "El archivo contiene un desplazamiento demasiado grande." }
        return value.toInt()
    }
}
