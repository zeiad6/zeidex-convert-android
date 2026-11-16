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
            Format("ico", "ICO", Category.IMAGE),

            // Video
            Format("mp4", "MP4", Category.VIDEO),
            Format("m4a", "M4A (Audio)", Category.VIDEO),

            // Audio
            Format("m4a", "M4A", Category.AUDIO),
            Format("wav", "WAV (Desktop)", Category.AUDIO),

            // PDF
            Format("png", "PNG (Images)", Category.PDF),
            Format("jpg", "JPG (Images)", Category.PDF),
            Format("pdf", "PDF (Optimized)", Category.PDF),
            Format("docx", "Word (DOCX)", Category.PDF),
            Format("txt", "Text (TXT)", Category.PDF),

            // Documents
            Format("txt", "TXT", Category.DOCUMENT),
            Format("md", "Markdown", Category.DOCUMENT),
            Format("docx", "Word (DOCX)", Category.DOCUMENT),

            // Subtitles
            Format("srt", "SRT", Category.SUBTITLE),
            Format("vtt", "WebVTT", Category.SUBTITLE),
            Format("ass", "ASS", Category.SUBTITLE),
            Format("txt", "Plain text", Category.SUBTITLE),

            // Archive
            Format("zip", "ZIP", Category.ARCHIVE),
            Format("tar", "TAR", Category.ARCHIVE)
        )

        /**
         * Formats the current selection can be turned into. Only what the on-device engines
         * actually implement is listed: offering MP3 or 1080p upscaling here would produce a
         * file with a wrong extension or a silently identical video.
         */
        fun targetsFor(category: Category, currentExt: String): List<Format> {
            val current = currentExt.lowercase()
            val targets = when (category) {
                Category.IMAGE -> listOf(
                    Format("jpg", "JPG", Category.IMAGE),
                    Format("png", "PNG", Category.IMAGE),
                    Format("webp", "WEBP", Category.IMAGE),
                    Format("bmp", "BMP", Category.IMAGE),
                    Format("pdf", "PDF", Category.IMAGE),
                    Format("ico", "ICO", Category.IMAGE),
                    Format("mp4", "MP4 (Live Photo video)", Category.VIDEO),
                    Format("zip", "ZIP (Archive)", Category.ARCHIVE)
                )

                Category.VIDEO -> listOf(
                    Format("mp4", "MP4 (fast remux)", Category.VIDEO),
                    Format("m4a", "M4A (Extract audio)", Category.AUDIO),
                    Format("zip", "ZIP", Category.ARCHIVE)
                )

                Category.AUDIO -> listOf(
                    Format("m4a", "M4A", Category.AUDIO),
                    Format("zip", "ZIP", Category.ARCHIVE)
                )

                Category.PDF -> listOf(
                    Format("png", "PNG (Pages)", Category.IMAGE),
                    Format("jpg", "JPG (Pages)", Category.IMAGE),
                    Format("docx", "Word (DOCX)", Category.DOCUMENT),
                    Format("txt", "Text (TXT)", Category.DOCUMENT),
                    Format("zip", "ZIP (Pages)", Category.ARCHIVE)
                )

                Category.DOCUMENT -> listOf(
                    Format("pdf", "PDF", Category.PDF),
                    Format("docx", "Word (DOCX)", Category.DOCUMENT),
                    Format("txt", "Text (TXT)", Category.DOCUMENT),
                    Format("md", "Markdown", Category.DOCUMENT),
                    Format("zip", "ZIP", Category.ARCHIVE)
                )

                Category.SUBTITLE -> listOf(
                    Format("srt", "SRT", Category.SUBTITLE),
                    Format("vtt", "WebVTT", Category.SUBTITLE),
                    Format("ass", "ASS", Category.SUBTITLE),
                    Format("txt", "Plain text", Category.SUBTITLE),
                    Format("zip", "ZIP", Category.ARCHIVE)
                )

                Category.ARCHIVE -> listOf(
                    Format("extract", "Extract All", Category.ARCHIVE),
                    Format("zip", "ZIP", Category.ARCHIVE)
                )
            }
            // Converting "photo.jpg" to "JPG" is a no-op; keep the list honest.
            return targets.filterNot { it.ext.equals(current, ignoreCase = true) }
        }

        fun detectCategory(ext: String, mimeType: String?): Category {
            val lower = ext.lowercase()
            return when {
                mimeType?.startsWith("image/") == true || lower in listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp", "gif", "ico", "dng", "cr2", "nef", "arw", "tif", "tiff") -> Category.IMAGE
                mimeType?.startsWith("video/") == true || lower in listOf("mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp", "ts", "m2ts", "wmv", "flv") -> Category.VIDEO
                mimeType?.startsWith("audio/") == true || lower in listOf("mp3", "m4a", "wav", "aac", "flac", "ogg", "opus", "aiff", "amr") -> Category.AUDIO
                lower == "pdf" || mimeType == "application/pdf" -> Category.PDF
                lower in listOf("srt", "vtt", "ass", "ssa", "smi", "lrc") -> Category.SUBTITLE
                lower in listOf("zip", "tar", "tgz", "gz", "7z", "rar", "bz2", "xz") -> Category.ARCHIVE
                // Anything else is a document: text, Word, spreadsheets, slides, OpenDocument,
                // RTF, HTML, EPUB — and the legacy binaries, which fail with an explanation
                // instead of producing a wrong file.
                else -> Category.DOCUMENT
            }
        }
    }
}
