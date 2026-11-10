package com.lumpforge.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object SoundFontFileManager {
    private const val MAX_SOUNDFONT_BYTES = 512L * 1024L * 1024L

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) {
                    cursor.getString(column)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "SoundFont.sf2"
    }

    /**
     * Copies a SAF-selected SoundFont to app-private storage because FluidSynth
     * opens the SoundFont through a native filesystem path.
     */
    fun copyToPrivateStorage(context: Context, uri: Uri, displayName: String): File {
        require(displayName.endsWith(".sf2", ignoreCase = true)) {
            "Selecciona un SoundFont con extensión .sf2."
        }

        val directory = File(context.filesDir, "soundfonts")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("No se pudo crear la carpeta interna de SoundFonts.")
        }

        val key = Integer.toHexString(uri.toString().hashCode())
        val target = File(directory, "soundfont-$key.sf2")
        if (target.isFile && target.length() >= 12L && isValidSf2(target)) {
            return target
        }

        val temporary = File(directory, "soundfont-$key.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Android no pudo leer el SoundFont seleccionado.")
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOUNDFONT_BYTES) {
                            "El SoundFont supera el límite de 512 MB."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            require(temporary.length() >= 12L && isValidSf2(temporary)) {
                "El archivo seleccionado no contiene una cabecera SoundFont 2 válida (RIFF/sfbk)."
            }

            if (target.exists() && !target.delete()) {
                throw IllegalStateException("No se pudo reemplazar la copia anterior del SoundFont.")
            }
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("No se pudo guardar el SoundFont en el almacenamiento interno.")
            }
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun writeMidiToCache(context: Context, lumpName: String, lumpIndex: Int, midiBytes: ByteArray): File {
        require(midiBytes.size >= 14 &&
            midiBytes[0] == 'M'.code.toByte() &&
            midiBytes[1] == 'T'.code.toByte() &&
            midiBytes[2] == 'h'.code.toByte() &&
            midiBytes[3] == 'd'.code.toByte()
        ) {
            "La secuencia convertida no es un archivo MIDI válido."
        }

        val safeName = lumpName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        val file = File(context.cacheDir, "lumpforge-$safeName-$lumpIndex.mid")
        file.writeBytes(midiBytes)
        return file
    }

    private fun isValidSf2(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) return false
                    offset += count
                }
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "sfbk"
            }
        }.getOrDefault(false)
    }
}
