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
            isMinifyEnabled = false
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
    implementation(fileTree("libs") { include("*.aar") })

    // 网络：模型下载 + 后续在线 Provider / AI 润色统一走 OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

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
        val url = "https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-$sherpaAarVersion.aar"
        println("Downloading Sherpa-ONNX AAR $sherpaAarVersion ...")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connect()
        conn.inputStream.use { input ->
            sherpaAarFile.outputStream().use { output -> input.copyTo(output) }
        }
        println("Sherpa AAR saved to ${sherpaAarFile.absolutePath}")
    }
}
