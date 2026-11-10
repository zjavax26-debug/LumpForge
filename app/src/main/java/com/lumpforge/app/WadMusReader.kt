package com.lumpforge.app

import java.io.ByteArrayOutputStream

data class DoomMusSong(
    val midiBytes: ByteArray,
    val instrumentCount: Int,
    val primaryChannels: Int,
    val secondaryChannels: Int,
    val durationTicks: Long,
    val ticksPerQuarter: Int = 70
) {
    val durationMillis: Long
        get() = durationTicks * 500L / ticksPerQuarter
}

/**
 * Converts the DMX MUS format used by classic Doom music lumps to a
 * Standard MIDI File (format 0). No audio samples or SoundFont are embedded.
 *
 * MUS timing is represented at 140 ticks per second. A 70 PPQ MIDI file
 * with a 500,000 microsecond quarter-note tempo preserves that timing.
 */
object WadMusReader {
    private const val HEADER_SIZE = 16
    private const val MAX_SCORE_SIZE = 65_535
    private const val MAX_EVENTS = 1_000_000
    private const val MIDI_TICKS_PER_QUARTER = 70

    private val controllerMap = intArrayOf(
        -1, 0, 1, 7, 10, 11, 91, 93, 64, 67, 120, 123, 126, 127, 121
    )

    fun isMus(data: ByteArray): Boolean {
        return data.size >= 4 &&
            data[0] == 'M'.code.toByte() &&
            data[1] == 'U'.code.toByte() &&
            data[2] == 'S'.code.toByte() &&
            (data[3].toInt() and 0xff) == 0x1a
    }

    fun convert(data: ByteArray): DoomMusSong {
        require(data.size >= HEADER_SIZE) { "La cabecera MUS está incompleta." }
        require(isMus(data)) { "El recurso no tiene la firma MUS\u001a." }

        val scoreLength = u16(data, 4)
        val scoreStart = u16(data, 6)
        val primaryChannels = u16(data, 8)
        val secondaryChannels = u16(data, 10)
        val instrumentCount = u16(data, 12)

        require(scoreLength in 1..MAX_SCORE_SIZE) { "La pista MUS está vacía o tiene un tamaño no válido." }
        require(primaryChannels + secondaryChannels <= 15) {
            "La pista MUS declara demasiados canales."
        }

        val instrumentsEnd = HEADER_SIZE.toLong() + instrumentCount.toLong() * 2L
        require(instrumentsEnd <= scoreStart.toLong()) {
            "La lista de instrumentos MUS invade el inicio de la pista."
        }
        require(scoreStart >= HEADER_SIZE && scoreStart.toLong() + scoreLength <= data.size.toLong()) {
            "La pista MUS está fuera de los límites del recurso."
        }

        val scoreEnd = scoreStart + scoreLength
        var cursor = scoreStart
        var pendingDelta = 0L
        var totalTicks = 0L
        var eventCount = 0
        val track = ByteArrayOutputStream()

        // MIDI tempo meta event: 500,000 microseconds per quarter note.
        track.write(0x00)
        track.write(0xff)
        track.write(0x51)
        track.write(0x03)
        track.write(0x07)
        track.write(0xa1)
        track.write(0x20)

        val channelVelocity = IntArray(16) { 64 }
        var scoreEnded = false

        while (cursor < scoreEnd && !scoreEnded) {
            val descriptor = data[cursor++].toInt() and 0xff
            val eventType = (descriptor ushr 4) and 0x07
            val musChannel = descriptor and 0x0f
            val midiChannel = mapChannel(musChannel)

            eventCount++
            require(eventCount <= MAX_EVENTS) { "La pista MUS contiene demasiados eventos." }

            when (eventType) {
                0 -> {
                    require(cursor < scoreEnd) { "Evento MUS de liberación de nota truncado." }
                    val note = data[cursor++].toInt() and 0x7f
                    writeMidiEvent(track, pendingDelta, 0x80 or midiChannel, note, 0)
                    pendingDelta = 0
                }

                1 -> {
                    require(cursor < scoreEnd) { "Evento MUS de nota truncado." }
                    val noteByte = data[cursor++].toInt() and 0xff
                    val note = noteByte and 0x7f
                    if ((noteByte and 0x80) != 0) {
                        require(cursor < scoreEnd) { "Velocidad MUS truncada." }
                        channelVelocity[musChannel] = data[cursor++].toInt() and 0x7f
                    }
                    writeMidiEvent(
                        track, pendingDelta, 0x90 or midiChannel,
                        note, channelVelocity[musChannel]
                    )
                    pendingDelta = 0
                }

                2 -> {
                    require(cursor < scoreEnd) { "Evento MUS de pitch bend truncado." }
                    val bend = (data[cursor++].toInt() and 0xff) shl 6
                    writeMidiEvent(
                        track, pendingDelta, 0xe0 or midiChannel,
                        bend and 0x7f, (bend ushr 7) and 0x7f
                    )
                    pendingDelta = 0
                }

                3 -> {
                    require(cursor < scoreEnd) { "Evento MUS de sistema truncado." }
                    val systemController = data[cursor++].toInt() and 0x7f
                    val midiController = when (systemController) {
                        10 -> 120 // All sounds off
                        11 -> 123 // All notes off
                        12 -> 126 // Mono mode
                        13 -> 127 // Poly mode
                        14 -> 121 // Reset all controllers
                        else -> -1
                    }
                    if (midiController >= 0) {
                        writeMidiEvent(
                            track, pendingDelta, 0xb0 or midiChannel,
                            midiController, 0
                        )
                        pendingDelta = 0
                    }
                }

                4 -> {
                    require(cursor + 1 < scoreEnd) { "Evento MUS de controlador truncado." }
                    val musController = data[cursor++].toInt() and 0x7f
                    val value = data[cursor++].toInt() and 0x7f
                    if (musController == 0) {
                        // MUS controller zero is a MIDI program change.
                        writeMidiProgramChange(track, pendingDelta, midiChannel, value)
                        pendingDelta = 0
                    } else if (musController in controllerMap.indices) {
                        val midiController = controllerMap[musController]
                        if (midiController >= 0) {
                            writeMidiEvent(
                                track, pendingDelta, 0xb0 or midiChannel,
                                midiController, value
                            )
                            pendingDelta = 0
                        }
                    }
                }

                5, 7 -> throw IllegalArgumentException(
                    "La pista MUS contiene un tipo de evento reservado ($eventType)."
                )

                6 -> {
                    scoreEnded = true
                }
            }

            if (!scoreEnded && (descriptor and 0x80) != 0) {
                val (time, nextCursor) = readVariableTime(data, cursor, scoreEnd)
                cursor = nextCursor
                pendingDelta += time
                totalTicks += time
                require(pendingDelta <= 0x0fffffffL) {
                    "El intervalo de tiempo MUS supera el límite MIDI."
                }
            }
        }

        require(scoreEnded) { "La pista MUS terminó sin un evento de fin de canción." }

        writeVariableLength(track, pendingDelta)
        track.write(0xff)
        track.write(0x2f)
        track.write(0x00)

        val trackBytes = track.toByteArray()
        val midi = ByteArrayOutputStream(22 + trackBytes.size)
        midi.write(byteArrayOf(
            0x4d, 0x54, 0x68, 0x64, // MThd
            0x00, 0x00, 0x00, 0x06,
            0x00, 0x00, // format 0
            0x00, 0x01, // one track
            0x00, MIDI_TICKS_PER_QUARTER.toByte()
        ))
        midi.write(byteArrayOf(0x4d, 0x54, 0x72, 0x6b)) // MTrk
        writeInt32(midi, trackBytes.size)
        midi.write(trackBytes)

        return DoomMusSong(
            midiBytes = midi.toByteArray(),
            instrumentCount = instrumentCount,
            primaryChannels = primaryChannels,
            secondaryChannels = secondaryChannels,
            durationTicks = totalTicks
        )
    }

