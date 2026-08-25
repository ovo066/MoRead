package com.mozhi.reader.core.library

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object EpubResourcePath {
    fun normalize(href: String, baseHref: String? = null): String? {
        val raw = href.trim()
        if (raw.isEmpty() || raw.startsWith("//")) return null
        if (SCHEME.containsMatchIn(raw)) return null

        val withoutSuffix = raw.substringBefore('#').substringBefore('?').replace('\\', '/')
        if (withoutSuffix.isEmpty()) return baseHref?.let { normalize(it) }
        val decoded = runCatching {
            URLDecoder.decode(withoutSuffix.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(withoutSuffix)
        if (decoded.any { it == '\u0000' || it.code < 0x20 }) return null

        val combined = if (decoded.startsWith('/')) {
            decoded.removePrefix("/")
        } else {
            val normalizedBase = baseHref
                ?.substringBefore('#')
                ?.substringBefore('?')
                ?.replace('\\', '/')
                .orEmpty()
            val directory = normalizedBase.substringBeforeLast('/', "")
            if (directory.isEmpty()) decoded else "$directory/$decoded"
        }
        val segments = ArrayDeque<String>()
        combined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/").takeIf(String::isNotEmpty)
    }

    fun matchKnown(href: String, knownHrefs: Iterable<String>): String? {
        val normalized = normalize(href) ?: return null
        val known = knownHrefs.mapNotNull(::normalize)
        known.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }
        return known.filter { candidate ->
            candidate.endsWith("/$normalized", ignoreCase = true) ||
                normalized.endsWith("/$candidate", ignoreCase = true)
        }.singleOrNull()
    }

    fun packageAliases(href: String, packageDocumentPath: String): List<String> {
        val normalized = normalize(href) ?: return emptyList()
        val packagePath = normalize(packageDocumentPath)
        val packageDirectory = packagePath?.substringBeforeLast('/', "").orEmpty()
        val relative = if (
            packageDirectory.isNotEmpty() &&
            normalized.startsWith("$packageDirectory/", ignoreCase = true)
        ) {
            normalized.substring(packageDirectory.length + 1)
        } else {
            null
        }
        return listOfNotNull(normalized, relative).distinctBy { it.lowercase() }
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
}
