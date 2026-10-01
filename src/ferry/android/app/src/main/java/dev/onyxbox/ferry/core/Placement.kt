package dev.onyxbox.ferry.core

import java.io.File

/** "Smart by type" placement. Pure JVM so it can be unit-tested off-device. */
object Placement {
    enum class Category(val dir: String) {
        BOOKS("Books"), AUDIOBOOKS("Audiobooks"), MUSIC("Music"), PICTURES("Pictures/Ferry"),
        VIDEO("Movies/Ferry"), FONTS("Fonts"), NOTES("Documents/Notes"), DOCUMENTS("Documents"),
        OTHER("Download/Ferry")
    }

    private val byExt: Map<String, Category> = buildMap {
        fun add(c: Category, vararg e: String) = e.forEach { put(it, c) }
        add(Category.BOOKS, "epub", "pdf", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr", "chm")
        add(Category.AUDIOBOOKS, "m4b", "aax")
        add(Category.MUSIC, "mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")
        add(Category.PICTURES, "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tif", "tiff", "svg")
        add(Category.VIDEO, "mp4", "mkv", "mov", "avi", "webm", "m4v")
        add(Category.FONTS, "ttf", "otf", "ttc", "woff", "woff2")
        add(Category.NOTES, "note")
        add(Category.DOCUMENTS, "txt", "md", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods",
            "csv", "ppt", "pptx", "odp", "json")
    }

    fun categoryOf(path: String): Category {
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return byExt[ext] ?: Category.OTHER
    }

    /** Turns a peer-supplied path into safe relative segments. Never empty. */
    fun sanitize(path: String): List<String> {
        val segs = path.replace('\\', '/').split('/')
            .map { s -> s.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimEnd('.', ' ') }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        return segs.ifEmpty { listOf("file") }
    }

    /** Final destination under [root], created and de-duplicated. */
    fun destination(root: File, path: String): File {
        val segs = sanitize(path)
        var dir = File(root, categoryOf(segs.last()).dir)
        for (s in segs.dropLast(1)) dir = File(dir, s)
        dir.mkdirs()
        return unique(File(dir, segs.last()))
    }

    fun unique(f: File): File {
        if (!f.exists()) return f
        val base = f.name.substringBeforeLast('.', f.name)
        val ext = if (f.name.contains('.')) "." + f.name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            val c = File(f.parentFile, "$base ($i)$ext")
            if (!c.exists()) return c
            i++
        }
    }
}
