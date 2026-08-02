package com.mozhi.reader.core.vector;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.HnswIndex;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.VectorDistanceType;

/** 角色长期记忆。写法约束同 {@link BookChunk}：纯 Java、无关系字段。 */
@Entity
public class MemoryEntry {
    @Id
    public long id;
    public long personaId;
    /** 可空：null 为跨书的全局记忆。 */
    public Long bookId;
    /** 来源会话与本批最后一条消息；用于跨 Room/ObjectBox 的幂等固化。 */
    public long conversationId;
    public long sourceMessageId;
    public String summary;
    /** CHAT_SUMMARY | EVENT。 */
    public String sourceType;
    public long createdAt;
    @HnswIndex(dimensions = VectorDb.EMBEDDING_DIMENSIONS, distanceType = VectorDistanceType.COSINE)
    public float[] embedding;
}