    private fun mapChannel(musChannel: Int): Int {
        // MUS reserves channel 15 for percussion, which is MIDI channel 9.
        return when {
            musChannel == 15 -> 9
            musChannel >= 9 -> musChannel + 1
            else -> musChannel
        }
    }

    private fun writeMidiEvent(
        output: ByteArrayOutputStream,
        delta: Long,
        status: Int,
        data1: Int,
        data2: Int
    ) {
        writeVariableLength(output, delta)
        output.write(status)
        output.write(data1 and 0x7f)
        output.write(data2 and 0x7f)
    }

    private fun writeMidiProgramChange(
        output: ByteArrayOutputStream,
        delta: Long,
        channel: Int,
        program: Int
    ) {
        writeVariableLength(output, delta)
        output.write(0xc0 or channel)
        output.write(program and 0x7f)
    }

    private fun readVariableTime(
        data: ByteArray,
        start: Int,
        limit: Int
    ): Pair<Long, Int> {
        var cursor = start
        var value = 0L
        var count = 0
        while (true) {
            require(cursor < limit) { "Tiempo MUS truncado." }
            val byte = data[cursor++].toInt() and 0xff
            value = (value shl 7) or (byte and 0x7f).toLong()
            count++
            require(count <= 5 && value <= 0x0fffffffL) {
                "Tiempo MUS demasiado largo."
            }
            if ((byte and 0x80) == 0) break
        }
        return value to cursor
    }

    private fun writeVariableLength(output: ByteArrayOutputStream, value: Long) {
        require(value in 0..0x0fffffffL) { "Delta MIDI fuera de rango." }
        var buffer = (value and 0x7f).toInt()
        var remaining = value ushr 7
        while (remaining > 0) {
            buffer = (buffer shl 8) or ((remaining and 0x7f).toInt() or 0x80)
            remaining = remaining ushr 7
        }
        while (true) {
            output.write(buffer and 0xff)
            if ((buffer and 0x80) != 0) {
                buffer = buffer ushr 8
            } else {
                break
            }
        }
    }

    private fun writeInt32(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)
    }
}
