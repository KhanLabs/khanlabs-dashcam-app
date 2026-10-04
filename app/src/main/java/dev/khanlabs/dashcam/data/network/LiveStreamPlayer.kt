package dev.khanlabs.dashcam.data.network

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LiveStreamState {
    data object Connecting : LiveStreamState
    data class Streaming(val framesDecoded: Int) : LiveStreamState
    data class Failed(val message: String) : LiveStreamState
    data object Stopped : LiveStreamState
}

/**
 * Reads a raw H.264 Annex-B byte stream from the dashcam's LiveStreamServer
 * (port 47121) and decodes it straight to a Surface via MediaCodec -- no
 * container, no ExoPlayer extractor, just NAL-unit boundaries reconstructed
 * from the TCP stream (start codes aren't message-framed by TCP, so a
 * single socket read can span, split, or bundle multiple NALs).
 *
 * The encoder only emits a real keyframe every ~2s (its i-frame-interval is
 * -1 -- see the firmware's LiveStreamServer.maybeRequestKeyframe) rather
 * than on connect, so a client that attaches mid-cycle sees undecodable
 * leading P-frames for up to ~2s. Rather than feed those to the decoder
 * with guessed dimensions (works on some hardware decoders, silently fails
 * on others), this waits for a real SPS+PPS pair, sets them as the
 * MediaFormat's csd-0/csd-1, and only starts feeding the decoder from that
 * point on -- the leading garbage gets discarded as a side effect of the
 * same wait, not handled as a special case.
 */
