package com.mozhi.reader.core.datastore

import kotlinx.serialization.json.Json

/** Built-in colors also have a named typography profile; color values are never read from it. */
object ReaderThemeTypographyCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(values: Map<String, CustomReaderTheme>): String = json.encodeToString(values)
    fun decode(raw: String?): Map<String, CustomReaderTheme> = raw?.let {
        runCatching { json.decodeFromString<Map<String, CustomReaderTheme>>(it) }.getOrNull()
    }.orEmpty()
}

internal fun ReaderSettings.typographySnapshot() = CustomReaderTheme(
    id = 0, name = theme.name, backgroundArgb = 0, textArgb = 0, accentArgb = 0,
    font = font, customFontId = selectedCustomFontId, customFontPath = customFontPath, customFontName = customFontName,
    fontScale = fontScale, fontWeight = fontWeight, lineHeight = lineHeight, publisherStyleMode = publisherStyleMode,
    pageMarginLeft = pageMarginLeft, pageMarginRight = pageMarginRight, pageMarginTop = pageMarginTop, pageMarginBottom = pageMarginBottom,
    letterSpacingEm = letterSpacingEm, paragraphSpacingEm = paragraphSpacingEm, firstLineIndentEm = firstLineIndentEm,
    titleScale = titleScale, titleTopSpacing = titleTopSpacing, titleBottomSpacing = titleBottomSpacing, titleStyle = titleStyle,
    headerMarginTop = headerMarginTop, footerMarginBottom = footerMarginBottom,
    textJustification = textJustification, showHeader = showHeader, showFooter = showFooter
)
