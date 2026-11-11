package com.morozi.autophone.screen

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log

private const val TAG = "AudioCapture"

/**
 * Records what Android is playing (the REMOTE_SUBMIX source), for the car's
 * media audio channel. Only a privileged process may do that, which is why it
 * lives in [ScreenServer] (root) and not in the app.
 *
 * While it records, Android sends that sound here instead of to HDMI, which
 * is what a car wants. Silence is not passed on, so the car's audio channel
 * is only busy while something actually plays.
 *
 * PCM 16-bit little-endian stereo at [sampleRate], in chunks of [CHUNK_BYTES].
 */
internal class AudioCapture(
    private val sampleRate: Int,
    private val onPcm: (timestampUs: Long, data: ByteArray) -> Unit
) {
    @Volatile private var running = true

    fun stop() {
        running = false
    }

    /** Blocks until [stop]; returns at once (logged) if recording cannot start. */
    fun run() {
        val record = open() ?: return
        try {
            record.startRecording()
            Log.i(TAG, "recording playback at $sampleRate Hz")
            fullSubmixVolume()
            val chunk = ByteArray(CHUNK_BYTES)
            var quietChunks = QUIET_CHUNKS_SENT // start as silent
            var sent = 0L
            while (running) {
                var filled = 0
                while (filled < chunk.size && running) {
                    val n = record.read(chunk, filled, chunk.size - filled)
                    if (n <= 0) {
                        Log.w(TAG, "read failed: $n")
                        return
                    }
                    filled += n
                }
                if (!running) break
                // A little silence is still sent after sound, so short pauses
                // (between songs, in speech) do not cut the stream.
                quietChunks = if (isSilent(chunk)) quietChunks + 1 else 0
                if (quietChunks > QUIET_CHUNKS_SENT) continue
                onPcm(System.nanoTime() / 1000, chunk.copyOf())
                if (++sent % 500 == 0L) Log.i(TAG, "sent $sent audio chunks")
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture ended: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * While we record, Android plays into the "remote submix" output, which has
     * its own media volume (20 of 25 by default here: the car got ~38% of the
     * level). Set that output to full; the level the car hears is then ours
     * ([CarVolume]) and the car's own. HDMI and other outputs keep theirs.
     */
    private fun fullSubmixVolume() {
        runCatching {
            Thread.sleep(300) // let the output switch to the submix first
            val range = Runtime.getRuntime().exec(arrayOf("cmd", "media_session", "volume", "--stream", "3", "--get"))
                .inputStream.bufferedReader().readText()
            val max = Regex("""\[\d+\.\.(\d+)]""").find(range)?.groupValues?.get(1) ?: return
            Runtime.getRuntime().exec(arrayOf("cmd", "media_session", "volume", "--stream", "3", "--set", max)).waitFor()
            Log.i(TAG, "media volume of the capture output set to $max")
        }.onFailure { Log.w(TAG, "could not set the capture output volume: $it") }
    }

    private fun isSilent(pcm: ByteArray): Boolean {
        var i = 0
        while (i < pcm.size) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            if (sample > SILENCE_LEVEL || sample < -SILENCE_LEVEL) return false
            i += 2
        }
        return true
    }

    /**
     * AudioRecord wants a Context to know who is recording, and a process
     * started with app_process has none; try with a stand-in for each identity
     * the audio server may accept, then with no context at all.
     */
    private fun open(): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuffer, CHUNK_BYTES * 4)
        val identities: List<String?> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf("root", "com.android.shell", null) else listOf(null)
        for (identity in identities) {
            val record = runCatching {
                AudioRecord.Builder()
                    .apply { if (identity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setContext(ServerContext(identity)) }
                    .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.onFailure { Log.w(TAG, "open as ${identity ?: "no context"} failed: $it") }.getOrNull()
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "opened as ${identity ?: "no context"}")
                return record
            }
            record?.release()
        }
        Log.e(TAG, "playback capture not available; the car gets no sound")
        return null
    }

    /** The least Context that AudioRecord needs: a package name and who we are. */
    private class ServerContext(private val pkg: String) : ContextWrapper(null) {
        override fun getPackageName(): String = pkg
        override fun getOpPackageName(): String = pkg
        override fun getAttributionTag(): String? = null
        override fun getApplicationContext(): Context = this
        override fun getDeviceId(): Int = 0
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(pkg).build()
    }

    companion object {
        /** ~43 ms of 48 kHz stereo 16-bit; the size the car's audio channel is fed in. */
        const val CHUNK_BYTES = 8192
        /** ~0.5 s of trailing silence is still sent. */
        private const val QUIET_CHUNKS_SENT = 12
        private const val SILENCE_LEVEL = 2
    }
}
