package com.mozhi.reader.core.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 纯 JVM 核对字符串资源：翻译只能覆盖默认资源已有的键，占位符与默认资源一致，
 * translatable="false" 的条目不得出现在翻译里。资源编译只查 XML 语法，查不出这些。
 */
class LocalizationResourceParityTest {
    private val resDir = File("src/main/res")

    private data class Entry(val text: String, val translatable: Boolean)

    private fun load(dir: String): Map<String, Entry> {
        val file = File(resDir, "$dir/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val entries = linkedMapOf<String, Entry>()
        val nodes = document.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            val translatable = element.getAttribute("translatable") != "false"
            when (element.tagName) {
                "string" -> entries[name] = Entry(element.textContent, translatable)
                "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        entries["$name#${item.getAttribute("quantity")}"] = Entry(item.textContent, translatable)
                    }
                }
            }
        }
        return entries
    }

    private fun placeholders(text: String): Set<String> =
        Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]""").findAll(text).map { it.value }.toSet()

    private fun translationDirs(): List<String> =
        resDir.listFiles().orEmpty()
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }
            .map { it.name }

    @Test
    fun translationsOnlyCoverDefaultKeysWithMatchingPlaceholders() {
        val defaults = load("values")
        val defaultNames = defaults.keys.map { it.substringBefore('#') }.toSet()
        val problems = mutableListOf<String>()
        translationDirs().forEach { dir ->
            load(dir).forEach { (key, entry) ->
                val name = key.substringBefore('#')
                val base = defaults[key] ?: defaults["$name#other"]
                when {
                    name !in defaultNames -> problems += "$dir: $name 不在默认资源中"
                    base == null -> Unit // 语言自有的复数类别（如英文 one）
                    !base.translatable -> problems += "$dir: $name 标记为 translatable=false，不应翻译"
                    placeholders(base.text) != placeholders(entry.text) ->
                        problems += "$dir: $key 占位符 ${placeholders(entry.text)} ≠ 默认 ${placeholders(base.text)}"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun formattedStringsUseIndexedPlaceholders() {
        val problems = (listOf("values") + translationDirs()).flatMap { dir ->
            load(dir).filter { (_, entry) -> placeholders(entry.text).any { !it.contains('$') } }
                .map { (key, _) -> "$dir: $key 请使用 %1\$s 这类带序号的占位符" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
