package com.mozhi.reader.core.vector;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.HnswIndex;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.VectorDistanceType;

/**
 * 书籍内容切片（RAG）。
 *
 * 向量实体一律用 Java 写：ObjectBox 无 KSP 支持，Java 实体让 javac 的
 * annotationProcessor 完成代码生成，绕开 kapt 与官方 Gradle 插件（见 DECISIONS 2026-07-28）。
 * 禁止加 ToOne/ToMany 关系字段——那需要插件的字节码 transform。
 *
 * 章节坐标沿用主库口径：chapterIndex 与 Room 侧一致，防剧透过滤按它裁。
 */
@Entity
public class BookChunk {
    @Id
    public long id;
    public long bookId;
    public int chapterIndex;
    /** 章内切片序号。 */
    public int chunkIndex;
    /** UTF-16 source range, end-exclusive. Legacy rows have 0/0. */
    public int startCharOffset;
    public int endCharOffset;
    public String text;
    /** 归一化后的向量；维度不等于 {@link VectorDb#EMBEDDING_DIMENSIONS} 的记录不会进索引。 */
    @HnswIndex(dimensions = VectorDb.EMBEDDING_DIMENSIONS, distanceType = VectorDistanceType.COSINE)
    public float[] embedding;
}
