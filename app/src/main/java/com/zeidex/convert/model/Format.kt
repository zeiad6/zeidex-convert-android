package com.zeidex.convert.model

data class Format(
    val ext: String,
    val label: String,
    val category: Category
) {
    companion object {
        val PNG = Format("png", "PNG", Category.IMAGE)
        val JPG = Format("jpg", "JPG", Category.IMAGE)
        val WEBP = Format("webp", "WEBP", Category.IMAGE)
        val PDF = Format("pdf", "PDF", Category.PDF)
        val MP4 = Format("mp4", "MP4", Category.VIDEO)
        val MP3 = Format("mp3", "MP3", Category.AUDIO)
        val ZIP = Format("zip", "ZIP", Category.ARCHIVE)
        val TXT = Format("txt", "TXT", Category.DOCUMENT)

        val ALL = listOf(
            // Image
            Format("png", "PNG", Category.IMAGE),
            Format("jpg", "JPG", Category.IMAGE),
            Format("webp", "WEBP", Category.IMAGE),
            Format("pdf", "PDF", Category.IMAGE),
            Format("bmp", "BMP", Category.IMAGE),
            Format("heic", "HEIC", Category.IMAGE),
            Format("avif", "AVIF", Category.IMAGE),
            Format("ico", "ICO", Category.IMAGE),

            // Video
            Format("mp4", "MP4", Category.VIDEO),
            Format("webm", "WEBM", Category.VIDEO),
            Format("mkv", "MKV", Category.VIDEO),
            Format("gif", "GIF", Category.VIDEO),
            Format("mp3", "MP3 (Audio)", Category.VIDEO),

            // Audio
            Format("mp3", "MP3", Category.AUDIO),
            Format("m4a", "M4A", Category.AUDIO),
            Format("wav", "WAV", Category.AUDIO),
            Format("aac", "AAC", Category.AUDIO),
            Format("flac", "FLAC", Category.AUDIO),
            Format("ogg", "OGG", Category.AUDIO),

            // PDF
            Format("png", "PNG (Images)", Category.PDF),
            Format("jpg", "JPG (Images)", Category.PDF),
            Format("pdf", "PDF (Optimized)", Category.PDF),

            // Archive
            Format("zip", "ZIP", Category.ARCHIVE),
            Format("tar", "TAR", Category.ARCHIVE)
        )

        fun targetsFor(category: Category, currentExt: String): List<Format> {
            return when (category) {
                Category.IMAGE -> listOf(
                    Format("jpg", "JPG", Category.IMAGE),
                    Format("png", "PNG", Category.IMAGE),
                    Format("webp", "WEBP", Category.IMAGE),
                    Format("pdf", "PDF", Category.IMAGE),
                    Format("ico", "ICO", Category.IMAGE),
                    Format("bmp", "BMP", Category.IMAGE)
                ).filter { it.ext != currentExt.lowercase() }

                Category.VIDEO -> listOf(
                    Format("mp4", "MP4", Category.VIDEO),
                    Format("mp3", "MP3", Category.AUDIO),
                    Format("m4a", "M4A", Category.AUDIO),
                    Format("wav", "WAV", Category.AUDIO),
                    Format("gif", "GIF", Category.IMAGE)
                ).filter { it.ext != currentExt.lowercase() }

                Category.AUDIO -> listOf(
                    Format("mp3", "MP3", Category.AUDIO),
                    Format("m4a", "M4A", Category.AUDIO),
                    Format("wav", "WAV", Category.AUDIO),
                    Format("aac", "AAC", Category.AUDIO),
                    Format("ogg", "OGG", Category.AUDIO)
                ).filter { it.ext != currentExt.lowercase() }

                Category.PDF -> listOf(
                    Format("png", "PNG", Category.IMAGE),
                    Format("jpg", "JPG", Category.IMAGE),
                    Format("pdf", "PDF (Merged/Split)", Category.PDF)
                )

                Category.ARCHIVE -> listOf(
                    Format("zip", "ZIP", Category.ARCHIVE),
                    Format("tar", "TAR", Category.ARCHIVE)
                )

                Category.DOCUMENT -> listOf(
                    Format("pdf", "PDF", Category.DOCUMENT)
                )
            }
        }

        fun detectCategory(ext: String, mimeType: String?): Category {
            val lower = ext.lowercase()
            return when {
                mimeType?.startsWith("image/") == true || lower in listOf("jpg", "jpeg", "png", "webp", "heic", "avif", "bmp", "gif", "ico", "dng", "cr2", "nef", "arw") -> Category.IMAGE
                mimeType?.startsWith("video/") == true || lower in listOf("mp4", "mov", "mkv", "webm", "avi", "3gp", "ts", "m2ts") -> Category.VIDEO
                mimeType?.startsWith("audio/") == true || lower in listOf("mp3", "m4a", "wav", "aac", "flac", "ogg", "opus") -> Category.AUDIO
                lower == "pdf" || mimeType == "application/pdf" -> Category.PDF
                lower in listOf("zip", "tar", "gz", "7z", "rar", "bz2", "xz") -> Category.ARCHIVE
                else -> Category.DOCUMENT
            }
        }
    }
}
