package com.zeidex.convert.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.zeidex.convert.model.FileItem
import java.io.File
import java.nio.ByteBuffer

object MediaAudioExtractor {

    /**
     * Natively extracts the audio track from a video file into an .m4a or .aac audio file
     * using hardware-accelerated MediaExtractor and MediaMuxer (no external libraries needed).
     */
    fun extractAudio(
        context: Context,
        input: FileItem,
        outputDir: File,
        targetExt: String = "m4a",
        onProgress: ((Float) -> Unit)? = null
    ): File {
        val baseName = input.name.substringBeforeLast('.')
        val outFile = File(outputDir, "${baseName}_audio.${if (targetExt.lowercase() == "mp3") "m4a" else targetExt}")

        val pfd = context.contentResolver.openFileDescriptor(input.uri, "r")
            ?: throw IllegalStateException("Cannot access input file: ${input.name}")

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                throw IllegalStateException("No audio track found inside ${input.name}")
            }

            extractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = try {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } catch (_: Exception) {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxBufferSize.coerceAtLeast(64 * 1024))
            val bufferInfo = MediaCodec.BufferInfo()

            var readSampleCount = 0
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                extractor.advance()

                readSampleCount++
                if (readSampleCount % 100 == 0) {
                    onProgress?.invoke(0.6f)
                }
            }
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }

        return outFile
    }
}
