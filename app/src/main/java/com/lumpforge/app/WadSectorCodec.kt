package com.lumpforge.app

import java.util.Locale

/**
 * Encodes the fixed 26-byte classic Doom SECTORS record.
 * Only fields exposed by the first sector editor are written; the original
 * sector record is replaced as a whole so its binary layout stays unchanged.
 */
object WadSectorCodec {
    const val RECORD_SIZE = 26

    fun encode(sector: WadMapSector): ByteArray {
        require(sector.floorHeight in Short.MIN_VALUE..Short.MAX_VALUE) {
            "La altura del suelo debe estar entre -32768 y 32767."
        }
        require(sector.ceilingHeight in Short.MIN_VALUE..Short.MAX_VALUE) {
            "La altura del techo debe estar entre -32768 y 32767."
        }
        require(sector.lightLevel in 0..255) {
            "La iluminación debe estar entre 0 y 255."
        }
        require(sector.special in 0..0xffff) {
            "El especial debe estar entre 0 y 65535."
        }
        require(sector.tag in 0..0xffff) {
            "El tag debe estar entre 0 y 65535."
        }

        val bytes = ByteArray(RECORD_SIZE)
        putSigned16(bytes, 0, sector.floorHeight)
        putSigned16(bytes, 2, sector.ceilingHeight)
        putTextureName(bytes, 4, sector.floorTexture)
        putTextureName(bytes, 12, sector.ceilingTexture)
        putUnsigned16(bytes, 20, sector.lightLevel)
        putUnsigned16(bytes, 22, sector.special)
        putUnsigned16(bytes, 24, sector.tag)
        return bytes
    }

    private fun putTextureName(bytes: ByteArray, offset: Int, value: String) {
        val normalized = value.trim().uppercase(Locale.ROOT)
        require(normalized.isNotEmpty() && normalized.length <= 8) {
            "El nombre de textura debe tener entre 1 y 8 caracteres."
        }
        require(normalized.all { it.code in 0x21..0x7e }) {
            "El nombre de textura solo puede contener caracteres ASCII imprimibles sin espacios."
        }
        normalized.forEachIndexed { index, char ->
            bytes[offset + index] = char.code.toByte()
        }
    }

    private fun putSigned16(bytes: ByteArray, offset: Int, value: Int) {
        putUnsigned16(bytes, offset, value and 0xffff)
    }

    private fun putUnsigned16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }
}
