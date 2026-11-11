package `in`.bipul.ebookreader.book

/** The HTML named character references that turn up in ebooks: Latin-1, typography, Greek, symbols. */
internal object HtmlEntities {
    // Code points 160..255 in order.
    private val LATIN1 = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 " +
            "acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml " +
            "Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde " +
            "Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring aelig " +
            "ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide " +
            "oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(' ')

    private val OTHERS = mapOf(
        "amp" to 38, "lt" to 60, "gt" to 62, "quot" to 34, "apos" to 39,
        "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376, "fnof" to 402,
        "circ" to 710, "tilde" to 732, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204,
        "zwj" to 8205, "lrm" to 8206, "rlm" to 8207, "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216,
        "rsquo" to 8217, "sbquo" to 8218, "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222, "dagger" to 8224,
        "Dagger" to 8225, "bull" to 8226, "hellip" to 8230, "permil" to 8240, "prime" to 8242, "Prime" to 8243,
        "lsaquo" to 8249, "rsaquo" to 8250, "oline" to 8254, "frasl" to 8260, "euro" to 8364, "trade" to 8482,
        "larr" to 8592, "uarr" to 8593, "rarr" to 8594, "darr" to 8595, "harr" to 8596, "minus" to 8722,
        "infin" to 8734, "ne" to 8800, "le" to 8804, "ge" to 8805, "asymp" to 8776, "loz" to 9674,
        "spades" to 9824, "clubs" to 9827, "hearts" to 9829, "diams" to 9830,
        "Alpha" to 913, "Beta" to 914, "Gamma" to 915, "Delta" to 916, "Epsilon" to 917, "Zeta" to 918,
        "Eta" to 919, "Theta" to 920, "Iota" to 921, "Kappa" to 922, "Lambda" to 923, "Mu" to 924, "Nu" to 925,
        "Xi" to 926, "Omicron" to 927, "Pi" to 928, "Rho" to 929, "Sigma" to 931, "Tau" to 932, "Upsilon" to 933,
        "Phi" to 934, "Chi" to 935, "Psi" to 936, "Omega" to 937, "alpha" to 945, "beta" to 946, "gamma" to 947,
        "delta" to 948, "epsilon" to 949, "zeta" to 950, "eta" to 951, "theta" to 952, "iota" to 953, "kappa" to 954,
        "lambda" to 955, "mu" to 956, "nu" to 957, "xi" to 958, "omicron" to 959, "pi" to 960, "rho" to 961,
        "sigmaf" to 962, "sigma" to 963, "tau" to 964, "upsilon" to 965, "phi" to 966, "chi" to 967, "psi" to 968,
        "omega" to 969,
    )

    fun codePoint(name: String): Int? {
        OTHERS[name]?.let { return it }
        val i = LATIN1.indexOf(name)
        return if (i >= 0) 160 + i else null
    }
}
