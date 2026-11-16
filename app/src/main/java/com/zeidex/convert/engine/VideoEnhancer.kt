package com.zeidex.convert.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.AtomicWriter
import java.io.File
import java.nio.ByteBuffer

/**
 * Container-level video processing that Android can do correctly without a re-encode.
 *
 * The previous implementation claimed to "upscale to 1080p" and "boost the bitrate" while
 * only copying compressed samples and editing the header, so the output was byte-for-byte
 * the same resolution and quality. Those modes are gone: what remains is a fast remux, with an
 * optional metadata strip, and an honest error when real transcoding is required.
 */
object VideoEnhancer {

    enum class RemuxMode {
        /** Copy every track as-is into a fresh MP4 (fixes broken indexes, rewrites the container). */
        FAST,
        /** Same, but drop location/creation metadata that betrays where the clip was recorded. */
        STRIP_METADATA
    }

    fun remuxVideo(
        context: Context,
        input: FileItem,
        outputDir: File,
        mode: RemuxMode = RemuxMode.FAST,
        onProgress: ((Float) -> Unit)? = null
    ): File {
        val baseName = input.name.substringBeforeLast('.').ifBlank { "video" }
        val suffix = if (mode == RemuxMode.STRIP_METADATA) "_clean" else "_remux"
        val outName = "$baseName$suffix.mp4"

        val pfd = context.contentResolver.openFileDescriptor(input.uri, "r")
            ?: throw IllegalStateException("Cannot access video file: ${input.name}")

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        val staged = AtomicWriter.stage(outputDir, outName)
        try {
            extractor = MediaExtractor().apply { setDataSource(pfd.fileDescriptor) }
            muxer = MediaMuxer(staged.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = mutableMapOf<Int, Int>()
            var durationUs = 0L
            var hasVideo = false

            for (i in 0 until extractor.trackCount) {
                val source = extractor.getTrackFormat(i)
                val mime = source.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/")) hasVideo = true
                if (source.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, source.getLong(MediaFormat.KEY_DURATION))
                }
                val format = copyFormat(source, mode == RemuxMode.STRIP_METADATA)
                trackMap[i] = muxer.addTrack(format)
                extractor.selectTrack(i)
            }
            if (trackMap.isEmpty()) throw IllegalStateException("No video or audio track found in ${input.name}")
            if (!hasVideo) throw IllegalStateException("${input.name} has no video track to remux")

            muxer.start()
            // 4 MiB covers 4K keyframes; readSampleData returns an error rather than overrunning.
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val muxTrack = trackMap[track]
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                if (muxTrack != null) {
                    info.presentationTimeUs = extractor.sampleTime
                    // Extractor and codec flags only share the sync bit: SAMPLE_FLAG_PARTIAL_FRAME (4) has
                    // the value of BUFFER_FLAG_END_OF_STREAM, so the raw value could end the track early.
                    info.flags =
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.offset = 0
                    muxer.writeSampleData(muxTrack, buffer, info)
                    if (durationUs > 0) {
                        onProgress?.invoke((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                }
                extractor.advance()
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

    /** Copy the stream configuration; optionally drop the location/creation tags. */
    private fun copyFormat(source: MediaFormat, stripMetadata: Boolean): MediaFormat {
        // MediaFormat.getKeys() arrived in API 29; calling it on Android 8-9 throws
        // NoSuchMethodError. The muxer accepts the extractor's format as it is, and the
        // container-level location tag is never copied by MediaMuxer anyway.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return source
        val format = MediaFormat()
        for (key in source.keys) {
            if (stripMetadata && key.startsWith("metadata") ) continue
            if (stripMetadata && (key == "location" || key == "creation-time" || key == "compilation-time")) continue
            try {
                // MediaFormat has no type-erased getter: probe each supported type in turn.
                val value: Any = runCatching { source.getInteger(key) }.getOrNull()
                    ?: runCatching { source.getLong(key) }.getOrNull()
                    ?: runCatching { source.getFloat(key) }.getOrNull()
                    ?: runCatching { source.getString(key) }.getOrNull()
                    ?: runCatching { source.getByteBuffer(key) }.getOrNull()
                    ?: continue
                when (value) {
                    is Int -> format.setInteger(key, value)
                    is Long -> format.setLong(key, value)
                    is Float -> format.setFloat(key, value)
                    is String -> format.setString(key, value)
                    is ByteBuffer -> format.setByteBuffer(key, value)
                }
            } catch (_: Exception) {
            }
        }
        return format
    }
}
