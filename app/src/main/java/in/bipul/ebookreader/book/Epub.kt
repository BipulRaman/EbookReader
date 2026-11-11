package `in`.bipul.ebookreader.book

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Thrown for books that can't be read: DRM, broken archives, missing package documents. */
class BookException(message: String) : IOException(message)

/** An EPUB 2/3 publication read straight out of its ZIP archive. */
class EpubBook private constructor(
    private val zip: ZipFile,
    override val title: String,
    override val author: String,
    val language: String?,
    override val spine: List<SpineItem>,
    override val toc: List<TocEntry>,
    /** Path of the cover image inside the archive, if the book declares or obviously has one. */
    val coverPath: String?,
    private val mediaTypes: Map<String, String>,
) : ReflowableBook {

    override fun open(path: String): InputStream? = zip.getEntry(path)?.let { zip.getInputStream(it) }

    override fun mediaType(path: String): String? = mediaTypes[path]

    override fun close() = zip.close()

    companion object {
        private const val OPS_NS = "http://www.idpf.org/2007/ops"
        private const val CONTAINER = "META-INF/container.xml"
        private const val ENCRYPTION = "META-INF/encryption.xml"

        // Font obfuscation (IDPF and Adobe) is not DRM: the text itself is readable.
        private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
        private val DOCUMENT_TYPES = setOf("application/xhtml+xml", "text/html", "application/xml", "text/xml", "image/svg+xml")

        fun open(file: File): EpubBook {
            val zip = try {
                ZipFile(file)
            } catch (e: IOException) {
                throw BookException("Not a valid EPUB file")
            }
            try {
                return parse(zip)
            } catch (e: Exception) {
                zip.close()
                throw e as? BookException ?: BookException("This EPUB could not be read (${e.message})")
            }
        }

        private fun text(zip: ZipFile, path: String): String? =
            zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { Xml.decode(it.readBytes()) } }

        private fun parse(zip: ZipFile): EpubBook {
            checkDrm(zip)
            val opfPath = rootFile(zip) ?: throw BookException("This EPUB has no package document")
            val opf = text(zip, opfPath) ?: throw BookException("This EPUB's package document is missing")
            val opfDir = Xml.parentDir(opfPath)

            data class Item(val id: String, val path: String, val mediaType: String, val properties: String)

            val items = LinkedHashMap<String, Item>()
            val spineIds = ArrayList<String>()
            var tocId: String? = null
            var title = ""
            val creators = ArrayList<String>()
            var language: String? = null
            var coverMetaId: String? = null
            var guideCoverHref: String? = null

            val p = Xml.parser(opf)
            var section = ""
            while (true) {
                val event = p.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                if (event == XmlPullParser.END_TAG && p.name in setOf("metadata", "manifest", "spine", "guide")) section = ""
                if (event != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "metadata", "manifest", "spine", "guide" -> section = p.name
                    "title" -> if (section == "metadata" && title.isEmpty()) title = Xml.readText(p)
                    "creator" -> if (section == "metadata") Xml.readText(p).takeIf { it.isNotEmpty() }?.let(creators::add)
                    "language" -> if (section == "metadata" && language == null) language = Xml.readText(p)
                    "meta" -> if (section == "metadata" && p.getAttributeValue(null, "name") == "cover") {
                        coverMetaId = p.getAttributeValue(null, "content")
                    }
                    "item" -> if (section == "manifest") {
                        val id = p.getAttributeValue(null, "id") ?: continue
                        val href = p.getAttributeValue(null, "href") ?: continue
                        items[id] = Item(
                            id = id,
                            path = Xml.resolve(opfDir, href).first,
                            mediaType = p.getAttributeValue(null, "media-type").orEmpty().lowercase(),
                            properties = p.getAttributeValue(null, "properties").orEmpty(),
                        )
                    }
                    "spine" -> {
                        section = "spine"
                        tocId = p.getAttributeValue(null, "toc")
                    }
                    "itemref" -> if (section == "spine") p.getAttributeValue(null, "idref")?.let(spineIds::add)
                    "reference" -> if (section == "guide" && p.getAttributeValue(null, "type") == "cover") {
                        guideCoverHref = p.getAttributeValue(null, "href")
                    }
                }
            }

            val spine = spineIds.mapNotNull { items[it] }
                .filter { it.mediaType in DOCUMENT_TYPES || it.mediaType.isEmpty() }
                .map { SpineItem(it.path, it.mediaType.ifEmpty { "application/xhtml+xml" }, zip.getEntry(it.path)?.size?.coerceAtLeast(1) ?: 1) }
                .filter { zip.getEntry(it.path) != null }
            if (spine.isEmpty()) throw BookException("This EPUB has no readable chapters")

            val spineIndex = spine.withIndex().associate { (i, s) -> s.path to i }
            fun tocTarget(base: String, href: String): Pair<Int, String?>? {
                val (path, fragment) = Xml.resolve(base, href)
                val chapter = spineIndex[path] ?: return null
                return chapter to fragment
            }

            val navItem = items.values.firstOrNull { "nav" in it.properties.split(' ') }
            var toc = navItem?.let { nav -> text(zip, nav.path)?.let { parseNav(it, Xml.parentDir(nav.path), ::tocTarget) } }.orEmpty()
            if (toc.isEmpty()) {
                val ncx = tocId?.let(items::get) ?: items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                toc = ncx?.let { n -> text(zip, n.path)?.let { parseNcx(it, Xml.parentDir(n.path), ::tocTarget) } }.orEmpty()
            }

            val isImage = { item: Item -> item.mediaType.startsWith("image/") }
            val coverPath = items.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.path
                ?: coverMetaId?.let { id -> items[id]?.takeIf(isImage)?.path ?: items.values.firstOrNull { it.path.endsWith(id) && isImage(it) }?.path }
                ?: guideCoverHref?.let { href -> firstImageIn(zip, Xml.resolve(opfDir, href).first) }
                ?: items.values.firstOrNull { isImage(it) && (it.id.contains("cover", true) || it.path.substringAfterLast('/').contains("cover", true)) }?.path
                ?: firstImageIn(zip, spine.first().path)?.takeIf { spine.first().path.contains("cover", true) }

            return EpubBook(
                zip = zip,
                title = title.ifBlank { "" },
                author = creators.joinToString(", "),
                language = language,
                spine = spine,
                toc = toc,
                coverPath = coverPath?.takeIf { zip.getEntry(it) != null },
                mediaTypes = items.values.associate { it.path to it.mediaType },
            )
        }

        private fun rootFile(zip: ZipFile): String? {
            text(zip, CONTAINER)?.let { xml ->
                val p = Xml.parser(xml)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                        val type = p.getAttributeValue(null, "media-type")
                        val path = p.getAttributeValue(null, "full-path")
                        if (path != null && (type == null || type == "application/oebps-package+xml")) return Xml.normalize(path)
                    }
                }
            }
            // Broken container.xml: fall back to the first .opf in the archive.
            return zip.entries().asSequence().map { it.name }.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        }

        private fun checkDrm(zip: ZipFile) {
            val xml = text(zip, ENCRYPTION) ?: return
            val p = Xml.parser(xml)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "EncryptionMethod") {
                    val algorithm = p.getAttributeValue(null, "Algorithm")
                    if (algorithm != null && algorithm !in FONT_OBFUSCATION) {
                        throw BookException("This book is DRM-protected and can't be opened")
                    }
                }
            }
        }

        private val IMG_SRC = Regex("""<(?:img|image)\b[^>]*?\b(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        private fun firstImageIn(zip: ZipFile, docPath: String): String? {
            val html = text(zip, docPath) ?: return null
            val src = IMG_SRC.find(html)?.groupValues?.get(1) ?: return null
            return Xml.resolve(Xml.parentDir(docPath), src).first.takeIf { zip.getEntry(it) != null }
        }

        /** EPUB 3 navigation document: the `<nav epub:type="toc">` list (nested `<ol>`s give depth). */
        internal fun parseNav(xml: String, baseDir: String, target: (String, String) -> Pair<Int, String?>?): List<TocEntry> {
            val p = Xml.parser(xml)
            val out = ArrayList<TocEntry>()
            var inToc = false
            var sawToc = false
            var olDepth = 0
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "nav" -> {
                            val type = p.getAttributeValue(OPS_NS, "type") ?: p.getAttributeValue(null, "epub:type")
                            val role = p.getAttributeValue(null, "role")
                            if (!sawToc && (type?.split(' ')?.contains("toc") == true || role == "doc-toc")) {
                                inToc = true
                                sawToc = true
                            }
                        }
                        "ol" -> if (inToc) olDepth++
                        "a" -> if (inToc) {
                            val href = p.getAttributeValue(null, "href")
                            val label = Xml.readText(p)
                            val dest = href?.let { target(baseDir, it) }
                            if (dest != null && label.isNotEmpty()) out.add(TocEntry(label, dest.first, dest.second, (olDepth - 1).coerceAtLeast(0)))
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "nav" -> inToc = false
                        "ol" -> if (inToc) olDepth--
                    }
                }
            }
            return out
        }

        /** EPUB 2 NCX: nested `<navPoint>`s with a label and a content `src`. */
        internal fun parseNcx(xml: String, baseDir: String, target: (String, String) -> Pair<Int, String?>?): List<TocEntry> {
            val p = Xml.parser(xml)
            val out = ArrayList<TocEntry>()
            var depth = 0
            var label: String? = null
            var inNavMap = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "navMap" -> inNavMap = true
                        "navPoint" -> if (inNavMap) {
                            depth++
                            label = null
                        }
                        "text" -> if (inNavMap && depth > 0 && label == null) label = Xml.readText(p)
                        "content" -> if (inNavMap && depth > 0) {
                            val dest = p.getAttributeValue(null, "src")?.let { target(baseDir, it) }
                            val text = label
                            if (dest != null && !text.isNullOrEmpty()) out.add(TocEntry(text, dest.first, dest.second, depth - 1))
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "navPoint" -> if (inNavMap) depth--
                        "navMap" -> inNavMap = false
                    }
                }
            }
            return out
        }
    }
}
