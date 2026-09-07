package com.mozhi.reader.core.vector

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * libobjectbox-jni.so 会用 JNI 反向构造几个 Java 类（按名字找类和构造器）。
 * objectbox-java 4.3.0 自带的 consumer proguard 规则漏掉了 4.0 才引入的两个向量结果类型，
 * R8 会把它们整类收走：JVM 单测和 debug 包都测不出来，minify 的 release/performance 包里
 * 每次 findWithScores() 都在原生层炸掉，向量检索与记忆召回全部静默失效（vc66 实测）。
 *
 * 因此把「keep 规则存在」当成一条可断言的约定守在这里；升级 ObjectBox 后按
 * proguard-rules.pro 里记的 grep 办法重新核对原生层要找的类名清单。
 */
class VectorJniKeepRulesTest {

    /** 原生层按名字构造，必须逃过 R8 的删除与改名。 */
    private val jniConstructedTypes = listOf(
        "io.objectbox.query.ObjectWithScore",
        "io.objectbox.query.IdWithScore"
    )

    @Test
    fun proguardRulesKeepJniConstructedObjectBoxTypes() {
        val rules = proguardRulesFile().readText()
        jniConstructedTypes.forEach { type ->
            val keepRule = Regex("""-keep\s+class\s+${Regex.escape(type)}\b""")
            assertTrue(
                "proguard-rules.pro 缺少 $type 的 keep 规则：R8 会把它收走，" +
                    "minify 包里所有向量检索都会在原生层失败",
                keepRule.containsMatchIn(rules)
            )
        }
    }

    /** 单测工作目录随 Gradle 版本可能是模块目录或仓库根，两处都找。 */
    private fun proguardRulesFile(): File {
        val candidates = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
            File("../app/proguard-rules.pro")
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("找不到 proguard-rules.pro（工作目录 ${File("").absolutePath}）")
    }
}
