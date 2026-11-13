package com.zeidex.convert.engine

import android.content.Context
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.util.AtomicWriter
import com.zeidex.convert.util.FileSafety
import com.zeidex.convert.util.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Archive packing and extraction.
 *
 * Every entry is validated twice — lexically (segments, absolute paths) and by canonical path
 * after resolution — because a single prefix check is not enough: `/out` must not accept
 * `/outside/x`. Extraction happens in a staging folder that is only renamed into place when
 * the whole archive succeeded.
 */
object ArchiveConverter {

    /** Refuse archives that would fill the device through sheer entry count or expansion. */
    private const val MAX_ENTRIES = 20_000
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    /**
     * Creates a ZIP archive containing the provided input files.
     */
    fun createZip(
        context: Context,
        inputs: List<FileItem>,
        outputDir: File,
        archiveName: String = "archive.zip",
        compressionLevel: Int = 6
    ): File {
        val targetDir = if (outputDir.isDirectory || outputDir.mkdirs()) outputDir else StorageManager.getSubfolder(context, Category.ARCHIVE)
        val staged = AtomicWriter.stage(targetDir, archiveName)
        try {
            ZipOutputStream(FileOutputStream(staged).buffered()).use { zos ->
                zos.setLevel(compressionLevel.coerceIn(0, 9))
                val used = HashSet<String>()
                for (item in inputs) {
                    val entryName = uniqueEntryName(FileSafety.sanitizeFileName(item.name) ?: "file", used)
                    val inputStream = context.contentResolver.openInputStream(item.uri) ?: continue
                    inputStream.use { input ->
                        zos.putNextEntry(ZipEntry(entryName))
                        input.copyTo(zos, 64 * 1024)
                        zos.closeEntry()
                    }
                }
            }
            return AtomicWriter.commit(staged, targetDir, archiveName)
        } catch (t: Throwable) {
            AtomicWriter.discard(staged)
            throw t
        }
    }

    /**
     * Extracts ZIP, TAR, TAR.GZ or GZ into a new folder beside the source archive.
     * Returns the extracted files (or the folder when the archive was empty).
     */
    fun extractArchive(context: Context, input: FileItem, outputDir: File): List<File> {
        val targetRoot = if (outputDir.isDirectory || outputDir.mkdirs()) outputDir else StorageManager.getSubfolder(context, Category.ARCHIVE)
        val baseName = FileSafety.sanitizeFileName(input.name.substringBeforeLast('.')) ?: "archive"
        val staging = File(targetRoot, ".$baseName.extract-${System.nanoTime()}")
        if (!staging.mkdirs()) throw IOException("Cannot create extraction folder")

        val extracted = mutableListOf<File>()
        var totalBytes = 0L
        var entries = 0

        fun ensureBudget(size: Long) {
            entries++
            if (entries > MAX_ENTRIES) throw IOException("Archive has too many entries")
            totalBytes += size.coerceAtLeast(0)
            if (totalBytes > MAX_TOTAL_BYTES) throw IOException("Archive expands beyond the 8 GiB safety limit")
        }

        fun target(name: String): Pair<File, List<String>>? {
            val segments = FileSafety.safeSegments(name) ?: return null
            val dest = File(staging, segments.joinToString(File.separator))
            if (!FileSafety.isInside(dest, staging)) return null
            return dest to segments
        }

        try {
            val raw = context.contentResolver.openInputStream(input.uri)
                ?: throw IOException("Cannot open ${input.name}")
            raw.use {
                    val name = input.name.lowercase()
                    when {
                        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> {
                            TarArchive.read(GZIPInputStream(raw.buffered())) { entry, payload ->
                                ensureBudget(entry.size)
                                if (!entry.isDirectory) {
                                    target(entry.name)?.let { (dest, _) ->
                                        dest.parentFile?.mkdirs()
                                        FileOutputStream(dest).use { out -> payload.copyTo(out, 64 * 1024) }
                                        extracted.add(dest)
                                    }
                                } else {
                                    target(entry.name)?.first?.mkdirs()
                                }
                            }
                        }
                        name.endsWith(".tar") -> {
                            TarArchive.read(raw.buffered()) { entry, payload ->
                                ensureBudget(entry.size)
                                if (!entry.isDirectory) {
                                    target(entry.name)?.let { (dest, _) ->
                                        dest.parentFile?.mkdirs()
                                        FileOutputStream(dest).use { out -> payload.copyTo(out, 64 * 1024) }
                                        extracted.add(dest)
                                    }
                                } else {
                                    target(entry.name)?.first?.mkdirs()
                                }
                            }
                        }
                        name.endsWith(".gz") -> {
                            val stripped = FileSafety.sanitizeFileName(input.name.dropLast(3)) ?: "extracted"
                            val dest = File(staging, stripped)
                            GZIPInputStream(raw.buffered()).use { gz ->
                                FileOutputStream(dest).use { out ->
                                    val bytes = gz.copyWithLimit(out, MAX_TOTAL_BYTES)
                                    ensureBudget(bytes)
                                }
                            }
                            extracted.add(dest)
                        }
                        else -> {
                            ZipInputStream(raw.buffered()).use { zis ->
                                while (true) {
                                    val entry: ZipEntry = zis.nextEntry ?: break
                                    ensureBudget(entry.size)
                                    val resolved = target(entry.name)
                                    if (resolved == null) {
                                        throw IOException("Blocked unsafe archive entry: ${entry.name}")
                                    }
                                    val (dest, _) = resolved
                                    if (entry.isDirectory) {
                                        dest.mkdirs()
                                    } else {
                                        dest.parentFile?.mkdirs()
                                        FileOutputStream(dest).use { out ->
                                            val bytes = zis.copyWithLimit(out, MAX_TOTAL_BYTES - totalBytes)
                                            if (entry.size > 0 && bytes != entry.size) {
                                                throw IOException("Truncated entry: ${entry.name}")
                                            }
                                        }
                                        extracted.add(dest)
                                    }
                                    zis.closeEntry()
                                }
                            }
                        }
                    }
                }

            val finalDir = FileSafety.uniqueDir(targetRoot, baseName)
            if (!staging.renameTo(finalDir)) {
                finalDir.mkdirs()
                staging.copyRecursively(finalDir, overwrite = false)
                staging.deleteRecursively()
            }
            val remapped = extracted.map { finalDir.resolve(it.relativeTo(staging)).takeIf(File::exists) ?: finalDir }
            StorageManager.scanDirectory(context, finalDir)
            return if (remapped.isNotEmpty()) remapped else listOf(finalDir)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    /** Backwards-compatible name used by older call sites. */
    fun extractZip(context: Context, input: FileItem, outputDir: File): List<File> =
        extractArchive(context, input, outputDir)

    private fun uniqueEntryName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (!used.add("$stem ($n)$ext")) n++
        return "$stem ($n)$ext"
    }

    private fun InputStream.copyWithLimit(out: java.io.OutputStream, limit: Long): Long {
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            written += n
            if (written > limit) throw IOException("Archive expands beyond the safety limit")
            out.write(buffer, 0, n)
        }
        return written
    }
}
