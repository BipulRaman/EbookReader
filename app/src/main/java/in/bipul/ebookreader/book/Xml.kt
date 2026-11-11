package `in`.bipul.ebookreader.book

import android.util.Xml as AndroidXml
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.nio.charset.Charset

/** Small helpers for the XML (OPF, NCX, XHTML) found in ebooks. */
object Xml {
    private val XML_DECL_ENCODING = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
    private val NAMED_ENTITY = Regex("&([A-Za-z][A-Za-z0-9]{1,31});")
    private val NUMERIC_ENTITY = Regex("&#([xX][0-9A-Fa-f]{1,6}|[0-9]{1,7});")

    /** Decodes an XML/XHTML document, honouring a byte-order mark or the XML declaration's encoding. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val charset = XML_DECL_ENCODING.find(head)?.groupValues?.get(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /**
     * Rewrites HTML named entities (`&nbsp;`, `&mdash;`...) as numeric references so a plain XML
     * parser accepts the document; unknown names become a space.
     */
    fun numericEntities(xml: String): String {
        if (xml.indexOf('&') < 0) return xml
        return NAMED_ENTITY.replace(xml) { m ->
            val name = m.groupValues[1]
            when {
                name in XML_ENTITIES -> m.value
                else -> HtmlEntities.codePoint(name)?.let { "&#$it;" } ?: " "
            }
        }
    }

    /** Replaces every entity (named or numeric) with the character it stands for. */
    fun decodeEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        val numeric = NUMERIC_ENTITY.replace(text) { m ->
            val v = m.groupValues[1]
            val cp = if (v[0] == 'x' || v[0] == 'X') v.substring(1).toIntOrNull(16) else v.toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else ""
        }
        return NAMED_ENTITY.replace(numeric) { m ->
            HtmlEntities.codePoint(m.groupValues[1])?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    /** A namespace-aware pull parser over [xml], tolerant of HTML entities and DOCTYPEs. */
    fun parser(xml: String): XmlPullParser {
        val parser = AndroidXml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(java.io.StringReader(numericEntities(xml)))
        return parser
    }

    /** Text of the current element and all its descendants; leaves the parser on its end tag. */
    fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    /**
     * Resolves [href] (percent-encoded, possibly with `../` and a `#fragment`) against [baseDir]
     * (a directory path inside the book, "" for the root). Returns the normalised path and fragment.
     */
    fun resolve(baseDir: String, href: String): Pair<String, String?> {
        val hash = href.indexOf('#')
        val rawPath = if (hash >= 0) href.substring(0, hash) else href
        val fragment = if (hash >= 0) href.substring(hash + 1).takeIf { it.isNotEmpty() } else null
        val decoded = runCatching { URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8") }.getOrDefault(rawPath)
        val joined = when {
            decoded.isEmpty() -> ""
            decoded.startsWith("/") -> decoded.substring(1)
            baseDir.isEmpty() -> decoded
            else -> "$baseDir/$decoded"
        }
        return normalize(joined) to fragment
    }

    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun parentDir(path: String): String = path.substringBeforeLast('/', "")

    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    private val WHITESPACE = Regex("[\\s\\u00A0]+")
}
