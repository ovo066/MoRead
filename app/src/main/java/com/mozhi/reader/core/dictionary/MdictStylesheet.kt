package com.mozhi.reader.core.dictionary

internal fun mdictStylesheet(source: String): Map<String, Pair<String, String>> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val result = linkedMapOf<String, Pair<String, String>>()
    var index = 0
    while (index + 2 < lines.size) {
        val key = lines[index].trim()
        if (key.toIntOrNull() != null) {
            result[key] = lines[index + 1] to lines[index + 2]
            index += 3
        } else index++
    }
    return result
}

/** MDX's numbered style markers delimit runs; they are not HTML or CSS selectors. */
internal fun applyMdictStyles(source: String, styles: Map<String, Pair<String, String>>): String {
    if (styles.isEmpty()) return source
    val markers = Regex("`(\\d+)`").findAll(source).toList()
    if (markers.isEmpty()) return source
    return buildString {
        append(source.substring(0, markers.first().range.first))
        markers.forEachIndexed { index, marker ->
            val text = source.substring(marker.range.last + 1, markers.getOrNull(index + 1)?.range?.first ?: source.length)
            val style = styles[marker.groupValues[1]]
            if (style == null) append(marker.value).append(text)
            else append(style.first).append(text).append(style.second)
        }
    }
}
