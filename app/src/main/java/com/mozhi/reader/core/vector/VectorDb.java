package com.mozhi.reader.core.vector;

import android.content.Context;

import java.io.File;

import io.objectbox.BoxStore;

/**
 * ObjectBox 建库门面。
 *
 * 注解处理器生成的类（MyObjectBox、BookChunk_、*Cursor）只对 javac 可见，主源集的
 * Kotlin 先于 javac 编译、看不到它们——所以生成类的触点全部收在本包的手写 Java 文件里，
 * Kotlin 侧只消费 {@link BoxStore}、实体类与静态门面方法。
 */
public final class VectorDb {

    /**
     * 全局向量维度。embedding 管线负责把模型输出规整到该维度（MRL 截断或整段弃用），
     * 改动它会触发 HNSW 索引重建。
     */
    public static final int EMBEDDING_DIMENSIONS = 1024;

    private VectorDb() {
    }

    /** Android 运行时建库，库文件在 filesDir/objectbox/vectors。 */
    public static BoxStore open(Context context) {
        return MyObjectBox.builder()
                .androidContext(context)
                .name("vectors")
                .build();
    }

    /** 桌面 JVM 建库（本机单测用，Windows/Linux 原生库来自 testImplementation）。 */
    public static BoxStore openAt(File directory) {
        return MyObjectBox.builder()
                .directory(directory)
                .build();
    }
}
