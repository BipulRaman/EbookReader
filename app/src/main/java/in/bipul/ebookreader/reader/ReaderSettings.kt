package `in`.bipul.ebookreader.reader

import android.content.Context
import android.content.SharedPreferences
import `in`.bipul.ebookreader.R

/** Page colours, like a Kindle's: white, sepia, green and black. */
enum class ReaderTheme(
    val label: Int,
    val background: Int,
    val text: Int,
    val secondary: Int,
    val link: Int,
    /** Background of the reader's menus and panels. */
    val chrome: Int,
    val dark: Boolean,
) {
    WHITE(R.string.theme_white, 0xFFFFFFFF.toInt(), 0xFF1C1C1C.toInt(), 0xFF7A7A7A.toInt(), 0xFF1D5FA8.toInt(), 0xFFF6F6F6.toInt(), false),
    SEPIA(R.string.theme_sepia, 0xFFF6EEDC.toInt(), 0xFF4A3A27.toInt(), 0xFF8C7A62.toInt(), 0xFF8B4E1C.toInt(), 0xFFEDE3CC.toInt(), false),
    GREEN(R.string.theme_green, 0xFFDCEAD6.toInt(), 0xFF22321E.toInt(), 0xFF5E7258.toInt(), 0xFF2D6A3E.toInt(), 0xFFCFE1C8.toInt(), false),
    BLACK(R.string.theme_black, 0xFF000000.toInt(), 0xFFCFCFCF.toInt(), 0xFF8A8A8A.toInt(), 0xFF8AB4F8.toInt(), 0xFF161616.toInt(), true),
}

/** Reading fonts. Literata ships in the app (SIL OFL); the others are the device's own. */
enum class ReaderFont(val label: Int, val css: String?) {
    PUBLISHER(R.string.font_publisher, null),
    LITERATA(R.string.font_literata, "'Literata', serif"),
    SERIF(R.string.font_serif, "serif"),
    SANS(R.string.font_sans, "sans-serif"),
}

/** Highlight colours (index stored with each highlight). */
object HighlightColors {
    val light = intArrayOf(0x73FFD400, 0x5C4FA3FF, 0x5CFF6FB0.toInt(), 0x6BFF9A2E)
    val dark = intArrayOf(0x66B89A00, 0x664A7FD0, 0x66C04A86, 0x66C0702A)
    val swatches = intArrayOf(0xFFFFD84D.toInt(), 0xFF7DB8FF.toInt(), 0xFFFF8CC2.toInt(), 0xFFFFAE5C.toInt())
    val names = intArrayOf(R.string.color_yellow, R.string.color_blue, R.string.color_pink, R.string.color_orange)
}

