package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.retrieval.ReadingScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ChartToolsTest {

    private fun spec(raw: String) = ChartSpecCodec.decode(raw)

    @Test fun `valid bar chart passes and is trimmed`() {
        val outcome = spec("""{"type":"BAR","title":"  出场  ","labels":["第1章","第2章"],"series":[{"name":"林冲","values":[3,5]}]}""")
        val valid = outcome as ChartSpecCodec.Outcome.Valid
        assertEquals("bar", valid.spec.type)
        assertEquals("出场", valid.spec.title)
    }

    @Test fun `mismatched values and oversized data explain how to fix`() {
        val mismatch = spec("""{"type":"line","labels":["a","b","c"],"series":[{"values":[1,2]}]}""") as ChartSpecCodec.Outcome.Invalid
        assertTrue(mismatch.reason.contains("3"))
        val many = (1..51).joinToString(",") { "\"$it\"" }
        val tooMany = spec("""{"type":"bar","labels":[$many],"series":[{"values":[${(1..51).joinToString(",")}]}]}""")
        assertTrue(tooMany is ChartSpecCodec.Outcome.Invalid)
        assertTrue(spec("""{"type":"pie","labels":["a","b"],"series":[{"values":[1,2]},{"values":[1,2]}]}""") is ChartSpecCodec.Outcome.Invalid)
        assertTrue(spec("""{"type":"radar","labels":["a","b"],"series":[{"values":[1,2]}]}""") is ChartSpecCodec.Outcome.Invalid)
        assertTrue(spec("""{"type":"area"}""") is ChartSpecCodec.Outcome.Invalid)
        assertTrue(spec("not json") is ChartSpecCodec.Outcome.Invalid)
    }

    @Test fun `relation edges must reference nodes`() {
        val bad = spec("""{"type":"relation","nodes":[{"id":"a"},{"id":"b"}],"edges":[{"from":"a","to":"c"}]}""")
        assertTrue(bad is ChartSpecCodec.Outcome.Invalid)
        val good = spec("""{"type":"relation","nodes":[{"id":"a","label":"林冲"},{"id":"b"}],"edges":[{"from":"a","to":"b","label":"兄弟"}]}""")
        assertEquals("b", (good as ChartSpecCodec.Outcome.Valid).spec.nodes[1].label)
    }

    @Test fun `history only draws charts whose call succeeded`() = runTest {
        val args = """{"type":"timeline","events":[{"time":"第1章","title":"出发"}]}"""
        val result = CreateChartTool().execute(kotlinx.serialization.json.Json.parseToJsonElement(args) as JsonObject)
        assertTrue(result is ToolResult.Success)
        assertNotNull(ChartSpecCodec.fromToolCall(args, result.content))
        assertNull(ChartSpecCodec.fromToolCall(args, null))
        assertNull(ChartSpecCodec.fromToolCall(args, "图表未生成：不对"))
        val failure = CreateChartTool().execute(buildJsonObject { put("type", "timeline") })
        assertTrue(failure is ToolResult.Failure)
    }

    @Test fun `mention counts stay inside the reading scope`() = runTest {
        val chapters = listOf("林冲林冲教头", "林冲与鲁智深", "未读：林冲林冲林冲")
        val tool = CountMentionsTool(
            totalChapters = { chapters.size },
            loadChapter = { index -> ChapterDocument(index, "第${index + 1}章", chapters[index]) },
            readingScope = ReadingScope.upto(1, chapters[1].length)
        )
        val result = tool.execute(buildJsonObject {
            putJsonArray("terms") {
                addJsonObject { put("name", "林冲"); putJsonArray("aliases") { add("教头") } }
                addJsonObject { put("name", "鲁智深") }
            }
        })
        assertTrue(result is ToolResult.Success)
        assertTrue(result.content, result.content.contains("林冲（共 4 次）: [3, 1]"))
        assertTrue(result.content.contains("鲁智深（共 1 次）: [0, 1]"))
        assertTrue(result.content.contains("未读部分不计"))
    }

    @Test fun `buckets keep at most fifty points`() {
        val perChapter = (0 until 120).map { listOf(1) }
        val buckets = MentionCounts.bucket(perChapter, 0, requested = 1)
        assertTrue(buckets.labels.size <= 50)
        assertEquals(120, buckets.values.single().sum())
        assertEquals("第1-3章", buckets.labels.first())
    }

    @Test fun `relation layout is deterministic and spreads nodes`() {
        val nodes = listOf("a", "b", "c", "d", "e", "f")
        val edges = listOf("a" to "b", "a" to "c", "a" to "d", "d" to "e", "e" to "f")
        val first = RelationLayout.layout(nodes, edges)
        assertEquals(first, RelationLayout.layout(nodes, edges))
        val points = first.values.toList()
        assertTrue(points.all { it.first in 0f..1f && it.second in 0f..1f })
        for (i in points.indices) for (j in i + 1 until points.size) {
            val d = hypot((points[i].first - points[j].first).toDouble(), (points[i].second - points[j].second).toDouble())
            assertTrue("nodes $i and $j overlap: $d", d > 0.08)
        }
    }
}
