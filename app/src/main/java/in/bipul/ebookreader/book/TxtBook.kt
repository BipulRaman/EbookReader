package `in`.bipul.ebookreader.book

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A plain-text book, converted once at import into XHTML chapters (split at detected chapter
 * headings) so it gets exactly the same reading experience as an EPUB.
 */
class TxtBook private constructor(
    private val dir: File,
    override val title: String,
    override val author: String,
    override val spine: List<SpineItem>,
    override val toc: List<TocEntry>,
) : ReflowableBook {

    override fun open(path: String): InputStream? {
        val file = File(dir, path)
        // Only files directly inside the converted folder are served.
        return if (file.parentFile == dir && file.isFile) file.inputStream() else null
    }

    override fun close() = Unit

    data class Meta(val title: String?, val author: String?)

    companion object {
        private const val INDEX = "book.json"
        private const val TARGET_CHUNK = 40_000
        private const val MAX_CHUNK = 70_000

        // "Chapter 12", "PART IV. The Return", "Book the First"... (a keyword followed by a number).
        private val NUMBERED_HEADING = Regex(
            "^(chapter|part|book|volume|chapitre|kapitel|cap[ií]tulo|capitolo|hoofdstuk|rozdział|глава|часть)\\s+" +
                "(\\d+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
                "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|first|second|third|fourth|fifth|sixth|" +
                "seventh|eighth|ninth|tenth|last|the\\s+\\w+)\\b.*",
            RegexOption.IGNORE_CASE,
        )
        // "PREFACE", "Preface to the Second Edition", "Appendix B: Notes" - but not "Preface words that…".
        private val SECTION_HEADING = Regex(
            "^(prologue|epilogue|preface|introduction|foreword|afterword|appendix|conclusion)" +
                "(\\s+(to|of|[a-d]|[ivx]+|\\d+)\\b.*|\\s*[.:\\u2014-].*)?$",
            RegexOption.IGNORE_CASE,
        )
        private val NUMBERED = Regex("^([IVXLCDM]{1,8}|\\d{1,3})\\.?$")
        private val GUTENBERG_TITLE = Regex("^\\s*Title:\\s*(.+)$", RegexOption.MULTILINE)
        private val GUTENBERG_AUTHOR = Regex("^\\s*Author:\\s*(.+)$", RegexOption.MULTILINE)

        fun open(dir: File): TxtBook {
            val index = JSONObject(File(dir, INDEX).readText())
            val chapters = index.getJSONArray("chapters")
            val spine = (0 until chapters.length()).map {
                val c = chapters.getJSONObject(it)
                SpineItem(c.getString("file"), "application/xhtml+xml", c.getLong("size"))
            }
            val tocJson = index.getJSONArray("toc")
            val toc = (0 until tocJson.length()).map {
                val t = tocJson.getJSONObject(it)
                TocEntry(t.getString("title"), t.getInt("chapter"), t.optString("anchor").ifEmpty { null }, 0)
            }
            return TxtBook(dir, index.optString("title"), index.optString("author"), spine, toc)
        }

        /** Decodes text: BOM if present, else strict UTF-8, else Windows-1252 (old Latin text files). */
        fun decode(bytes: ByteArray): String {
            if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) {
                return Xml.decode(bytes)
            }
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }

        /** Title/author from a Project Gutenberg style header, if the text has one. */
        fun meta(text: String): Meta {
            val head = text.take(6000)
            return Meta(
                GUTENBERG_TITLE.find(head)?.groupValues?.get(1)?.trim(),
                GUTENBERG_AUTHOR.find(head)?.groupValues?.get(1)?.trim(),
            )
        }

        private sealed interface Block {
            data class Heading(val text: String) : Block
            data class Para(val text: String) : Block
        }

        private fun blocks(text: String): List<Block> {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val blank = lines.count { it.isBlank() }
            val out = ArrayList<Block>()
            if (blank >= lines.size / 12 && blank > 0) {
                // Paragraphs separated by blank lines; lines inside one are hard-wrapped.
                val para = ArrayList<String>()
                fun flush() {
                    if (para.isEmpty()) return
                    val joined = para.joinToString(" ") { it.trim() }
                    out.add(if (para.size <= 2 && isHeading(para[0].trim(), joined)) Block.Heading(joined) else Block.Para(joined))
                    para.clear()
                }
                for (line in lines) if (line.isBlank()) flush() else para.add(line)
                flush()
            } else {
                for (line in lines) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    out.add(if (isHeading(t, t)) Block.Heading(t) else Block.Para(t))
                }
            }
            return out
        }

        private fun isHeading(firstLine: String, whole: String): Boolean =
            whole.length <= 100 && (NUMBERED_HEADING.matches(firstLine) || SECTION_HEADING.matches(firstLine) || NUMBERED.matches(firstLine))

        /**
         * Converts [text] into XHTML chapter files plus an index in [outDir]. A new chapter starts at
         * every detected heading; long stretches without one are split near [TARGET_CHUNK] chars.
         */
        // Plain-text ebooks (Project Gutenberg in particular) mark italics as _like this_.
        private val UNDERSCORE_ITALIC = Regex("""(?<![\p{L}\p{N}_])_(?=\S)([^_]+?)(?<=\S)_(?![\p{L}\p{N}_])""")

        internal fun inline(text: String): String =
            UNDERSCORE_ITALIC.replace(Xml.escape(text)) { "<i>" + it.groupValues[1] + "</i>" }

        fun convert(text: String, title: String, author: String, outDir: File): TxtBook {
            outDir.mkdirs()
            val chapters = JSONArray()
            val toc = JSONArray()
            val body = StringBuilder()
            var headings = 0
            var plainSinceSplit = 0

            fun writeChapter() {
                if (body.isEmpty()) return
                val index = chapters.length()
                val name = String.format(java.util.Locale.ROOT, "c%04d.xhtml", index)
                val html = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="utf-8"/><title>${Xml.escape(title)}</title>
<style>p{margin:0;text-indent:1.4em}h2+p,.first{text-indent:0}h2{text-align:center;font-size:1.25em;margin:1.5em 0 1.2em;line-height:1.3}</style>
</head><body>
$body</body></html>
"""
                val bytes = html.toByteArray(Charsets.UTF_8)
                File(outDir, name).writeBytes(bytes)
                chapters.put(JSONObject().put("file", name).put("size", bytes.size.toLong()))
                body.setLength(0)
                plainSinceSplit = 0
            }

            var first = true
            for (block in blocks(text)) {
                when (block) {
                    is Block.Heading -> {
                        writeChapter()
                        val id = "h$headings"
                        headings++
                        toc.put(JSONObject().put("title", block.text).put("chapter", chapters.length()).put("anchor", id))
                        body.append("<h2 id=\"").append(id).append("\">").append(Xml.escape(block.text)).append("</h2>\n")
                        first = false
                    }
                    is Block.Para -> {
                        if (body.length >= MAX_CHUNK || (plainSinceSplit >= TARGET_CHUNK && body.length >= TARGET_CHUNK)) writeChapter()
                        body.append(if (first) "<p class=\"first\">" else "<p>").append(inline(block.text)).append("</p>\n")
                        plainSinceSplit += block.text.length
                        first = false
                    }
                }
            }
            if (chapters.length() == 0 && body.isEmpty()) body.append("<p></p>\n")
            writeChapter()

            val index = JSONObject()
                .put("title", title)
                .put("author", author)
                .put("chapters", chapters)
                .put("toc", toc)
            File(outDir, INDEX).writeText(index.toString())
            return open(outDir)
        }
    }
}
