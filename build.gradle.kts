import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

plugins {
    id("com.android.application") version "9.2.0"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

// 版本号取自 git tag（发布时打的 tag 即为版本），不再维护 version.properties。
// versionName = 最近的 git tag（去掉前缀 v，如 v0.9.0 → 0.9.0）；
// versionCode 由语义化版本换算（基址 9_000_000，单调且高于历史手工码 1_000_0xx）。
// CI 可用 PLUGIN_VERSION / PLUGIN_VERSION_CODE 环境变量覆盖。
fun runGitCmd(vararg args: String): String = try {
    val process = ProcessBuilder("git", *args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output
} catch (e: Exception) {
    ""
}

fun gitTagName(): String =
    runGitCmd("describe", "--tags", "--abbrev=0").removePrefix("v")

/** tag / 环境变量版本号统一去掉可选的 v 前缀，Android versionName 只保留语义化版本。 */
fun normalizeVersionName(version: String): String = version.trim().removePrefix("v")

fun gitCommitCount(): Int =
    runGitCmd("rev-list", "--count", "HEAD").toIntOrNull() ?: 0

/** 语义化版本 → versionCode：9_000_000 + major*100_000 + minor*1_000 + patch。 */
fun tagToVersionCode(tag: String): Int? {
    val parts = tag.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size < 2) return null
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return 9_000_000 + major * 100_000 + minor * 1_000 + patch
}

// Sherpa-ONNX 官方 AAR 版本（构建期拉取，离线时手动放 libs/）。
// 同时用于：默认 .so 下载地址与 CI 发布的 .so zip 命名。
val sherpaAarVersion = "1.12.21"

// 当前构建对应的 Git tag（带 v 前缀），用于生成默认 .so 下载地址（指向本版本的 GitHub Release 资产）。
// CI release：PLUGIN_VERSION 环境变量 = github.ref_name（如 v0.9.5）；
// 本地/未打 tag：取最近 tag，都无则为占位 v0.0.0-dev（仅占位，本地测试时由用户改成自建源）。
val releaseTag: String = run {
    val raw = (System.getenv("PLUGIN_VERSION")?.takeIf { it.isNotBlank() }
        ?: runGitCmd("describe", "--tags", "--abbrev=0")).trim()
    when {
        raw.startsWith("v") -> raw
        raw.isNotBlank() -> "v$raw"
        else -> "v0.0.0-dev"
    }
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.quicksend"
    compileSdk = 35

    lint {
        abortOnError = false
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.quicksend"
        minSdk = 24
        targetSdk = 35
        val tagName = gitTagName()
        val envVersionName = System.getenv("PLUGIN_VERSION")?.takeIf { it.isNotBlank() }?.let(::normalizeVersionName)
        val envVersionCode = System.getenv("PLUGIN_VERSION_CODE")
        versionName = envVersionName ?: tagName.ifBlank { "0.0.0-dev" }
        versionCode = envVersionCode?.toIntOrNull()
            ?: tagToVersionCode(tagName)
            ?: (1_000_000 + gitCommitCount())

        // 默认 .so 下载地址：指向本版本 GitHub Release 下按 ABI 拆分的 zip 资产。
        // {ABI} 占位符由运行时按设备首选 ABI 替换（见 NativeLibManager.defaultUrl）。
        buildConfigField(
            "String",
            "NATIVE_LIB_DEFAULT_URL",
            "\"https://github.com/loveyu/fcitx5-plugin-quicksend/releases/download/$releaseTag/sherpa-onnx-$sherpaAarVersion-{ABI}.zip\""
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

            storeFile = (System.getenv("SIGNING_STORE_FILE") ?: props.getProperty("signing.storeFile"))?.let { file(it) }
            storeType = "PKCS12"
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: props.getProperty("signing.storePassword") ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: props.getProperty("signing.keyAlias") ?: "fcitx5-android-quicksend-plugin"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: props.getProperty("signing.keyPassword") ?: storePassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["fcitxAppId"] = "org.fcitx.fcitx5.android.debug"
            buildConfigField("String", "FCITX_APP_ID", "\"org.fcitx.fcitx5.android.debug\"")
        }
        release {
            manifestPlaceholders["fcitxAppId"] = "org.fcitx.fcitx5.android"
            buildConfigField("String", "FCITX_APP_ID", "\"org.fcitx.fcitx5.android\"")
            // 不开启 R8 混淆/压缩：插件命名空间代码量小，混淆收益有限，反而会重命名
            // Sherpa-ONNX 的 JNI 字段（如 OnlineRecognizerConfig.decodingMethod），导致 native
            // GetFieldID 失败、本地模型加载抛 "Failed to get field ID for decodingMethod"。
            // 关闭后既能修复该崩溃，也保留可读堆栈，便于排查问题。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 不再按 ABI 拆包：native 库（Sherpa .so）改为运行时按需下载、不随 APK 打包，
    // 产出一个体积很小的 universal 单 APK。.so 在 CI 发布时按 ABI 打 zip 上传到 Release 资产。
    packaging {
        jniLibs {
            // 排除 Sherpa 的 4 个 .so，确保不打入 APK（运行时由 NativeLibManager 动态加载）
            excludes += listOf(
                "**/libonnxruntime.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so",
                "**/libsherpa-onnx-jni.so"
            )
        }
    }
}

dependencies {
    // Room: 结构化存储 quicksend 条目（CRUD + 排序 + 计数）
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // 序列化 ContentSegment
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // UI: 全应用 Jetpack Compose + Material3（页面与弹窗；悬浮窗仍为编程式 View）。
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 语音：Sherpa-ONNX 官方 AAR（放 libs/；运行 ./gradlew downloadSherpaAar 拉取或手动放置）
    // builtBy 声明 AAR 由 downloadSherpaAar 产出，所有消费任务（compile / collectDependencies 等）自动依赖它
    implementation(fileTree("libs") { include("*.aar"); builtBy("downloadSherpaAar") })

    // 网络：模型下载 + 后续在线 Provider / AI 润色统一走 OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // tar.bz2 解压：Sherpa 模型从 GitHub Releases 以 .tar.bz2 分发
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
}

// Sherpa-ONNX 官方 AAR 拉取（构建期可选任务；离线/被墙时手动放 AAR 到 libs/）。
// HuggingFace 不通时为 gradle 设置代理（gradle.properties 的 https.proxyHost/Port）或改下面 URL 为镜像。
val sherpaAarFile = file("libs/sherpa-onnx-$sherpaAarVersion.aar")
tasks.register("downloadSherpaAar") {
    description = "Download the official Sherpa-ONNX Android AAR into libs/"
    outputs.file(sherpaAarFile)
    doLast {
        if (sherpaAarFile.exists()) return@doLast
        sherpaAarFile.parentFile.mkdirs()
        // CI 访问 huggingface.co 偶发超时，故多源 + 重试 + 兜底镜像
        val sources = listOf(
            "https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-$sherpaAarVersion.aar",
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-$sherpaAarVersion.aar"
        )
        var lastError: Throwable? = null
        for (url in sources) {
            for (attempt in 1..3) {
                println("Downloading Sherpa-ONNX AAR $sherpaAarVersion (attempt $attempt) from $url")
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 60000
                        readTimeout = 180000
                        instanceFollowRedirects = true
                    }
                    conn.connect()
                    conn.inputStream.use { input ->
                        sherpaAarFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (sherpaAarFile.length() > 1_000_000) {
                        println("Sherpa AAR saved to ${sherpaAarFile.absolutePath} (${sherpaAarFile.length()} bytes)")
                        return@doLast
                    }
                    sherpaAarFile.delete()
                } catch (e: Throwable) {
                    println("Attempt failed: ${e.message}")
                    sherpaAarFile.delete()
                    lastError = e
                }
            }
        }
        throw lastError ?: RuntimeException("Failed to download Sherpa AAR")
    }
}

// CI 中 AAR 被 gitignore（libs/ 为空），需在编译前自动下载；本地已存在则跳过。
// fileTree("libs") 延迟解析，故编译任务依赖下载任务后即可拿到 AAR。
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn("downloadSherpaAar")
}
