package com.lumpforge.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Creates a separate copy of a WAD while replacing one same-length byte range.
 *
 * The source is read sequentially and is never opened for writing. This keeps
 * directory offsets and all unmodified lumps byte-for-byte intact, and works
 * with Storage Access Framework providers that do not support seeking.
 */
object WadPatchWriter {
    fun copyWithPatch(
        context: Context,
        sourceUri: Uri,
        sourceSize: Long,
        destinationFolder: DocumentFile,
        requestedName: String,
        patchOffset: Long,
        replacement: ByteArray
    ): DocumentFile {
        require(destinationFolder.isDirectory && destinationFolder.canWrite()) {
            "La carpeta de destino no permite guardar archivos."
        }
        require(sourceSize >= 12L) {
            "El tamaño del WAD original no es válido."
        }
        require(replacement.isNotEmpty()) {
            "No hay cambios para guardar."
        }
        require(patchOffset >= 0L && patchOffset <= sourceSize - replacement.size) {
            "El cambio solicitado queda fuera de los límites del WAD."
        }

        val outputName = uniqueName(destinationFolder, requestedName)
        val destination = destinationFolder.createFile(
            "application/x-doom-wad",
            outputName
        ) ?: throw IllegalStateException("Android no pudo crear la copia del WAD.")

        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Android no pudo leer el WAD original.")
            val output = context.contentResolver.openOutputStream(destination.uri, "w")
                ?: throw IllegalStateException("Android no pudo abrir la copia para escritura.")

            input.use { source ->
                output.use { target ->
                    copyExactly(source, target, patchOffset)
                    skipExactly(source, replacement.size.toLong())
                    target.write(replacement)
                    val remaining = sourceSize - patchOffset - replacement.size
                    copyExactly(source, target, remaining)
                    require(source.read() == -1) {
                        "El WAD original cambió de tamaño durante el guardado. Se canceló la copia."
                    }
                }
            }

            val writtenSize = destination.length()
            require(writtenSize == 0L || writtenSize == sourceSize) {
                "La copia guardada tiene un tamaño inesperado."
            }
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun uniqueName(folder: DocumentFile, requestedName: String): String {
        val safeName = requestedName
            .replace('/', '_')
            .replace('\\', '_')
            .ifBlank { "mapa_editado.wad" }
        if (folder.findFile(safeName) == null) return safeName

        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ".wad"
        var suffix = 2
        while (suffix < 10_000) {
            val candidate = "$base ($suffix)$extension"
            if (folder.findFile(candidate) == null) return candidate
            suffix++
        }
        throw IllegalStateException("No se pudo generar un nombre único para la copia.")
    }

    private fun copyExactly(input: InputStream, output: OutputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = input.read(buffer, 0, requested)
            if (count < 0) throw EOFException("El WAD terminó antes de completar la copia.")
            if (count == 0) {
                val single = input.read()
                if (single < 0) throw EOFException("El WAD terminó antes de completar la copia.")
                output.write(single)
                remaining--
            } else {
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun skipExactly(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                val count = input.read(buffer, 0, requested)
                if (count < 0) throw EOFException("El WAD terminó dentro del recurso que se iba a reemplazar.")
                if (count == 0) {
                    if (input.read() < 0) {
                        throw EOFException("El WAD terminó dentro del recurso que se iba a reemplazar.")
                    }
                    remaining--
                } else {
                    remaining -= count
                }
            }
        }
    }
}
