# Tink 在 androidx.security-crypto 中引用的 Error Prone 类型全是源码级注解，运行时
# 不参与密钥处理；依赖 POM 未把它们带进 APK，R8 需要显式知道可以安全忽略。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
