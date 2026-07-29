import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

plugins {
    id("com.android.application") version "9.2.0"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.quicksend"
    compileSdk = 35

    lint {
        abortOnError = false
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.quicksend"
        minSdk = 24
        targetSdk = 35
        val versionProps = Properties()
        listOf("version.properties", "version.local.properties").forEach { name ->
            val f = rootProject.file(name)
            if (f.exists()) {
                f.inputStream().use { versionProps.load(it) }
            }
        }
        val envVersionName = System.getenv("PLUGIN_VERSION")
        val envVersionCode = System.getenv("PLUGIN_VERSION_CODE")
        val fileVersionName = versionProps.getProperty("versionName")
        val fileVersionCode = versionProps.getProperty("versionCode")
        val fallbackVersionName = "0.1.0"
        val fallbackVersionCode = 1000000

        versionName = envVersionName ?: fileVersionName ?: fallbackVersionName
        versionCode = envVersionCode?.toIntOrNull()
            ?: fileVersionCode?.toIntOrNull()
            ?: fallbackVersionCode
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

    // 按 ABI 拆分输出独立 APK，避免单包过大（Sherpa native 库随 ABI 拆开）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
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

    // UI: 条目列表
    implementation("androidx.recyclerview:recyclerview:1.3.2")

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
val sherpaAarVersion = "1.12.21"
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
