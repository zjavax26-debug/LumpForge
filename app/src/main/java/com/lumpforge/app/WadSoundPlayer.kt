package com.lumpforge.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Streams classic Doom unsigned 8-bit mono DMX samples to Android AudioTrack.
 */
object WadSoundPlayer {
    suspend fun play(sound: DoomDmxSound) = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sound.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "Android no admite esta configuración de audio." }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sound.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, 8_192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            val chunkSize = 4_096
            val pcm = ShortArray(chunkSize)
            var offset = 0

            while (offset < sound.samples.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(chunkSize, sound.samples.size - offset)
                for (i in 0 until count) {
                    val unsignedSample = sound.samples[offset + i].toInt() and 0xff
                    pcm[i] = ((unsignedSample - 128) shl 8).toShort()
                }

                var written = 0
                while (written < count) {
                    currentCoroutineContext().ensureActive()
                    val result = track.write(pcm, written, count - written)
                    check(result > 0) { "No se pudieron enviar los datos al dispositivo de audio." }
                    written += result
                }
                offset += count
            }

            // AudioTrack may still have queued samples after the final write.
            while (track.playbackHeadPosition.toLong() < sound.samples.size.toLong()) {
                currentCoroutineContext().ensureActive()
                delay(5)
            }
        } finally {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            } catch (_: IllegalStateException) {
                // AudioTrack can already be stopped when playback is cancelled.
            }
            track.release()
        }
    }
}
