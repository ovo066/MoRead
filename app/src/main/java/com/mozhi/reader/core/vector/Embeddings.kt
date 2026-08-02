package com.mozhi.reader.core.vector

import kotlin.math.sqrt

/** 向量维度规整与归一化（索引维度见 [VectorDb.EMBEDDING_DIMENSIONS]）。 */
object Embeddings {

    /**
     * 把模型输出规整到索引维度：超长按 MRL 截断，较短则在尾部补零，再做 L2 归一化。
     *
     * 对余弦距离而言，同一模型的向量统一补零会严格保留夹角；向量模型切换时索引管线会
     * 清空旧 BookChunk 后重建，因此不会把 384/768/1024 等不同坐标系混在一个书籍索引里。
     * 这让常见的 768 维模型无需为了 ObjectBox 固定维度而被误判为不可用。
     */
    fun conformToIndex(raw: FloatArray): FloatArray {
        require(raw.isNotEmpty()) { "embedding 返回了空向量" }
        val vector = raw.copyOf(VectorDb.EMBEDDING_DIMENSIONS)
        var squares = 0.0
        for (x in vector) squares += x.toDouble() * x
        val norm = sqrt(squares)
        require(norm > 0.0) { "embedding 是零向量，无法归一化" }
        for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        return vector
    }
}
