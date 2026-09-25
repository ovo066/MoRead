package com.mozhi.reader.core.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 界面层硬编码中文的棘轮：`feature/`、`ui/` 与 `MainActivity` 里每个文件含汉字的字符串字面量数量
 * 只许减少、不许增加。新界面文案请写进 `res/values/strings.xml`（并补 `values-en`）。
 *
 * 基线在 `src/test/resources/i18n/hardcoded-han-baseline.txt`。迁移后数量变少时本测试也会失败，
 * 提醒收紧基线；确有必要新增（例如解析书籍内容用的中文正则、不对用户显示的诊断文本）时同样重新生成：
 *
 * ```
 * MOREAD_UPDATE_I18N_BASELINE=1 ./gradlew :app:testDebugUnitTest --tests '*HardcodedTextRatchetTest*'
 * ```
 *
 * `ai/`、`core/` 不在范围内：那里的中文大多是发给模型的提示词、工具结果和解析规则，不属于界面翻译。
 */
class HardcodedTextRatchetTest {
    private val sourceRoot = File("src/main/java/com/mozhi/reader")
    private val baselineFile = File("src/test/resources/i18n/hardcoded-han-baseline.txt")
    private val scopes = listOf("feature", "ui", "MainActivity.kt")

    @Test
    fun uiLayerDoesNotGainHardcodedChineseLiterals() {
        val current = scan()
        if (System.getenv("MOREAD_UPDATE_I18N_BASELINE") == "1") {
            baselineFile.parentFile?.mkdirs()
            baselineFile.writeText(
                "# path<TAB>含汉字的字符串字面量数；由 HardcodedTextRatchetTest 生成，勿手改\n" +
                    current.entries.joinToString("") { (path, count) -> "$path\t$count\n" }
            )
            return
        }
        val baseline = baselineFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').trim().toInt() }
        val grown = current.filter { (path, count) -> count > (baseline[path] ?: 0) }
        val shrunk = baseline.filter { (path, count) -> (current[path] ?: 0) < count }
        if (grown.isNotEmpty()) {
            fail(
                "以下文件新增了硬编码中文界面文案，请改用字符串资源（stringResource / UiText）：\n" +
                    grown.entries.joinToString("\n") { (path, count) -> "  $path: ${baseline[path] ?: 0} → $count" } +
                    "\n确实不是界面文案时，用 MOREAD_UPDATE_I18N_BASELINE=1 重新生成基线。"
            )
        }
        if (shrunk.isNotEmpty()) {
            fail(
                "以下文件的硬编码中文变少了，请用 MOREAD_UPDATE_I18N_BASELINE=1 重新生成基线以锁定进度：\n" +
                    shrunk.entries.joinToString("\n") { (path, count) -> "  $path: $count → ${current[path] ?: 0}" }
            )
        }
    }

    @Test
    fun scannerSkipsCommentsAndCountsTemplateSegments() {
        val source = """
            // "注释里的中文"
            /* "块注释" */
            val a = "书架"
            val b = "Books ${'$'}{count} 本"
            val c = "失败：${'$'}{error ?: "未知"}"
            val d = '"'
            val e = ""${'"'}多行
            原始字符串""${'"'}
            val f = "plain ${'$'}name"
        """.trimIndent()
        // 书架、「 本」、「失败：」、嵌套的「未知」、原始字符串。
        assertEquals(5, HanLiteralScanner(source).count())
    }

    private fun scan(): Map<String, Int> = scopes.asSequence()
        .map { File(sourceRoot, it) }
        .flatMap { root -> if (root.isFile) sequenceOf(root) else root.walkTopDown().filter { it.extension == "kt" } }
        .map { file -> file.relativeTo(sourceRoot).invariantSeparatorsPath to HanLiteralScanner(file.readText()).count() }
        .filter { it.second > 0 }
        .sortedBy { it.first }
        .toMap(LinkedHashMap())
}

/** 统计 Kotlin 源码中含汉字的字符串片段；跳过注释与字符字面量，模板 `${…}` 内的嵌套字面量单独计数。 */
internal class HanLiteralScanner(private val source: String) {
    private var i = 0
    private var count = 0

    fun count(): Int {
        i = 0
        count = 0
        scanCode(inTemplate = false)
        return count
    }

    /** 扫描代码；[inTemplate] 时遇到配对的右花括号即返回到外层字符串。 */
    private fun scanCode(inTemplate: Boolean) {
        var depth = 0
        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                source.startsWith("\"\"\"", i) -> { i += 3; readString(raw = true) }
                c == '"' -> { i++; readString(raw = false) }
                c == '\'' -> {
                    val end = source.indexOf('\'', if (source.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                    i = if (end < 0) i + 1 else end + 1
                }
                c == '{' -> { depth++; i++ }
                c == '}' -> {
                    i++
                    if (inTemplate && depth == 0) return
                    depth--
                }
                else -> i++
            }
        }
    }

    private fun readString(raw: Boolean) {
        var hasHan = false
        while (i < source.length) {
            val c = source[i]
            when {
                !raw && c == '\\' -> i += 2
                raw && source.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < source.length && source[i] == '"') i++
                    break
                }
                !raw && (c == '"' || c == '\n') -> { if (c == '"') i++; break }
                c == '$' && source.getOrNull(i + 1) == '{' -> {
                    if (hasHan) count++
                    hasHan = false
                    i += 2
                    scanCode(inTemplate = true)
                }
                else -> {
                    if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN) hasHan = true
                    i++
                }
            }
        }
        if (hasHan) count++
    }
}
