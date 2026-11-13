package com.zeidex.convert.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.AtomicWriter
import java.io.File
import java.nio.ByteBuffer

object MediaAudioExtractor {

    private const val POLL = 100

    /**
     * Copies the first audio track of a video into an `.m4a` (AAC in an MP4 container).
     *
     * Android ships an AAC muxer, not an MP3/FLAC/OGG encoder, so only `.m4a` is offered:
     * asking for `mp3` used to produce an `.m4a` file with a lying name. Anything else now
     * fails with a clear message instead.
     */
    fun extractAudio(
        context: Context,
        input: FileItem,
        outputDir: File,
        targetExt: String = "m4a",
        onProgress: ((Float) -> Unit)? = null
    ): File {
        val ext = targetExt.lowercase()
        if (ext != "m4a" && ext != "mp4") {
            throw UnsupportedOperationException(
                "On-device audio extraction produces M4A (AAC). Use the desktop app for MP3, WAV, FLAC or OGG."
            )
        }
        val baseName = input.name.substringBeforeLast('.').ifBlank { "audio" }
        val outName = "${baseName}_audio.m4a"

        val pfd = context.contentResolver.openFileDescriptor(input.uri, "r")
            ?: throw IllegalStateException("Cannot access input file: ${input.name}")

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        val staged = AtomicWriter.stage(outputDir, outName)
        try {
            extractor = MediaExtractor().apply { setDataSource(pfd.fileDescriptor) }
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                throw IllegalStateException("No audio track found inside ${input.name}")
            }
            extractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(staged.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = try {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } catch (_: Exception) {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxBufferSize.coerceAtLeast(64 * 1024))
            val bufferInfo = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                extractor.advance()
                samples++
                if (samples % POLL == 0) {
                    val fraction = if (durationUs > 0) {
                        (bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    } else 0.5f
                    onProgress?.invoke(fraction)
                }
            }
            muxer.stop()
            muxer.release()
            muxer = null
            onProgress?.invoke(1f)
            return AtomicWriter.commit(staged, outputDir, outName)
        } catch (t: Throwable) {
            AtomicWriter.discard(staged)
            throw t
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }
}
