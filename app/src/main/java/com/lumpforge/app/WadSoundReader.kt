package com.lumpforge.app

data class DoomDmxSound(
    val sampleRate: Int,
    val samples: ByteArray
) {
    val durationMillis: Long
        get() = samples.size * 1000L / sampleRate
}

/**
 * Reads the classic Doom DMX sound lump format:
 * format (u16 = 3), sample rate (u16), sample count (u32), unsigned 8-bit PCM.
 */
object WadSoundReader {
    private const val DMX_FORMAT = 3
    private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024

    fun parse(data: ByteArray): DoomDmxSound {
        require(data.size >= 8) { "El recurso es demasiado pequeño para ser un sonido DMX." }

        val format = u16(data, 0)
        require(format == DMX_FORMAT) {
            "Formato de sonido no compatible (identificador DMX: $format)."
        }

        val sampleRate = u16(data, 2)
        require(sampleRate in 4_000..48_000) {
            "Frecuencia de muestreo no válida: $sampleRate Hz."
        }

        val sampleCount = u32(data, 4)
        require(sampleCount in 1..MAX_SAMPLE_BYTES.toLong()) {
            "El sonido está vacío o supera el límite de lectura."
        }
        require(8L + sampleCount <= data.size.toLong()) {
            "Los datos PCM del sonido están truncados."
        }

        return DoomDmxSound(
            sampleRate = sampleRate,
            samples = data.copyOfRange(8, 8 + sampleCount.toInt())
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }
}
