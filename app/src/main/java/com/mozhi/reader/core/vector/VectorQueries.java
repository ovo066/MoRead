package com.mozhi.reader.core.vector;

import java.util.ArrayList;
import java.util.List;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.ObjectWithScore;
import io.objectbox.query.Query;
import io.objectbox.query.QueryCondition;

/**
 * 向量检索门面（生成的 *_ 条件类只在这里出现，理由见 {@link VectorDb}）。
 * 返回的 score 是余弦距离，越小越相近，升序排列。
 *
 * ObjectBox 的近邻查询是「先找 N 个近邻、再套其他条件」：过滤条件（防剧透章节上限、
 * bookId、personaId）可能把候选集全部滤掉。这里自适应扩大候选数直到凑够 topK 或
 * 穷尽（上限 {@link #MAX_FETCH}），保证已读范围内的结果不会被后文挤出。
 */
public final class VectorQueries {

    private static final int MAX_FETCH = 4096;

    private VectorQueries() {
    }

    /**
     * 书内切片检索。maxChapterIndex（含）是防剧透硬上限，过滤在查询层完成，
     * 调用方传入用户当前进度章节即可。返回最多 topK 条。
     */
    public static List<ObjectWithScore<BookChunk>> searchChunks(
            BoxStore store,
            long bookId,
            float[] queryVector,
            int topK,
            int maxChapterIndex
    ) {
        Box<BookChunk> box = store.boxFor(BookChunk.class);
        return adaptiveSearch(box.count(), topK, fetchCount -> box
                .query(
                        BookChunk_.embedding.nearestNeighbors(queryVector, fetchCount)
                                .and(BookChunk_.bookId.equal(bookId))
                                .and(BookChunk_.chapterIndex.lessOrEqual(maxChapterIndex))
                )
                .build());
    }

    /** 角色记忆检索，按 personaId 隔离。返回最多 topK 条。 */
    public static List<ObjectWithScore<MemoryEntry>> searchMemories(
            BoxStore store,
            long personaId,
            float[] queryVector,
            int topK
    ) {
        return searchMemories(store, personaId, queryVector, topK, null, 0L);
    }

    /**
     * 带范围过滤的角色记忆检索。
     *
     * @param bookId 非 null 时只召回这本书产生的记忆（「跨书记忆」开关关闭时用）。
     *   注意跨书的全局记忆 bookId 为 null，收窄时它们同样不参与——这正是关闭该开关的语义。
     * @param maskId 当前生效的用户面具（0 = 未启用面具）。本人层记忆（maskId=0）永远可见；
     *   面具内的经历只在同一面具下可见，不同面具之间互相穿帮是绝对不能出现的。
     */
    public static List<ObjectWithScore<MemoryEntry>> searchMemories(
            BoxStore store,
            long personaId,
            float[] queryVector,
            int topK,
            Long bookId,
            long maskId
    ) {
        Box<MemoryEntry> box = store.boxFor(MemoryEntry.class);
        return adaptiveSearch(box.count(), topK, fetchCount -> {
            QueryCondition<MemoryEntry> condition =
                    MemoryEntry_.embedding.nearestNeighbors(queryVector, fetchCount)
                            .and(MemoryEntry_.personaId.equal(personaId));
            if (bookId != null) {
                condition = condition.and(MemoryEntry_.bookId.equal(bookId));
            }
            condition = condition.and(
                    maskId == 0L
                            ? MemoryEntry_.maskId.equal(0L)
                            : MemoryEntry_.maskId.equal(0L).or(MemoryEntry_.maskId.equal(maskId))
            );
            return box.query(condition).build();
        });
    }

    /** 记忆管理页用：按时间倒序分页列出某角色的全部记忆。 */
    public static List<MemoryEntry> listMemories(
            BoxStore store,
            long personaId,
            int offset,
            int limit
    ) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.personaId.equal(personaId))
                .orderDesc(MemoryEntry_.createdAt)
                .build();
        try {
            return query.find(offset, limit);
        } finally {
            query.close();
        }
    }

    /** 删除单条记忆（记忆管理页的左滑删除）。 */
    public static void removeMemory(BoxStore store, long id) {
        store.boxFor(MemoryEntry.class).remove(id);
    }

    /** 清空某角色的全部记忆；角色卡本身与它写下的批注笔记不受影响。 */
    public static void removeMemoriesForPersona(BoxStore store, long personaId) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.personaId.equal(personaId))
                .build();
        try {
            query.remove();
        } finally {
            query.close();
        }
    }

    /** 已有切片的章节集合。写入按章原子（embedding 管线保证），出现即完整。 */
    public static int[] chaptersWithChunks(BoxStore store, long bookId) {
        Query<BookChunk> query = store.boxFor(BookChunk.class)
                .query(BookChunk_.bookId.equal(bookId))
                .build();
        try {
            return query.property(BookChunk_.chapterIndex).distinct().findInts();
        } finally {
            query.close();
        }
    }

    /** 角色的长期记忆条数（伴读页与角色编辑页的指标胶囊）。 */
    public static long countMemories(BoxStore store, long personaId) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.personaId.equal(personaId))
                .build();
        try {
            return query.count();
        } finally {
            query.close();
        }
    }

    /** 同一会话水位的记忆是否已写入；Room 水位更新前崩溃时靠它避免重复固化。 */
    public static boolean hasMemoryBatch(
            BoxStore store,
            long conversationId,
            long sourceMessageId
    ) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(
                        MemoryEntry_.conversationId.equal(conversationId)
                                .and(MemoryEntry_.sourceMessageId.equal(sourceMessageId))
                )
                .build();
        try {
            return query.count() > 0;
        } finally {
            query.close();
        }
    }

    /** 会话历史被编辑/删除时清掉由它固化出的旧记忆，随后可按新历史重新固化。 */
    public static void removeMemoriesForConversation(BoxStore store, long conversationId) {
        Query<MemoryEntry> query = store.boxFor(MemoryEntry.class)
                .query(MemoryEntry_.conversationId.equal(conversationId))
                .build();
        try {
            query.remove();
        } finally {
            query.close();
        }
    }

    /** 删书时清掉该书全部切片。 */
    public static void removeChunksForBook(BoxStore store, long bookId) {
        Query<BookChunk> query = store.boxFor(BookChunk.class)
                .query(BookChunk_.bookId.equal(bookId))
                .build();
        try {
            query.remove();
        } finally {
            query.close();
        }
    }

    /** 更换 embedding 模型后清空全部切片（不同模型坐标系不可混用）；重建按需触发。 */
    public static void removeAllChunks(BoxStore store) {
        store.boxFor(BookChunk.class).removeAll();
    }

    private interface VectorQueryFactory<T> {
        Query<T> build(int fetchCount);
    }

    private static <T> List<ObjectWithScore<T>> adaptiveSearch(
            long totalCandidates,
            int topK,
            VectorQueryFactory<T> factory
    ) {
        int fetch = Math.max(topK * 4, 32);
        while (true) {
            Query<T> query = factory.build(fetch);
            List<ObjectWithScore<T>> hits;
            try {
                hits = query.findWithScores();
            } finally {
                query.close();
            }
            boolean exhausted = fetch >= totalCandidates || fetch >= MAX_FETCH;
            if (hits.size() >= topK || exhausted) {
                return hits.size() > topK
                        ? new ArrayList<>(hits.subList(0, topK))
                        : hits;
            }
            fetch = (int) Math.min((long) fetch * 4, MAX_FETCH);
        }
    }
}
