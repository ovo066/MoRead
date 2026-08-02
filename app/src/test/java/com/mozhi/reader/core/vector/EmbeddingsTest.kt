package com.mozhi.reader.core.vector

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingsTest {

    @Test
    fun truncatesLongVectorAndRenormalizes() {
        val raw = FloatArray(1536) { if (it < 2) 3f else 0f }
        val conformed = Embeddings.conformToIndex(raw)

        assertEquals(VectorDb.EMBEDDING_DIMENSIONS, conformed.size)
        assertEquals(1.0, l2Norm(conformed), 1e-5)
        // 截断只丢尾部维度，保留方向：前两维等量。
        assertEquals(conformed[0], conformed[1], 1e-6f)
        assertTrue(conformed[0] > 0f)
    }

    @Test
    fun exactDimensionIsNormalizedInPlaceCopy() {
        val raw = FloatArray(VectorDb.EMBEDDING_DIMENSIONS) { if (it == 0) 2f else 0f }
        val conformed = Embeddings.conformToIndex(raw)

        assertEquals(1f, conformed[0], 1e-6f)
        assertEquals(1.0, l2Norm(conformed), 1e-5)
        // 不改调用方数组。
        assertEquals(2f, raw[0], 0f)
    }

    @Test
    fun padsShortVectorWithZerosAndPreservesCosineDirection() {
        val raw = FloatArray(768).also {
            it[0] = 3f
            it[1] = 4f
        }
        val conformed = Embeddings.conformToIndex(raw)

        assertEquals(VectorDb.EMBEDDING_DIMENSIONS, conformed.size)
        assertEquals(0.6f, conformed[0], 1e-6f)
        assertEquals(0.8f, conformed[1], 1e-6f)
        assertTrue(conformed.drop(768).all { it == 0f })
        assertEquals(1.0, l2Norm(conformed), 1e-5)
    }

    @Test
    fun rejectsEmptyVector() {
        assertThrows(IllegalArgumentException::class.java) {
            Embeddings.conformToIndex(FloatArray(0))
        }
    }

    @Test
    fun rejectsZeroVector() {
        assertThrows(IllegalArgumentException::class.java) {
            Embeddings.conformToIndex(FloatArray(VectorDb.EMBEDDING_DIMENSIONS))
        }
    }

    /** 截断后原有分量恰为零的向量同样是零向量，必须拒绝而不是除零。 */
    @Test
    fun rejectsVectorThatBecomesZeroAfterTruncation() {
        val raw = FloatArray(2048)
        raw[2047] = 1f
        assertThrows(IllegalArgumentException::class.java) {
            Embeddings.conformToIndex(raw)
        }
    }

    private fun l2Norm(vector: FloatArray): Double {
        var squares = 0.0
        for (x in vector) squares += x.toDouble() * x
        return sqrt(squares).also { assertTrue(abs(it) >= 0) }
    }
}
