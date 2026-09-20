package com.stagemic.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * AudioStreamer captures raw 16-bit PCM mono audio from the phone's microphone
 * over the same secure WebSocket endpoint used by the browser receiver.
 */
class AudioStreamer(
    private val receiverUrl: String,
    private val onLevelUpdate: (Int) -> Unit,   // 0–100 for UI level meter
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "AudioStreamer"

        // Audio config — 16kHz mono is very low bandwidth and works well for voice
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // ~20ms of audio per packet — good balance of latency vs overhead
        // 16000 samples/sec * 0.020 sec = 320 samples = 640 bytes of PCM
        const val FRAME_SIZE_SAMPLES = 320
        const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2  // 2 bytes per int16 sample

    }

    private val isStreaming = AtomicBoolean(false)
    private var streamJob: Job? = null
    private val httpClient = OkHttpClient()
    private var socket: WebSocket? = null

    /**
     * Start capturing mic and streaming over WebSocket.
     * Runs entirely in background coroutines — safe to call from UI thread.
     */
    fun start(scope: CoroutineScope) {
        if (isStreaming.getAndSet(true)) {
            Log.w(TAG, "Already streaming, ignoring start()")
            return
        }
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                stream()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Streaming failed", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Unknown streaming error")
                }
            }
            isStreaming.set(false)
        }
    }

    /**
     * Stop streaming and release resources.
     */
    fun stop() {
        isStreaming.set(false)
        streamJob?.cancel()
        streamJob = null
        socket?.close(1000, "Streaming stopped")
        socket = null
    }

    val isActive: Boolean get() = isStreaming.get()

    // ─── Internal streaming logic ───────────────────────────────────────────

    private suspend fun stream() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("AudioRecord not supported on this device")
        }

        // Use at least 4x the min buffer to avoid overruns
        val bufferSize = maxOf(minBufferSize * 4, FRAME_SIZE_BYTES * 4)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord failed to initialize — check RECORD_AUDIO permission")
        }

        val pcmBuffer = ByteArray(FRAME_SIZE_BYTES)
        val webSocket = connectWebSocket()
        socket = webSocket
        var frameCounter = 0

        try {
            audioRecord.startRecording()
            Log.i(TAG, "Streaming started → $receiverUrl")

            while (isStreaming.get()) {
                // Read exactly one frame of PCM
                val bytesRead = audioRecord.read(pcmBuffer, 0, FRAME_SIZE_BYTES)

                if (bytesRead <= 0) continue

                if (!webSocket.send(pcmBuffer.copyOf(bytesRead).toByteString())) {
                    throw IllegalStateException("WebSocket is not connected")
                }

                // Compute RMS for level meter (runs fast, just int math)
                val level = computeLevel(pcmBuffer, bytesRead)
                // Post level update to UI (throttle: only every 3 packets ≈ 60ms)
                if (frameCounter++ % 3 == 0) {
                    val levelCopy = level
                    // We're on IO thread, post back to main
                    // Use a simple mechanism — the caller handles this via the lambda
                    onLevelUpdate(levelCopy)
                }
            }

        } finally {
            Log.i(TAG, "Streaming stopped")
            audioRecord.stop()
            audioRecord.release()
            webSocket.close(1000, "Streaming stopped")
            socket = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun connectWebSocket(): WebSocket =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(toWebSocketUrl(receiverUrl)).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (continuation.isActive) continuation.resume(webSocket) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(
                            IllegalStateException("Cannot connect to receiver: ${t.message}", t)
                        ))
                    }
                }
            }
            httpClient.newWebSocket(request, listener)
            continuation.invokeOnCancellation { httpClient.dispatcher.cancelAll() }
        }

    private fun toWebSocketUrl(value: String): String {
        val trimmed = value.trim().removeSuffix("/")
        val base = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> throw IllegalArgumentException("Enter an http(s) receiver URL")
        }
        return if (base.endsWith("/ws/audio")) base else "$base/ws/audio"
    }

    /**
     * Compute audio level as 0–100 from raw PCM bytes.
     * Uses RMS of int16 samples, then scales to 0–100.
     */
    private fun computeLevel(pcmBytes: ByteArray, length: Int): Int {
        var sumSquares = 0L
        val sampleCount = length / 2  // 2 bytes per int16

        for (i in 0 until sampleCount) {
            val sample = (pcmBytes[i * 2].toInt() and 0xFF) or
                         (pcmBytes[i * 2 + 1].toInt() shl 8)
            // Convert unsigned bytes → signed int16
            val signed = if (sample > 32767) sample - 65536 else sample
            sumSquares += signed.toLong() * signed.toLong()
        }

        if (sampleCount == 0) return 0

        val rms = Math.sqrt(sumSquares.toDouble() / sampleCount)
        // Scale: max int16 is 32767. Map RMS to 0–100 with slight boost for visibility.
        val normalized = (rms / 32767.0 * 100.0 * 3.0).coerceIn(0.0, 100.0)
        return normalized.toInt()
    }
}
