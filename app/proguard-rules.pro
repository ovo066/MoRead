# Tink 在 androidx.security-crypto 中引用的 Error Prone 类型全是源码级注解，运行时
# 不参与密钥处理；依赖 POM 未把它们带进 APK，R8 需要显式知道可以安全忽略。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# ObjectBox 向量检索的结果对象由 libobjectbox-jni.so 用 JNI 反向构造：
# nativeFindWithScores 返回 List<ObjectWithScore<T>>、nativeFindIdsWithScores 返回
# List<IdWithScore>，原生层按「io/objectbox/query/ObjectWithScore」这个名字找类和
# (Ljava/lang/Object;D)V 构造器。objectbox-java 4.3.0 自带的 consumer 规则只覆盖了
# 带 native 方法的类、DbException 家族、Cursor 与实体，漏掉了这两个 4.0 才引入的向量类型，
# R8 于是把它们的构造器、字段乃至整个类都收走：debug 包正常，minify 的 release/performance
# 包里每次 findWithScores() 都在原生层炸掉——search_book 的向量召回、recall_memory、
# 伴读系统提示词的记忆注入全部静默退化，而写入/计数/findInts 因为不碰这两个类照常工作，
# 所以书籍详情页还显示「索引已就绪」。与向量模型无关，换供应商同样复现。
#
# 升级 ObjectBox 后可这样重新核对原生层要找的 Java 类，逐个确认没被 R8 收走：
#   grep -aoE "io/objectbox/[A-Za-z0-9/_]+" lib/arm64-v8a/libobjectbox-jni.so | sort -u
-keep class io.objectbox.query.ObjectWithScore { *; }
-keep class io.objectbox.query.IdWithScore { *; }
