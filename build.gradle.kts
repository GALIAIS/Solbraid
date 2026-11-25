buildscript {
    dependencies {
        // AGP 9.0.1 内置 Kotlin 2.2.10；Compose 需要更新的编译器。不要升到 2.4.x：AGP 9.0.1 的 R8 无法解析其元数据。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
