plugins {
    id("com.android.application")
    // AGP 9 内置 Kotlin 支持，不再应用 org.jetbrains.kotlin.android 插件
}

android {
    namespace = "com.zoe.iptv"
    // GeckoView 158 的 AAR 元数据要求 compileSdk >= 37（minCompileSdk=37）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zoe.iptv"
        minSdk = 26          // GeckoView 158 要求 minSdk >= 26（Android 8.0）
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
        // GeckoView 只保留 arm 双 ABI（砍掉 x86_64 省体积）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // debug 包不需要剥离 .so 调试符号：省磁盘、省时间（2G 小内存机器上 strip 会爆磁盘）
            packagingOptions {
                doNotStrip("**/*.so")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 内置 Kotlin：用 compilerOptions DSL（kotlinOptions 已移除）
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

// AGP 9.1.0 已满足 androidx.core 1.19.0 的全部要求
//（minCompileSdk=37、minAndroidGradlePluginVersion=9.1.0），
// GeckoView 158 的传递约束可自然生效，无需强制降级。
//
// 另：GeckoView 158 的模块元数据强制要求 kotlin-stdlib 2.4.20，
// 但 AGP 9.1.0 内置的 Kotlin 编译器是 2.2.0（最高只能读取 2.3.x 元数据），
// 读不懂 2.4.0 元数据会直接编译失败；降级到与编译器一致的 2.2.20。
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // 播放内核：火狐 GeckoView（打包进 APK，不依赖系统 WebView，各设备体验一致）
    implementation("org.mozilla.geckoview:geckoview:158.0.20261007201254")
    // 台标图片加载
    implementation("io.coil-kt:coil:2.6.0")
    // 协程（播放列表 IO 加载）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
