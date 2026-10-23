package ru.homescreen.tv.data

/**
 * Just enough HTML for Lampac pages: elements with a given class, their attributes and text. Lampac writes flat,
 * well-formed markup (`<div class="videos__item ..." s="1" e="2" data-json='{...}'>...</div>`), so no full parser.
 */
object Html {
    data class Element(val classes: List<String>, val attrs: Map<String, String>, val text: String)

    /** Every element whose class list contains [cls] exactly (`videos__item`, not `videos__item-title`). */
    fun elements(html: String, cls: String): List<Element> {
        val out = ArrayList<Element>()
        var i = 0
        while (true) {
            i = html.indexOf('<', i)
            if (i < 0 || i + 1 >= html.length) break
            if (!html[i + 1].isLetter()) { i++; continue }
            val tag = openTag(html, i) ?: break
            val classes = tag.attrs["class"]?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }.orEmpty()
            if (cls in classes) {
                val (inner, end) = body(html, tag.name, tag.end)
                out += Element(classes, tag.attrs, text(inner))
                i = end
            } else {
                i = tag.end
            }
        }
        return out
    }

    private class Tag(val name: String, val attrs: Map<String, String>, val end: Int)

    /** Parses `<name a='x' b="y" c>` starting at [start]; [Tag.end] is just past `>`. */
    private fun openTag(s: String, start: Int): Tag? {
        var i = start + 1
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-')) i++
        val name = s.substring(nameStart, i).lowercase()
        val attrs = HashMap<String, String>()
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return null
            if (s[i] == '>') return Tag(name, attrs, i + 1)
            if (s[i] == '/') { i++; continue }
            val kStart = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
            val key = s.substring(kStart, i).lowercase()
            while (i < s.length && s[i].isWhitespace()) i++
            if (i < s.length && s[i] == '=') {
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) return null
                val q = s[i]
                if (q == '"' || q == '\'') {
                    val close = s.indexOf(q, i + 1)
                    if (close < 0) return null
                    attrs[key] = decode(s.substring(i + 1, close))
                    i = close + 1
                } else {
                    val vStart = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                    attrs[key] = decode(s.substring(vStart, i))
                }
            } else if (key.isNotEmpty()) {
                attrs[key] = ""
            }
        }
        return null
    }

    /** Inner HTML up to the matching close tag, counting nested tags of the same name. */
    private fun body(s: String, name: String, from: Int): Pair<String, Int> {
        var depth = 1
        var i = from
        val open = "<$name"
        val close = "</$name"
        while (i < s.length) {
            val o = s.indexOf(open, i, ignoreCase = true)
            val c = s.indexOf(close, i, ignoreCase = true)
            if (c < 0) return s.substring(from) to s.length
            if (o in 0 until c) {
                depth++
                i = o + open.length
            } else {
                depth--
                if (depth == 0) return s.substring(from, c) to (s.indexOf('>', c).takeIf { it >= 0 }?.plus(1) ?: s.length)
                i = c + close.length
            }
        }
        return s.substring(from) to s.length
    }

    private fun text(inner: String): String =
        decode(inner.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("<[^>]*>"), " "))
            .replace(Regex("\\s+"), " ").trim()

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|quot|amp|apos|lt|gt|nbsp);")

    fun decode(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "quot" -> "\""
            "amp" -> "&"
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                code?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}