/** Every reader preference, stored in SharedPreferences and shared by all books. */
class ReaderSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var theme: ReaderTheme
        get() = ReaderTheme.entries.getOrElse(prefs.getInt("theme", 0)) { ReaderTheme.WHITE }
        set(v) = prefs.edit().putInt("theme", v.ordinal).apply()

    var font: ReaderFont
        get() = ReaderFont.entries.getOrElse(prefs.getInt("font", ReaderFont.LITERATA.ordinal)) { ReaderFont.LITERATA }
        set(v) = prefs.edit().putInt("font", v.ordinal).apply()

    /** Text size in percent of the book's own size. */
    var fontSize: Int
        get() = prefs.getInt("fontSize", 110)
        set(v) = prefs.edit().putInt("fontSize", v.coerceIn(MIN_FONT, MAX_FONT)).apply()

    /** 0 compact, 1 normal, 2 relaxed. */
    var lineSpacing: Int
        get() = prefs.getInt("lineSpacing", 1)
        set(v) = prefs.edit().putInt("lineSpacing", v.coerceIn(0, 2)).apply()

    /** 0 narrow, 1 normal, 2 wide. */
    var margins: Int
        get() = prefs.getInt("margins", 1)
        set(v) = prefs.edit().putInt("margins", v.coerceIn(0, 2)).apply()

    var justify: Boolean
        get() = prefs.getBoolean("justify", true)
        set(v) = prefs.edit().putBoolean("justify", v).apply()

    /** Two text columns per screen in landscape on wide screens. */
    var twoColumns: Boolean
        get() = prefs.getBoolean("twoColumns", true)
        set(v) = prefs.edit().putBoolean("twoColumns", v).apply()

    var pageAnimation: Boolean
        get() = prefs.getBoolean("pageAnimation", true)
        set(v) = prefs.edit().putBoolean("pageAnimation", v).apply()

    var volumeKeys: Boolean
        get() = prefs.getBoolean("volumeKeys", true)
        set(v) = prefs.edit().putBoolean("volumeKeys", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit().putBoolean("keepScreenOn", v).apply()

    var showStatus: Boolean
        get() = prefs.getBoolean("showStatus", true)
        set(v) = prefs.edit().putBoolean("showStatus", v).apply()

    /** Brightness slider position 0..1, or -1 to follow the system brightness. */
    var brightness: Float
        get() = prefs.getFloat("brightness", -1f)
        set(v) = prefs.edit().putFloat("brightness", if (v < 0) -1f else v.coerceIn(0f, 1f)).apply()

    /** Warm light, 0 (off) .. 1 (warmest). */
    var warmth: Float
        get() = prefs.getFloat("warmth", 0f)
        set(v) = prefs.edit().putFloat("warmth", v.coerceIn(0f, 1f)).apply()

    /** What the footer's left side shows; tapping it cycles. */
    var footerMode: Int
        get() = prefs.getInt("footerMode", 0)
        set(v) = prefs.edit().putInt("footerMode", v).apply()

    var lastHighlightColor: Int
        get() = prefs.getInt("highlightColor", 0)
        set(v) = prefs.edit().putInt("highlightColor", v).apply()

    /** Learned reading speed, in characters per second (about 250 words a minute to start). */
    var readingSpeed: Float
        get() = prefs.getFloat("readingSpeed", 25f)
        set(v) = prefs.edit().putFloat("readingSpeed", v.coerceIn(3f, 120f)).apply()

    val lineHeight: Double get() = LINE_HEIGHTS[lineSpacing]
    val marginPx: Int get() = MARGINS[margins]

    companion object {
        const val MIN_FONT = 60
        const val MAX_FONT = 250
        val LINE_HEIGHTS = doubleArrayOf(1.3, 1.55, 1.85)
        val MARGINS = intArrayOf(14, 26, 44)
    }
}

/** Builds the stylesheet reader.js lays pages out with; it comes last, so it wins over the book's. */
object ReaderCss {
    fun build(s: ReaderSettings): String = build(
        theme = s.theme,
        font = s.font,
        fontSize = s.fontSize,
        lineHeight = s.lineHeight,
        margin = s.marginPx,
        justify = s.justify,
        twoColumns = s.twoColumns,
    )

