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

    testImplementation("junit:junit:4.13.2")
}
