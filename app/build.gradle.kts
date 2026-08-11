import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

providers.gradleProperty("moreadBuildDir")
    .orNull
    ?.takeIf(String::isNotBlank)
    ?.let { customBuildDirectory -> layout.buildDirectory.set(file(customBuildDirectory)) }

// 正式发布签名：本地优先读取 gitignore 的 keystore.properties；CI 只从 GitHub
// Secrets 注入的环境变量读取。两种方式都不把密钥或口令写进仓库。
val releaseKeystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
val releaseStoreFile = releaseKeystoreProps?.getProperty("storeFile")
    ?: System.getenv("MOREAD_RELEASE_STORE_FILE")
val releaseStorePassword = releaseKeystoreProps?.getProperty("storePassword")
    ?: System.getenv("MOREAD_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseKeystoreProps?.getProperty("keyAlias")
    ?: System.getenv("MOREAD_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseKeystoreProps?.getProperty("keyPassword")
    ?: System.getenv("MOREAD_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.mozhi.reader"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.mozhi.reader"
        minSdk = 26
        targetSdk = 37
        // 测试期曾发过仓库外的高编号包，编号跳档保证覆盖安装不降级。
        versionCode = 48
        versionName = "0.10.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        javaCompileOptions {
            annotationProcessorOptions {
                // ObjectBox 模型文件与 Room schema 同理，纳入版本控制保 UID 稳定。
                arguments["objectbox.modelPath"] = "$projectDir/objectbox-models/default.json"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }

        // 可直接覆盖开发期 debug 包的真实性能验证包：沿用 debug 签名保证本地数据不丢，
        // 但关闭 debuggable 并启用与 release 相同的 R8/资源收缩。正式发布仍使用 release
        // 及独立发布签名，不能把 debug keystore 当生产签名。
        create("performance") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/jsoup/**"
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // AGP 9.3 lint 在当前强制 JDK 17 工具链中会误调用 Java 21 的
        // java.util.List.removeLast()，分析任意 Kotlin 文件时直接 NoSuchMethodError。
        // 仅关闭打包时自动执行的 lint-vital；常规编译与 JVM/仪器测试不受影响。
        checkReleaseBuilds = false
    }

    // MigrationTestHelper 从测试 APK 的 assets 读取各历史版本 schema。
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.markdown.renderer.m3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.coil.compose)
    implementation(libs.juniversalchardet)
    implementation(libs.jsoup)
    implementation(libs.android.svg)

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)

    // ObjectBox 向量库走免插件手动集成（DECISIONS 2026-07-28）：
    // 官方 Gradle 插件只支持 kapt 且 AGP 包装最高 7.2，与 AGP9 内置 Kotlin 相抵；
    // 向量实体全部用 Java 写、无关系字段，因此 javac annotationProcessor 就够，
    // 也不需要插件的字节码 transform。生成类（MyObjectBox / *_ / *Cursor）对
    // 主源集 Kotlin 不可见，触点一律收在 core/vector 的手写 Java 门面里。
    implementation(libs.objectbox.android)
    // objectbox-android 的 POM 不声明传递依赖，BoxStore/注解所在的 objectbox-java 要显式加。
    implementation(libs.objectbox.java)
    annotationProcessor(libs.objectbox.processor)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 桌面原生库：让向量检索可以在本机 JVM 单测里真跑（Windows 开发机 / Linux CI）。
    testImplementation(libs.objectbox.windows)
    testImplementation(libs.objectbox.linux)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
