plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStoreFile = System.getenv("WA_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("WA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("WA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("WA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "com.watchagents.wa"
    // Watch4(HarmonyOS4) 兼容层约 Android 12(API 31) 量级，minSdk 放低保证可安装
    compileSdk = 37

    defaultConfig {
        applicationId = "com.watchagents.wa"
        // 1.0.0：Watchagents(WA) 首次公开发布；applicationId 与内部 3.x 包名不同，
        //        属独立应用（可与旧包共存，数据不互通），功能等价内部版本 3.3.4。
        // 3.3.x：跨机型兼容（API 26~36、圆/方/长条屏、Android 14 部分照片授权）
        // 3.3.1：表冠手感打磨 + 悬浮毛玻璃输入框 + 转场动画
        // 3.3.2：表冠方向修正（+设置项）
        // 3.3.3：表冠平滑（目标队列 + 逐帧指数跟随，不再一格一格瞬跳）
        // 3.3.4：表冠更跟手（τ 35→20ms）+ 每格 26→34dp（再 +30%）
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        resources {
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Compose 基础 (手表 UI 全新实现, 不引入 Miuix 手机组件/模糊库)
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：DeepSeek SSE 与联网请求
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}
