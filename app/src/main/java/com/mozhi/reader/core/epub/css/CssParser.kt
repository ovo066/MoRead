package com.mozhi.reader.core.epub.css

import com.mozhi.reader.core.library.EpubResourcePath

/** Fault-tolerant EPUB CSS parser. Invalid selectors/declarations are isolated from their siblings. */
class CssParser(
    private val sourceHref: String,
    private val startingOrder: Int = 0
) {
    private val rules = ArrayList<CssRule>()
    private val fontFaces = ArrayList<CssFontFaceRule>()
    private val unsupportedSelectors = linkedSetOf<String>()
    private val unsupportedProperties = linkedSetOf<String>()
    private val diagnostics = ArrayList<String>()
    private var nextOrder = startingOrder

    fun parse(css: String): CssParseResult {
        parseRules(removeComments(css), null)
        return CssParseResult(
            stylesheet = CssStylesheet(sourceHref, rules, fontFaces),
            unsupportedSelectors = unsupportedSelectors.toList(),
            unsupportedProperties = unsupportedProperties.toList(),
            diagnostics = diagnostics.toList()
        )
    }

    private fun parseRules(css: String, mediaCondition: CssMediaCondition?) {
        var cursor = 0
        while (cursor < css.length) {
            cursor = css.skipWhitespace(cursor)
            if (cursor >= css.length) break
            // Recover from an unmatched top-level closing brace instead of abandoning later rules.
            if (css[cursor] == '}') {
                cursor++
                continue
            }
            if (css[cursor] == '@') {
                val nameEnd = css.consumeName(cursor + 1)
                val atName = css.substring(cursor + 1, nameEnd).lowercase()
                val boundary = findRuleBoundary(css, nameEnd)
                if (boundary < 0) break
                val prelude = css.substring(nameEnd, boundary).trim()
                if (css[boundary] == ';') {
                    if (atName == "import") diagnostics += "unsupported @import: $prelude"
                    cursor = boundary + 1
                    continue
                }
                val close = findMatching(css, boundary, '{', '}')
                val blockEnd = if (close < 0) css.length else close
                val block = css.substring(boundary + 1, blockEnd)
                when (atName) {
                    "media" -> parseRules(block, combineMedia(mediaCondition, parseMedia(prelude)))
                    "font-face" -> parseFontFace(block)
                    "charset", "page", "namespace", "supports" -> Unit
                    "import" -> diagnostics += "unsupported @import: $prelude"
                    else -> diagnostics += "ignored @$atName"
                }
                cursor = if (close < 0) css.length else close + 1
                continue
            }

            val open = findTopLevel(css, cursor, '{')
            if (open < 0) break
            val strayClose = findTopLevel(css, cursor, '}')
            if (strayClose in cursor until open) {
                cursor = strayClose + 1
                continue
            }
            val selectorSource = css.substring(cursor, open).trim()
            val close = findMatching(css, open, '{', '}')
            val blockEnd = if (close < 0) css.length else close
            val declarations = parseDeclarations(css.substring(open + 1, blockEnd))
            val order = nextOrder++
            if (declarations.isNotEmpty()) {
                splitTopLevel(selectorSource, ',').forEach { rawSelector ->
                    val source = rawSelector.trim()
                    if (source.isEmpty()) return@forEach
                    val selector = parseSelector(source)
                    if (selector == null) {
                        unsupportedSelectors += source
                    } else {
                        rules += CssRule(selector, declarations, order, mediaCondition)
                    }
                }
            }
            cursor = if (close < 0) css.length else close + 1
        }
    }

    fun parseDeclarations(raw: String): List<CssDeclaration> {
        val result = ArrayList<CssDeclaration>()
        splitTopLevel(raw, ';').forEach { declarationSource ->
            val colon = findTopLevel(declarationSource, 0, ':')
            if (colon <= 0) return@forEach
            val property = declarationSource.substring(0, colon).trim().lowercase()
            if (property !in SUPPORTED_PROPERTIES) {
                unsupportedProperties += property
                return@forEach
            }
            var valueSource = declarationSource.substring(colon + 1).trim()
            val importantMatch = IMPORTANT.find(valueSource)
            val important = importantMatch != null
            if (importantMatch != null) valueSource = valueSource.substring(0, importantMatch.range.first).trimEnd()
            val expanded = expandDeclaration(property, valueSource, important)
            if (expanded == null) {
                unsupportedProperties += property
            } else {
                result += expanded
            }
        }
        return result
    }

    private fun expandDeclaration(property: String, raw: String, important: Boolean): List<CssDeclaration>? = when (property) {
        "margin", "padding" -> expandBox(property, raw, important)
        "border" -> expandBorder(null, raw, important)
        "border-top", "border-right", "border-bottom", "border-left" ->
            expandBorder(property.substringAfter('-'), raw, important)
        "border-width", "border-style", "border-color" ->
            expandBorderQuad(property.substringAfter('-'), raw, important)
        "border-radius" -> expandRadius(raw, important)
        "background" -> expandBackground(raw, important)
        "font" -> expandFont(raw, important)
        "list-style" -> expandListStyle(raw, important)
        else -> parsePropertyValue(property, raw)?.let { listOf(CssDeclaration(property, it, important)) }
    }

    private fun expandBox(property: String, raw: String, important: Boolean): List<CssDeclaration>? {
        val values = componentValues(property, raw)?.zeroAsLength() ?: return null
        if (values.size !in 1..4) return null
        if (property == "padding" && values.any { value ->
                value !is CssValue.Length || value.value < 0f
            }
        ) return null
        if (property == "margin" && values.any { value ->
                value !is CssValue.Length && value != CssValue.Keyword("auto")
            }
        ) return null
        val expanded = expandFour(values)
        return SIDES.mapIndexed { index, side ->
            CssDeclaration("$property-$side", expanded[index], important)
        }
    }

    private fun expandBorder(side: String?, raw: String, important: Boolean): List<CssDeclaration>? {
        val values = componentValues("border", raw) ?: return null
        var width: CssValue = CssValue.Keyword("medium")
        var style: CssValue = CssValue.Keyword("none")
        var color: CssValue = CssValue.Keyword("currentcolor")
        values.forEach { value ->
            when {
                value is CssValue.Length || value is CssValue.Number ||
                    (value is CssValue.Keyword && value.name in BORDER_WIDTH_KEYWORDS) -> width = value
                value is CssValue.Color || value == CssValue.Keyword("currentcolor") ||
                    value == CssValue.Keyword("transparent") -> color = value
                value is CssValue.Keyword && value.name in BORDER_STYLES -> style = value
                else -> return null
            }
        }
        val sides = side?.let(::listOf) ?: SIDES
        return sides.flatMap { edge ->
            listOf(
                CssDeclaration("border-$edge-width", width, important),
                CssDeclaration("border-$edge-style", style, important),
                CssDeclaration("border-$edge-color", color, important)
            )
        }
    }

    private fun expandBorderQuad(kind: String, raw: String, important: Boolean): List<CssDeclaration>? {
        val values = componentValues("border-$kind", raw)?.zeroAsLength() ?: return null
        if (values.size !in 1..4) return null
        val valid = when (kind) {
            "width" -> values.all { it is CssValue.Length || it is CssValue.Number || it is CssValue.Keyword && it.name in BORDER_WIDTH_KEYWORDS }
            "style" -> values.all { it is CssValue.Keyword && it.name in BORDER_STYLES }
            else -> values.all { it is CssValue.Color || it is CssValue.Keyword && it.name in setOf("transparent", "currentcolor") }
        }
        if (!valid) return null
        return expandFour(values).mapIndexed { index, value ->
            CssDeclaration("border-${SIDES[index]}-$kind", value, important)
        }
    }

    private fun expandRadius(raw: String, important: Boolean): List<CssDeclaration>? {
        val horizontal = splitTopLevel(raw, '/').firstOrNull().orEmpty()
        val values = componentValues("border-radius", horizontal)?.zeroAsLength() ?: return null
        if (values.size !in 1..4 || values.any { it !is CssValue.Length || it.value < 0f }) return null
        return expandFour(values).mapIndexed { index, value ->
            CssDeclaration("border-${CORNERS[index]}-radius", value, important)
        }
    }

    private fun expandBackground(raw: String, important: Boolean): List<CssDeclaration>? {
        val slashParts = splitTopLevel(raw, '/')
        if (slashParts.size > 2) return null
        var beforeSource = slashParts[0]
        var color: CssValue = CssValue.Color(0)
        FUNCTION_COLOR.find(beforeSource)?.let { match ->
            color = colorValue(match.value) ?: return null
            beforeSource = beforeSource.removeRange(match.range)
        }
        val before = tokensWithoutWhitespace(beforeSource)
        var image: CssValue = CssValue.Keyword("none")
        var repeat: CssValue = CssValue.Keyword("repeat")
        val position = ArrayList<CssValue>()
        before.forEach { token ->
            val tokenRaw = token.text
            val value = tokenToValue(token, "background") ?: return null
            when {
                value is CssValue.Url || value == CssValue.Keyword("none") -> image = value
                value is CssValue.Color || value == CssValue.Keyword("currentcolor") || value == CssValue.Keyword("transparent") -> color = value
                value is CssValue.Keyword && value.name in BACKGROUND_REPEATS -> repeat = value
                else -> position += value
            }
        }
        val size = if (slashParts.size == 2) parseTuple("background-size", slashParts[1]) ?: return null
            else CssValue.Keyword("auto")
        return listOf(
            CssDeclaration("background-color", color, important),
            CssDeclaration("background-image", image, important),
            CssDeclaration("background-repeat", repeat, important),
            CssDeclaration("background-position", position.takeIf { it.isNotEmpty() }?.let(CssValue::Tuple) ?: CssValue.Keyword("0% 0%"), important),
            CssDeclaration("background-size", size, important)
        )
    }

    private fun expandFont(raw: String, important: Boolean): List<CssDeclaration>? {
        val tokens = tokensWithoutWhitespace(raw)
        val sizeIndex = tokens.indexOfFirst { token ->
            token.type == CssTokenType.DIMENSION || token.type == CssTokenType.PERCENTAGE ||
                token.type == CssTokenType.IDENT && token.text.lowercase() in FONT_SIZE_KEYWORDS
        }
        if (sizeIndex < 0) return null
        val before = tokens.take(sizeIndex)
        val size = tokenToValue(tokens[sizeIndex], "font-size") ?: return null
        var cursor = sizeIndex + 1
        var lineHeight: CssValue = CssValue.Keyword("normal")
        if (tokens.getOrNull(cursor)?.text == "/") {
            lineHeight = tokens.getOrNull(cursor + 1)?.let { tokenToValue(it, "line-height") } ?: return null
            cursor += 2
        }
        if (cursor >= tokens.size) return null
        val familyTokens = tokens.drop(cursor)
        val families = splitTokenGroups(familyTokens).mapNotNull { group ->
            group.joinToString(" ") { it.text }.trim().takeIf(String::isNotEmpty)?.let(CssValue::Ident)
        }
        if (families.isEmpty()) return null
        var style = CssValue.Keyword("normal")
        var weight: CssValue = CssValue.Keyword("normal")
        before.forEach { token ->
            val lower = token.text.lowercase()
            when {
                lower in setOf("normal", "italic", "oblique") -> style = CssValue.Keyword(lower)
                lower in setOf("bold", "bolder", "lighter") || token.type == CssTokenType.NUMBER ->
                    weight = tokenToValue(token, "font-weight") ?: return null
                lower in setOf("small-caps") -> Unit
                else -> return null
            }
        }
        return listOf(
            CssDeclaration("font-style", style, important),
            CssDeclaration("font-weight", weight, important),
            CssDeclaration("font-size", size, important),
            CssDeclaration("line-height", lineHeight, important),
            CssDeclaration("font-family", CssValue.CommaList(families), important)
        )
    }

    private fun expandListStyle(raw: String, important: Boolean): List<CssDeclaration>? {
        val values = componentValues("list-style", raw) ?: return null
        val type = values.firstOrNull { value ->
            value is CssValue.Keyword && value.name !in setOf("inside", "outside", "none") ||
                value is CssValue.Ident
        } ?: values.firstOrNull { it == CssValue.Keyword("none") } ?: CssValue.Keyword("disc")
        val position = values.firstOrNull { it is CssValue.Keyword && it.name in setOf("inside", "outside") }
            ?: CssValue.Keyword("outside")
        val image = values.firstOrNull { it is CssValue.Url } ?: CssValue.Keyword("none")
        return listOf(
            CssDeclaration("list-style-type", type, important),
            CssDeclaration("list-style-position", position, important),
            CssDeclaration("list-style-image", image, important)
        )
    }

    private fun parsePropertyValue(property: String, raw: String): CssValue? {
        if (raw.isBlank()) return null
        if (property == "font-family") {
            val families = splitTopLevel(raw, ',').mapNotNull { family ->
                family.trim().trim('"', '\'').takeIf(String::isNotEmpty)?.let(CssValue::Ident)
            }
            return families.takeIf { it.isNotEmpty() }?.let(CssValue::CommaList)
        }
        if (property in COLOR_PROPERTIES) return colorValue(raw)
        if (property == "background-position" || property == "background-size" ||
            property == "box-shadow" || property == "text-shadow" || property == "grid-template-columns"
        ) return parseTuple(property, raw)
        val values = componentValues(property, raw) ?: return null
        if (values.size != 1) return CssValue.Tuple(values)
        val value = values.single()
        if (property in LENGTH_PROPERTIES && value is CssValue.Number && value.value == 0f) {
            return CssValue.Length(0f, CssUnit.PX)
        }
        if (property in NON_NEGATIVE_LENGTH_PROPERTIES && value is CssValue.Length && value.value < 0f) return null
        return value
    }

    private fun parseTuple(property: String, raw: String): CssValue? {
        val commaParts = splitTopLevel(raw, ',')
        val groups = commaParts.map { part ->
            componentValues(property, part)?.let { values ->
                if (values.size == 1) values.single() else CssValue.Tuple(values)
            } ?: return null
        }
        return if (groups.size == 1) groups.single() else CssValue.CommaList(groups)
    }

    /** CSS allows a bare `0` wherever a length goes; keep `margin: 2em 0` intact. */
    private fun List<CssValue>.zeroAsLength(): List<CssValue> = map { value ->
        if (value is CssValue.Number && value.value == 0f) CssValue.Length(0f, CssUnit.PX) else value
    }

    private fun componentValues(property: String, raw: String): List<CssValue>? {
        val tokens = tokensWithoutWhitespace(raw)
        if (tokens.isEmpty() || tokens.any { it.type == CssTokenType.COMMA || it.type == CssTokenType.LEFT_PAREN }) return null
        return tokens.map { tokenToValue(it, property) ?: return null }
    }

    private fun tokenToValue(token: CssToken, property: String): CssValue? = when (token.type) {
        CssTokenType.DIMENSION -> unit(token.unit)?.let { CssValue.Length(token.number ?: return null, it) }
        CssTokenType.PERCENTAGE -> CssValue.Length(token.number ?: return null, CssUnit.PERCENT)
        CssTokenType.NUMBER -> CssValue.Number(token.number ?: return null)
        CssTokenType.URL -> {
            val href = EpubResourcePath.normalize(token.text, sourceHref)
                ?: token.text.takeIf { it.startsWith("data:", true) }
                ?: return null
            CssValue.Url(href)
        }
        CssTokenType.STRING -> CssValue.Ident(token.text)
        CssTokenType.HASH -> colorValue("#${token.text}")
        CssTokenType.IDENT -> {
            val lower = token.text.lowercase()
            when {
                lower == "currentcolor" -> CssValue.Keyword("currentcolor")
                lower == "transparent" -> CssValue.Color(0)
                CssColor.parse(lower) != null && property in COLOR_AWARE_PROPERTIES -> CssValue.Color(CssColor.parse(lower)!!)
                lower in KEYWORDS -> CssValue.Keyword(lower)
                else -> CssValue.Ident(token.text)
            }
        }
        CssTokenType.DELIM -> if (token.text == "/") CssValue.Keyword("/") else null
        else -> null
    }

    private fun colorValue(raw: String): CssValue? {
        if (raw.trim().equals("currentcolor", true)) return CssValue.Keyword("currentcolor")
        return CssColor.parse(raw)?.let { color ->
            if (color == CssColor.CURRENT_COLOR) CssValue.Keyword("currentcolor") else CssValue.Color(color)
        }
    }

    private fun parseFontFace(block: String) {
        val raw = rawDeclarations(block)
        val family = raw["font-family"]?.trim()?.trim('"', '\'')?.takeIf(String::isNotEmpty) ?: return
        val sources = raw["src"].orEmpty().let(CssTokenizer::tokenize)
            .filter { it.type == CssTokenType.URL }
            .mapNotNull { token -> EpubResourcePath.normalize(token.text, sourceHref) }
        if (sources.isEmpty()) return
        val weight = raw["font-weight"]?.trim()?.let { value ->
            value.toIntOrNull() ?: if (value.equals("bold", true)) 700 else if (value.equals("normal", true)) 400 else null
        }
        fontFaces += CssFontFaceRule(family, sources, weight, raw["font-style"]?.trim()?.lowercase())
    }

    private fun rawDeclarations(block: String): Map<String, String> = buildMap {
        splitTopLevel(block, ';').forEach { declaration ->
            val colon = findTopLevel(declaration, 0, ':')
            if (colon > 0) put(declaration.substring(0, colon).trim().lowercase(), declaration.substring(colon + 1).trim())
        }
    }

    private fun parseSelector(source: String): CssSelector? {
        val compounds = ArrayList<CssCompoundSelector>()
        val combinators = ArrayList<CssCombinator>()
        var pseudoElement: CssPseudoElement? = null
        var cursor = 0
        while (cursor < source.length) {
            while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
            val start = cursor
            var bracket = 0
            var paren = 0
            var quote: Char? = null
            while (cursor < source.length) {
                val char = source[cursor]
                if (quote != null) {
                    if (char == '\\') cursor++ else if (char == quote) quote = null
                    cursor++
                    continue
                }
                when (char) {
                    '"', '\'' -> quote = char
                    '[' -> bracket++
                    ']' -> bracket--
                    '(' -> paren++
                    ')' -> paren--
                }
                if (bracket == 0 && paren == 0 && (char.isWhitespace() || char in ">+~")) break
                cursor++
            }
            if (cursor == start) return null
            val parsed = parseCompound(source.substring(start, cursor), source) ?: return null
            if (parsed.second != null) {
                if (pseudoElement != null) return null
                pseudoElement = parsed.second
            }
            compounds += parsed.first
            if (cursor >= source.length) break
            var hadWhitespace = false
            while (source.getOrNull(cursor)?.isWhitespace() == true) {
                hadWhitespace = true
                cursor++
            }
            val combinator = when (source.getOrNull(cursor)) {
                '>' -> CssCombinator.CHILD.also { cursor++ }
                '+' -> CssCombinator.ADJACENT_SIBLING.also { cursor++ }
                '~' -> CssCombinator.GENERAL_SIBLING.also { cursor++ }
                else -> if (hadWhitespace) CssCombinator.DESCENDANT else return null
            }
            combinators += combinator
        }
        if (compounds.isEmpty() || combinators.size != compounds.size - 1) return null
        var specificity = CssSpecificity(tags = if (pseudoElement != null) 1 else 0)
        compounds.forEach { compound -> specificity += specificityOf(compound) }
        val never = compounds.any { compound -> compound.pseudoClasses.any { it is CssPseudoClass.Unsupported } }
        return CssSelector(compounds, combinators, pseudoElement, specificity, never, source)
    }

    private fun parseCompound(raw: String, wholeSelector: String): Pair<CssCompoundSelector, CssPseudoElement?>? {
        var cursor = 0
        var tag: String? = null
        var universal = false
        var id: String? = null
        val classes = ArrayList<String>()
        val attributes = ArrayList<CssAttributeSelector>()
        val pseudos = ArrayList<CssPseudoClass>()
        var pseudoElement: CssPseudoElement? = null
        if (raw.getOrNull(cursor) == '*') {
            universal = true
            cursor++
        } else if (raw.getOrNull(cursor)?.let { it.isLetter() || it == '_' || it.code >= 0x80 } == true) {
            val end = raw.consumeName(cursor)
            tag = cssUnescape(raw.substring(cursor, end)).lowercase()
            cursor = end
        }
        while (cursor < raw.length) {
            when (raw[cursor]) {
                '#' -> {
                    val end = raw.consumeName(cursor + 1)
                    if (end == cursor + 1) return null
                    id = cssUnescape(raw.substring(cursor + 1, end))
                    cursor = end
                }
                '.' -> {
                    val end = raw.consumeName(cursor + 1)
                    if (end == cursor + 1) return null
                    classes += cssUnescape(raw.substring(cursor + 1, end))
                    cursor = end
                }
                '[' -> {
                    val end = findMatching(raw, cursor, '[', ']')
                    if (end < 0) return null
                    parseAttribute(raw.substring(cursor + 1, end))?.let(attributes::add) ?: return null
                    cursor = end + 1
                }
                ':' -> {
                    val doubleColon = raw.getOrNull(cursor + 1) == ':'
                    cursor += if (doubleColon) 2 else 1
                    val end = raw.consumeName(cursor)
                    if (end == cursor) return null
                    val name = raw.substring(cursor, end).lowercase()
                    cursor = end
                    var argument: String? = null
                    if (raw.getOrNull(cursor) == '(') {
                        val close = findMatching(raw, cursor, '(', ')')
                        if (close < 0) return null
                        argument = raw.substring(cursor + 1, close).trim()
                        cursor = close + 1
                    }
                    if (doubleColon || name in setOf("first-letter", "first-line")) {
                        pseudoElement = when (name) {
                            "first-letter" -> CssPseudoElement.FIRST_LETTER
                            "first-line" -> CssPseudoElement.FIRST_LINE
                            else -> {
                                unsupportedSelectors += wholeSelector
                                return CssCompoundSelector(pseudoClasses = listOf(CssPseudoClass.Unsupported(name))) to null
                            }
                        }
                    } else {
                        pseudos += parsePseudo(name, argument, wholeSelector)
                    }
                }
                else -> return null
            }
        }
        return CssCompoundSelector(tag, universal, id, classes, attributes, pseudos) to pseudoElement
    }

    private fun parsePseudo(name: String, argument: String?, source: String): CssPseudoClass = when (name) {
        "first-child" -> CssPseudoClass.FirstChild
        "last-child" -> CssPseudoClass.LastChild
        "only-child" -> CssPseudoClass.OnlyChild
        "first-of-type" -> CssPseudoClass.FirstOfType
        "last-of-type" -> CssPseudoClass.LastOfType
        "nth-child" -> parseNth(argument.orEmpty())?.let { CssPseudoClass.NthChild(it.first, it.second) }
            ?: unsupportedPseudo(name, source)
        "not" -> argument?.let { parseCompound(it, source)?.first }
            ?.takeIf { argument.none(Char::isWhitespace) }
            ?.let(CssPseudoClass::Not) ?: unsupportedPseudo(name, source)
        else -> unsupportedPseudo(name, source)
    }

    private fun unsupportedPseudo(name: String, source: String): CssPseudoClass.Unsupported {
        unsupportedSelectors += source
        return CssPseudoClass.Unsupported(name)
    }

    private fun parseAttribute(raw: String): CssAttributeSelector? {
        val value = raw.trim()
        val match = ATTRIBUTE.matchEntire(value) ?: return null
        val name = cssUnescape(match.groupValues[1])
        val operator = when (match.groupValues[2]) {
            "=" -> CssAttributeOperator.EQUALS
            "~=" -> CssAttributeOperator.INCLUDES
            "|=" -> CssAttributeOperator.DASH_MATCH
            else -> CssAttributeOperator.EXISTS
        }
        val expected = match.groupValues[3].trim().trim('"', '\'').takeIf(String::isNotEmpty)
        val insensitive = match.groupValues[4].equals("i", true)
        return CssAttributeSelector(name, operator, expected, insensitive)
    }

    private fun parseNth(raw: String): Pair<Int, Int>? {
        val value = raw.lowercase().replace(" ", "")
        if (value == "odd") return 2 to 1
        if (value == "even") return 2 to 0
        if ('n' !in value) return 0 to (value.toIntOrNull() ?: return null)
        val before = value.substringBefore('n')
        val after = value.substringAfter('n')
        val a = when (before) {
            "", "+" -> 1
            "-" -> -1
            else -> before.toIntOrNull() ?: return null
        }
        val b = if (after.isEmpty()) 0 else after.toIntOrNull() ?: return null
        return a to b
    }

    private fun specificityOf(compound: CssCompoundSelector): CssSpecificity {
        var result = CssSpecificity(
            ids = if (compound.id == null) 0 else 1,
            classes = compound.classes.size + compound.attributes.size,
            tags = if (compound.tag == null) 0 else 1
        )
        compound.pseudoClasses.forEach { pseudo ->
            result += if (pseudo is CssPseudoClass.Not) specificityOf(pseudo.selector) else CssSpecificity(classes = 1)
        }
        return result
    }

    private fun parseMedia(raw: String): CssMediaCondition {
        val queries = splitTopLevel(raw, ',').map { querySource ->
            val parts = querySource.trim().split(Regex("(?i)\\s+and\\s+"))
            var mediaType = CssMediaType.ALL
            val features = ArrayList<CssMediaFeature>()
            parts.forEachIndexed { index, partRaw ->
                val part = partRaw.trim()
                if (index == 0 && !part.startsWith('(')) {
                    mediaType = when (part.lowercase().removePrefix("only ").trim()) {
                        "", "all" -> CssMediaType.ALL
                        "screen" -> CssMediaType.SCREEN
                        "print" -> CssMediaType.PRINT
                        "speech" -> CssMediaType.SPEECH
                        "aural" -> CssMediaType.AURAL
                        else -> CssMediaType.UNKNOWN
                    }
                } else {
                    features += parseMediaFeature(part)
                }
            }
            CssMediaQuery(mediaType, features)
        }
        return CssMediaCondition(queries.ifEmpty { listOf(CssMediaQuery()) })
    }

    private fun parseMediaFeature(raw: String): CssMediaFeature {
        val value = raw.trim().removePrefix("(").removeSuffix(")")
        val name = value.substringBefore(':').trim().lowercase()
        val featureValue = value.substringAfter(':', "").trim().lowercase()
        return when (name) {
            "min-width", "max-width" -> {
                val token = CssTokenizer.tokenize(featureValue).firstOrNull { it.type != CssTokenType.WHITESPACE && it.type != CssTokenType.EOF }
                val number = token?.number
                val unit = token?.unit?.let(::unit) ?: CssUnit.PX
                if (number == null) CssMediaFeature.Unknown(raw)
                else if (name == "min-width") CssMediaFeature.MinWidth(number, unit)
                else CssMediaFeature.MaxWidth(number, unit)
            }
            "orientation" -> CssMediaFeature.Orientation(featureValue == "landscape")
            else -> CssMediaFeature.Unknown(raw)
        }
    }

    private fun combineMedia(parent: CssMediaCondition?, child: CssMediaCondition): CssMediaCondition {
        if (parent == null) return child
        val combined = parent.queries.flatMap { outer ->
            child.queries.map { inner ->
                val type = when {
                    outer.type == CssMediaType.ALL -> inner.type
                    inner.type == CssMediaType.ALL -> outer.type
                    outer.type == inner.type -> outer.type
                    else -> CssMediaType.UNKNOWN
                }
                CssMediaQuery(type, outer.features + inner.features)
            }
        }
        return CssMediaCondition(combined)
    }

    private fun tokensWithoutWhitespace(raw: String): List<CssToken> = CssTokenizer.tokenize(raw)
        .filter { it.type != CssTokenType.WHITESPACE && it.type != CssTokenType.EOF }

    private fun splitTokenGroups(tokens: List<CssToken>): List<List<CssToken>> {
        val groups = ArrayList<MutableList<CssToken>>()
        groups += ArrayList<CssToken>()
        tokens.forEach { token ->
            if (token.type == CssTokenType.COMMA) groups += ArrayList<CssToken>() else groups.last() += token
        }
        return groups
    }

    private fun unit(raw: String?): CssUnit? = when (raw?.lowercase()) {
        "px" -> CssUnit.PX
        "em" -> CssUnit.EM
        "rem" -> CssUnit.REM
        "%" -> CssUnit.PERCENT
        "vw" -> CssUnit.VW
        "vh" -> CssUnit.VH
        "pt" -> CssUnit.PT
        "ex" -> CssUnit.EX
        "ch" -> CssUnit.CH
        else -> null
    }

    private fun <T> expandFour(values: List<T>): List<T> = when (values.size) {
        1 -> listOf(values[0], values[0], values[0], values[0])
        2 -> listOf(values[0], values[1], values[0], values[1])
        3 -> listOf(values[0], values[1], values[2], values[1])
        else -> values
    }

    private fun removeComments(css: String): String {
        val result = StringBuilder(css.length)
        var cursor = 0
        var quote: Char? = null
        while (cursor < css.length) {
            val char = css[cursor]
            if (quote != null) {
                result.append(char)
                if (char == '\\' && cursor + 1 < css.length) {
                    result.append(css[++cursor])
                } else if (char == quote) {
                    quote = null
                }
                cursor++
            } else if (char == '"' || char == '\'') {
                quote = char
                result.append(char)
                cursor++
            } else if (char == '/' && css.getOrNull(cursor + 1) == '*') {
                val close = css.indexOf("*/", cursor + 2)
                cursor = if (close < 0) css.length else close + 2
            } else {
                result.append(char)
                cursor++
            }
        }
        return result.toString()
    }

    private fun findRuleBoundary(value: String, start: Int): Int {
        val brace = findTopLevel(value, start, '{')
        val semicolon = findTopLevel(value, start, ';')
        return when {
            brace < 0 -> semicolon
            semicolon < 0 -> brace
            else -> minOf(brace, semicolon)
        }
    }

    private fun findTopLevel(value: String, start: Int, needle: Char): Int {
        var paren = 0
        var bracket = 0
        var quote: Char? = null
        var cursor = start
        while (cursor < value.length) {
            val char = value[cursor]
            if (quote != null) {
                if (char == '\\') cursor++ else if (char == quote) quote = null
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    '(' -> paren++
                    ')' -> paren--
                    '[' -> bracket++
                    ']' -> bracket--
                    needle -> if (paren == 0 && bracket == 0) return cursor
                }
            }
            cursor++
        }
        return -1
    }

    private fun findMatching(value: String, openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        var quote: Char? = null
        var cursor = openIndex
        while (cursor < value.length) {
            val char = value[cursor]
            if (quote != null) {
                if (char == '\\') cursor++ else if (char == quote) quote = null
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    open -> depth++
                    close -> if (--depth == 0) return cursor
                }
            }
            cursor++
        }
        return -1
    }

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val result = ArrayList<String>()
        var start = 0
        var paren = 0
        var bracket = 0
        var quote: Char? = null
        var cursor = 0
        while (cursor < value.length) {
            val char = value[cursor]
            if (quote != null) {
                if (char == '\\') cursor++ else if (char == quote) quote = null
            } else {
                when (char) {
                    '"', '\'' -> quote = char
                    '(' -> paren++
                    ')' -> paren--
                    '[' -> bracket++
                    ']' -> bracket--
                    delimiter -> if (paren == 0 && bracket == 0) {
                        result += value.substring(start, cursor)
                        start = cursor + 1
                    }
                }
            }
            cursor++
        }
        result += value.substring(start)
        return result
    }

    private fun String.skipWhitespace(start: Int): Int {
        var cursor = start
        while (getOrNull(cursor)?.isWhitespace() == true) cursor++
        return cursor
    }

    private fun String.consumeName(start: Int): Int {
        var cursor = start
        while (cursor < length) {
            val char = this[cursor]
            if (char == '\\' && cursor + 1 < length) cursor += 2
            else if (char.isLetterOrDigit() || char == '-' || char == '_' || char.code >= 0x80) cursor++
            else break
        }
        return cursor
    }

    private fun cssUnescape(value: String): String = value.replace(Regex("\\\\([:#.\\[\\]])"), "$1")

    private companion object {
        val IMPORTANT = Regex("(?i)!\\s*important\\s*$")
        val FUNCTION_COLOR = Regex("(?i)(?:rgba?|hsla?)\\([^)]*\\)")
        val ATTRIBUTE = Regex("^([^\\s~|=]+)\\s*(?:(~=|\\|=|=)\\s*(.*?)\\s*(?:([iIsS]))?)?$")
        val SIDES = listOf("top", "right", "bottom", "left")
        val CORNERS = listOf("top-left", "top-right", "bottom-right", "bottom-left")
        val BORDER_STYLES = setOf("none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset")
        val BORDER_WIDTH_KEYWORDS = setOf("thin", "medium", "thick")
        val BACKGROUND_REPEATS = setOf("repeat", "repeat-x", "repeat-y", "no-repeat", "space", "round")
        val FONT_SIZE_KEYWORDS = setOf("xx-small", "x-small", "small", "medium", "large", "x-large", "xx-large", "smaller", "larger")
        val KEYWORDS = setOf(
            "auto", "inherit", "initial", "unset", "revert", "none", "normal", "bold", "bolder", "lighter",
            "italic", "oblique", "block", "inline", "inline-block", "flex", "grid", "table", "table-row", "table-cell",
            "list-item", "contents", "left", "right", "both", "start", "end", "center", "justify", "top", "bottom",
            "middle", "baseline", "text-top", "text-bottom", "sub", "super", "cover", "contain", "repeat", "repeat-x",
            "repeat-y", "no-repeat", "space", "round", "inside", "outside", "disc", "circle", "square", "decimal",
            "always", "avoid", "avoid-page", "page", "visible", "hidden", "collapse", "separate", "border-box",
            "content-box", "nowrap", "pre", "pre-wrap", "break-spaces", "row", "column", "solid", "dashed", "dotted",
            "double", "groove", "ridge", "inset", "outset", "thin", "medium", "thick", "landscape", "portrait"
        )
        val COLOR_PROPERTIES = setOf(
            "color", "background-color", "border-color", "border-top-color", "border-right-color", "border-bottom-color",
            "border-left-color", "text-decoration-color", "outline-color"
        )
        val COLOR_AWARE_PROPERTIES = COLOR_PROPERTIES + setOf("background", "border", "border-top", "border-right", "border-bottom", "border-left", "box-shadow", "text-shadow")
        val LENGTH_PROPERTIES = setOf(
            "margin-top", "margin-right", "margin-bottom", "margin-left", "padding-top", "padding-right", "padding-bottom",
            "padding-left", "width", "height", "min-width", "min-height", "max-width", "max-height", "text-indent",
            "font-size", "line-height", "letter-spacing", "word-spacing", "vertical-align", "border-top-width",
            "border-right-width", "border-bottom-width", "border-left-width", "border-top-left-radius", "border-top-right-radius",
            "border-bottom-right-radius", "border-bottom-left-radius", "column-gap", "row-gap", "gap"
        )
        val NON_NEGATIVE_LENGTH_PROPERTIES = LENGTH_PROPERTIES - setOf(
            "margin-top", "margin-right", "margin-bottom", "margin-left", "text-indent", "letter-spacing", "word-spacing", "vertical-align"
        )
        val SUPPORTED_PROPERTIES = setOf(
            "margin", "padding", "border", "border-top", "border-right", "border-bottom", "border-left", "border-width",
            "border-style", "border-color", "border-radius", "background", "font", "list-style"
        ) + LENGTH_PROPERTIES + COLOR_PROPERTIES + setOf(
            "font-family", "font-weight", "font-style", "display", "float", "clear", "text-align", "vertical-align",
            "box-sizing", "background-image", "background-repeat", "background-position", "background-size", "box-shadow",
            "text-shadow", "text-decoration", "text-decoration-line", "opacity", "overflow", "visibility", "white-space",
            "break-before", "break-after", "break-inside", "page-break-before", "page-break-after", "page-break-inside",
            "orphans", "widows", "list-style-type", "list-style-position", "list-style-image", "border-collapse",
            "ruby-align", "duokan-text-indent", "duokan-bleed", "align-items", "justify-content", "flex-direction",
            "grid-template-columns", "column-gap", "row-gap", "gap"
        )
    }
}
