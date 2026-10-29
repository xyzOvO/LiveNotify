// 根构建脚本：只声明插件版本，具体版本在 app/build.gradle.kts 中使用
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
