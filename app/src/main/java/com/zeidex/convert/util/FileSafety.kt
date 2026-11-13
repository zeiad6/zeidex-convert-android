package com.zeidex.convert.util

import java.io.File
import java.util.Locale

/**
 * Path and name rules shared by every engine.
 *
 * Archive extraction is the only place where names come from an untrusted document, so the
 * containment check here is deliberately separator-aware: `/data/app/out` must never accept
 * `/data/app/outside/evil.zip`, which a plain `startsWith` would.
 */
object FileSafety {

    private val RESERVED_WINDOWS_NAMES = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )

    /** True when [child] is [parent] itself or lives below it after resolving `..` and links. */
    fun isInside(child: File, parent: File): Boolean {
        val p = parent.canonicalFile
        val c = child.canonicalFile
        return c == p || c.path.startsWith(p.path + File.separator)
    }

    /**
     * A single path segment from an archive entry or a display name.
     * Returns null for anything that could escape the destination: absolute paths, drive
     * letters, `..`, NUL bytes, or names that are empty after trimming.
     */
    fun safeSegment(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        if (name.indexOf('\u0000') >= 0) return null
        if (name.startsWith("/") || name.startsWith("\\")) return null
        if (name.length > 1 && name[1] == ':') return null
        val segments = name.split('/', '\\')
        if (segments.any { it == ".." || it == "." || it.isEmpty() }) return null
        val last = segments.last()
        val cleaned = sanitizeFileName(last) ?: return null
        return cleaned
    }

    /**
     * Validated, sanitized segments of a relative archive entry (`a/b/c.txt` → `[a, b, c.txt]`).
     * Null when the entry is absolute or contains a traversal, so callers can skip it entirely.
     */
    fun safeSegments(raw: String): List<String>? {
        val entry = raw.trim()
        if (entry.isEmpty() || entry.indexOf('\u0000') >= 0) return null
        if (entry.startsWith("/") || entry.startsWith("\\")) return null
        if (entry.length > 1 && entry[1] == ':') return null
        val parts = entry.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.map { sanitizeFileName(it) ?: return null }
    }

    /**
     * Makes [name] usable as a file name on every platform Android shares files with.
     * Keeps dots (so `.tar.gz` survives) but drops separators and control characters.
     */
    fun sanitizeFileName(name: String): String? {
        var out = name
            .replace('\u0000', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .map { ch -> if (ch.code < 0x20 || ch.code == 0x7f) '_' else ch }
            .joinToString("")
            .trim()
            .trimEnd('.')
        if (out.isEmpty() || out == "." || out == "..") return null
        val stem = out.substringBeforeLast('.').lowercase(Locale.ROOT)
        if (stem in RESERVED_WINDOWS_NAMES) out = "_$out"
        if (out.length > 200) {
            val dot = out.lastIndexOf('.')
            out = if (dot > 0 && out.length - dot <= 16) {
                out.take(200 - (out.length - dot)) + out.substring(dot)
            } else {
                out.take(200)
            }
        }
        return out
    }

    /** `photo.jpg` → `photo 2.jpg`: the first free name in [dir], never overwriting. */
    fun uniqueFile(dir: File, name: String): File {
        val safe = sanitizeFileName(name) ?: "file"
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var candidate = File(dir, safe)
        var n = 2
        while (candidate.exists() && n <= 9_999) {
            candidate = File(dir, "$stem $n$ext")
            n++
        }
        return candidate
    }

    /** `folder` → `folder 2` for directories. */
    fun uniqueDir(dir: File, name: String): File {
        val safe = sanitizeFileName(name) ?: "folder"
        var candidate = File(dir, safe)
        var n = 2
        while (candidate.exists() && n <= 9_999) {
            candidate = File(dir, "$safe $n")
            n++
        }
        return candidate
    }
}
