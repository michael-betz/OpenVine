package ch.betzengineering.openvine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log

/**
 * Keeps the microphone running all the time and stores the most recent audio in a ring buffer.
 * Each sample is mapped onto the CLOCK_BOOTTIME timebase, which is the same clock the camera
 * uses for frame timestamps (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME). This allows cutting audio
 * retroactively at the exact video frame times, with zero start-up latency.
 */
class AudioCapture {
    private val TAG = "AudioCapture"

    companion object {
        const val SAMPLE_RATE = 48000
        private const val RING_SECONDS = 8 // must be longer than the longest segment
        private const val READ_CHUNK = 960 // 20 ms
    }

    private val ring = ShortArray(SAMPLE_RATE * RING_SECONDS)
    private val lock = Object()

    // Total number of samples written into the ring since start()
    private var writtenFrames = 0L

    // Mapping sample index <-> boottime nanoseconds
    private var anchorFrame = 0L
    private var anchorNs = 0L
    private var hasAnchor = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, READ_CHUNK * 2 * 4)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            rec.release()
            return
        }
        synchronized(lock) {
            writtenFrames = 0L
            hasAnchor = false
        }
        rec.startRecording()
        record = rec
        running = true
        thread = Thread({ readLoop(rec) }, "AudioCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Audio capture started")
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to stop AudioRecord", e)
            }
            it.release()
        }
        record = null
        synchronized(lock) { hasAnchor = false }
        Log.i(TAG, "Audio capture stopped")
    }

    private fun readLoop(rec: AudioRecord) {
        val chunk = ShortArray(READ_CHUNK)
        val ts = AudioTimestamp()
        while (running) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) {
                if (n < 0) Log.e(TAG, "AudioRecord.read error $n")
                continue
            }
            val nowNs = SystemClock.elapsedRealtimeNanos()
            synchronized(lock) {
                var pos = (writtenFrames % ring.size).toInt()
                var remaining = n
                var src = 0
                while (remaining > 0) {
                    val len = minOf(remaining, ring.size - pos)
                    System.arraycopy(chunk, src, ring, pos, len)
                    src += len
                    remaining -= len
                    pos = (pos + len) % ring.size
                }
                writtenFrames += n

                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    anchorFrame = ts.framePosition
                    anchorNs = ts.nanoTime
                    hasAnchor = true
                } else if (!hasAnchor) {
                    // Fallback: assume the last sample read was captured just now
                    anchorFrame = writtenFrames
                    anchorNs = nowNs
                    hasAnchor = true
                }
                lock.notifyAll()
            }
        }
    }

    /** Sample index (since start()) captured at boottime [ns]. Call with lock held. */
    private fun frameAt(ns: Long): Long =
        anchorFrame + Math.round((ns - anchorNs).toDouble() * SAMPLE_RATE / 1e9)

    /**
     * Returns the audio between [startUs] (boottime, same as camera PTS) and
     * [startUs] + [durationUs] as exactly round(durationUs * SAMPLE_RATE / 1e6) samples.
     * Blocks until the microphone has delivered the requested range (or the timeout expires),
     * missing samples are filled with silence.
     */
    fun extract(startUs: Long, durationUs: Long, timeoutMs: Long = 300): ShortArray {
        val nSamples = Math.round(durationUs.toDouble() * SAMPLE_RATE / 1e6).toInt()
        val out = ShortArray(nSamples)
        if (!running) return out
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (true) {
                if (hasAnchor && writtenFrames >= frameAt(startUs * 1000) + nSamples) break
                val wait = deadline - SystemClock.elapsedRealtime()
                if (wait <= 0) {
                    Log.w(TAG, "Timeout waiting for audio, padding with silence")
                    break
                }
                lock.wait(wait)
            }
            if (!hasAnchor) return out
            val first = frameAt(startUs * 1000)
            val oldest = maxOf(0L, writtenFrames - ring.size)
            for (i in 0 until nSamples) {
                val f = first + i
                if (f < oldest || f >= writtenFrames) continue
                out[i] = ring[(f % ring.size).toInt()]
            }
            Log.d(TAG, "extract: start=$startUs us, n=$nSamples, first=$first, written=$writtenFrames")
        }
        return out
    }
}