    fun build(
        theme: ReaderTheme,
        font: ReaderFont,
        fontSize: Int,
        lineHeight: Double,
        margin: Int,
        justify: Boolean,
        twoColumns: Boolean,
    ): String {
        val bg = css(theme.background)
        val fg = css(theme.text)
        val link = css(theme.link)
        val hl = if (theme.dark) HighlightColors.dark else HighlightColors.light
        val sb = StringBuilder()
        sb.append(
            """
            @font-face { font-family: 'Literata'; font-style: normal; font-weight: 400; src: url('/reader/fonts/Literata-Regular.ttf'); }
            @font-face { font-family: 'Literata'; font-style: italic; font-weight: 400; src: url('/reader/fonts/Literata-Italic.ttf'); }
            @font-face { font-family: 'Literata'; font-style: normal; font-weight: 700; src: url('/reader/fonts/Literata-Bold.ttf'); }
            @font-face { font-family: 'Literata'; font-style: italic; font-weight: 700; src: url('/reader/fonts/Literata-BoldItalic.ttf'); }
            :root { --rd-margin: $margin; --rd-vmargin: 6; --rd-two: ${if (twoColumns) 1 else 0}; color-scheme: ${if (theme.dark) "dark" else "light"}; }
            html { font-size: $fontSize% !important; -webkit-text-size-adjust: none !important; text-size-adjust: none !important;
                   background: $bg !important; height: 100% !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; }
            body { background: transparent !important; color: $fg !important; display: block !important; margin: 0 !important;
                   box-sizing: border-box !important; overflow: visible !important; column-fill: auto !important;
                   orphans: 2; widows: 2; overflow-wrap: break-word; -webkit-hyphens: auto; hyphens: auto;
                   -webkit-tap-highlight-color: transparent; opacity: 0; max-width: none !important; min-height: 0 !important; }
            html.rd-ready body { opacity: 1; }
            body * { max-width: 100%; }
            a, a * { color: $link !important; text-decoration: none !important; }
            img, svg, video, image { max-width: 100% !important; max-height: calc(100vh - 16px) !important; object-fit: contain; break-inside: avoid; }
            svg { height: auto; }
            pre { white-space: pre-wrap !important; }
            table { max-width: 100% !important; }
            h1, h2, h3, h4, h5, h6 { break-after: avoid; break-inside: avoid; }
            ::selection { background: ${if (theme.dark) "rgba(138,180,248,.45)" else "rgba(29,95,168,.28)"}; }
            .rd-hl { border-radius: 2px; }
            .rd-hl-0 { background-color: ${cssA(hl[0])} !important; }
            .rd-hl-1 { background-color: ${cssA(hl[1])} !important; }
            .rd-hl-2 { background-color: ${cssA(hl[2])} !important; }
            .rd-hl-3 { background-color: ${cssA(hl[3])} !important; }
            .rd-note { border-bottom: 2px dotted currentColor; }
            .rd-found { background-color: ${if (theme.dark) "rgba(255,181,71,.55)" else "rgba(255,170,0,.55)"} !important; border-radius: 2px; }
            """.trimIndent(),
        )
        sb.append('\n')
        // On tinted pages the book's own text and box colours would clash (dark text on black).
        if (theme != ReaderTheme.WHITE) sb.append("body *:not(a) { color: inherit !important; background-color: transparent !important; border-color: currentColor !important; }\n")
        if (theme.dark) sb.append("img { filter: brightness(.85); }\n")
        val textRules = ArrayList<String>()
        textRules += "line-height: $lineHeight !important"
        font.css?.let { textRules += "font-family: $it !important" }
        sb.append("body, body p, body div, body span, body li, body blockquote, body dd, body dt, body td, body th, body em, body i, body b, body strong, body a, body small, body cite, body section, body article { ")
        sb.append(textRules.joinToString("; ")).append("; }\n")
        if (font.css != null) sb.append("body h1, body h2, body h3, body h4, body h5, body h6 { font-family: ${font.css} !important; }\n")
        // reader.js marks elements the book centres or right-aligns (titles, epigraphs) with
        // .rd-keep, so only running text follows the alignment setting.
        val align = if (justify) "justify" else "start"
        sb.append("body p:not(.rd-keep), body div:not(.rd-keep), body li:not(.rd-keep), body blockquote:not(.rd-keep) { text-align: $align !important; }\n")
        return sb.toString()
    }

    private fun css(color: Int) = "#%06X".format(color and 0xFFFFFF)

    private fun cssA(color: Int): String {
        val a = (color ushr 24) / 255.0
        return "rgba(${(color shr 16) and 0xFF},${(color shr 8) and 0xFF},${color and 0xFF},${"%.2f".format(java.util.Locale.ROOT, a)})"
    }
}
