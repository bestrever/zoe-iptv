// 顶层构建文件
plugins {
    // AGP 9.1.0：支持 compileSdk 37（GeckoView 158 要求 minCompileSdk=37，
    // 且其传递依赖 androidx.core 1.19.0 要求 AGP >= 9.1.0）。
    // AGP 9 内置 Kotlin 支持，org.jetbrains.kotlin.android 插件已废弃
    //（AGP 9 检测到该插件会直接报错），故不再声明。
    id("com.android.application") version "9.1.0" apply false
}
