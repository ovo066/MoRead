package com.mozhi.reader.core.database

object ShelfTagBackfill {
    private val colors = listOf("琥珀", "青竹", "黛蓝", "绯红")

    fun parse(raw: String): List<String> = raw
        .split(',', '，')
        .map(TagNameNormalizer::normalize)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)

    fun colorFor(name: String): String =
        colors[Math.floorMod(TagNameNormalizer.normalize(name).hashCode(), colors.size)]
}

object TagNameNormalizer {
    fun normalize(raw: String): String = raw
        .trim()
        .replace('，', ',')
        .replace(Regex("\\s+"), " ")

    fun isSame(left: String, right: String): Boolean =
        normalize(left).equals(normalize(right), ignoreCase = true)

    fun split(raw: String): List<String> = raw
        .split(',', '，')
        .map(::normalize)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
}