class LiveStreamPlayer(
    private val onState: (LiveStreamState) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private var pending = ByteArray(0)

    fun start(host: String, port: Int, surface: Surface) {
        if (running.getAndSet(true)) return
        pending = ByteArray(0)
        thread = Thread({ runLoop(host, port, surface) }, "LiveStream-Decode").apply {
            isDaemon = true
            start()
        }
    }

    /** Synchronous: closes the socket (the actual way to unblock the
     *  decode thread's blocking read -- interrupt() doesn't touch a
     *  blocking java.net.Socket read) and joins the thread before
     *  returning, so a caller about to invalidate the Surface (e.g.
     *  surfaceDestroyed) can't race a final releaseOutputBuffer against it. */
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { socket?.close() }
        thread?.join(2_000)
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        socket = null
    }

    private fun runLoop(host: String, port: Int, surface: Surface) {
        onState(LiveStreamState.Connecting)
        var wasFailure = false
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 4_000)
            socket = sock
            val input = sock.getInputStream()
            val readBuf = ByteArray(64 * 1024)

            var sps: ByteArray? = null
            var pps: ByteArray? = null
            var mc: MediaCodec? = null
            var framesDecoded = 0

            // One combined loop rather than a separate "wait for SPS/PPS"
            // phase followed by a "feed the decoder" phase: a single socket
            // read can return the PPS *and* the IDR that immediately follows
            // it (they're often written together -- see LiveEncodedFrame on
            // the firmware side). A two-phase split would configure the
            // decoder right after spotting the PPS and then move on to the
            // next read, silently dropping that first IDR and leaving the
            // screen black for another ~2s keyframe cycle. Configuring
            // mid-batch and falling through to queue the rest of the same
            // batch avoids that.
            while (running.get()) {
                for (nal in readNals(input, readBuf)) {
                    val activeCodec = mc
                    if (activeCodec == null) {
                        when (nalType(nal)) {
                            7 -> sps = nal
                            8 -> pps = nal
                        }
                        val s = sps
                        val p = pps
                        if (s != null && p != null) {
                            val format = MediaFormat.createVideoFormat("video/avc", DEFAULT_WIDTH, DEFAULT_HEIGHT).apply {
                                setByteBuffer("csd-0", ByteBuffer.wrap(s))
                                setByteBuffer("csd-1", ByteBuffer.wrap(p))
                            }
                            val newCodec = MediaCodec.createDecoderByType("video/avc")
                            newCodec.configure(format, surface, null, 0)
                            newCodec.start()
                            codec = newCodec
                            mc = newCodec
                            onState(LiveStreamState.Streaming(framesDecoded))
                        }
                    } else {
                        queueNal(activeCodec, nal)
                    }
                }
                mc?.let {
                    framesDecoded += drainOutput(it)
                    onState(LiveStreamState.Streaming(framesDecoded))
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                onState(LiveStreamState.Failed(e.message ?: "connection lost"))
                wasFailure = true
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { socket?.close() }
            val wasRunning = running.getAndSet(false)
            // Don't clobber a Failed state we just emitted above -- both are
            // published through the same StateFlow, so an unconditional
            // Stopped here would overwrite Failed before the UI ever
            // collects it, leaving the user looking at a blank screen with
            // no explanation for why streaming stopped.
            if (wasRunning && !wasFailure) onState(LiveStreamState.Stopped)
        }
    }

    /** Blocks for one socket read, folds it into [pending], and returns
     *  every complete NAL (still carrying its own leading 00 00 01 marker)
     *  that read produced. Any bytes before the first marker are discarded
     *  -- unavoidable on the first read of a connection, which can attach
     *  mid-NAL since LiveStreamServer doesn't align new clients to a
     *  boundary. */
    private fun readNals(input: InputStream, readBuf: ByteArray): List<ByteArray> {
        val n = input.read(readBuf)
        if (n < 0) throw IOException("stream ended")
        var buf = pending + readBuf.copyOf(n)

        if (!startsWithMarker(buf)) {
            val firstMarker = findStartCode(buf, 0)
            buf = if (firstMarker >= 0) buf.copyOfRange(firstMarker, buf.size) else ByteArray(0)
        }

        val result = mutableListOf<ByteArray>()
        while (true) {
            val nextStart = findStartCode(buf, 1)
            if (nextStart < 0) break
            result.add(buf.copyOfRange(0, nextStart))
            buf = buf.copyOfRange(nextStart, buf.size)
        }
        pending = buf
        return result
    }

    private fun queueNal(mc: MediaCodec, nal: ByteArray) {
        if (nal.isEmpty()) return
        try {
            val idx = mc.dequeueInputBuffer(10_000)
            if (idx < 0) return
            val inputBuffer = mc.getInputBuffer(idx) ?: return
            inputBuffer.clear()
            inputBuffer.put(nal)
            mc.queueInputBuffer(idx, 0, nal.size, System.nanoTime() / 1000, 0)
        } catch (e: IllegalStateException) {
            // Codec torn down mid-loop (stop() racing this same iteration) --
            // running is about to flip false too, the outer loop exits clean.
        }
    }

    private fun drainOutput(mc: MediaCodec): Int {
        var count = 0
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val idx = mc.dequeueOutputBuffer(info, 0)
                if (idx < 0) break
                mc.releaseOutputBuffer(idx, true)
                count++
            }
        } catch (e: IllegalStateException) {
            // Same race as queueNal.
        }
        return count
    }

    companion object {
        const val PORT = 47121
        private const val DEFAULT_WIDTH = 640
        private const val DEFAULT_HEIGHT = 360

        private fun startsWithMarker(buf: ByteArray): Boolean =
            buf.size >= 3 && buf[0] == 0.toByte() && buf[1] == 0.toByte() && buf[2] == 1.toByte()

        private fun findStartCode(buf: ByteArray, from: Int): Int {
            var i = from
            while (i + 2 < buf.size) {
                if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) return i
                i++
            }
            return -1
        }

        /** [nal] always starts with the 00 00 01 marker this class splits
         *  on, so the NAL header byte is at index 3. */
        private fun nalType(nal: ByteArray): Int {
            if (nal.size < 4) return -1
            return nal[3].toInt() and 0x1f
        }
    }
}
