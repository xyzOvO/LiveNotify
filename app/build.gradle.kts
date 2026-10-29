import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// release 签名：读取本地 keystore.properties（该文件与密钥都不入库）。
// 没有密钥文件时自动回退 debug 签名——保证任何人克隆后都能构建出可安装的包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeyFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseKey = releaseKeyFile != null && releaseKeyFile.exists()

// 版本号从最近的 git tag 自动读取：v1.0.0 → versionName "1.0.0"、versionCode 10000。
// tag 即版本，发布时只需 git tag vX.Y.Z && git push --tags。
// 非 git 环境（下载 zip、CI 浅克隆没拿 tag）回退 1.0.0。
val tagVersion: Pair<String, Int> = run {
    val tag = try {
        val p = ProcessBuilder("git", "describe", "--tags", "--abbrev=0", "--match", "v*")
            .directory(rootDir).start()
            .inputStream.bufferedReader().readText().trim()
        p
    } catch (t: Throwable) { "" }
    val m = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").find(tag)
    if (m != null) {
        val (a, b, c) = m.destructured
        "$a.$b.$c" to (a.toInt() * 10000 + b.toInt() * 100 + c.toInt())
    } else "1.0.0" to 1
}

android {
    namespace = "com.example.livenotify"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.livenotify"
        minSdk = 26
        targetSdk = 35
        versionCode = tagVersion.second
        versionName = tagVersion.first

        // 用 org.json（Android 自带），不额外引入 JSON 库，减少依赖冲突
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 资源收缩：APK 8.2MB → 约 5MB。项目无反射依赖
            // （Room/OkHttp/Coil/Compose 均自带 consumer rules），开箱即用。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 需要在 debug 构建中区分调试入口（BuildConfig.DEBUG）
        buildConfig = true
    }

    composeOptions {
        // 与 Kotlin 1.9.25 配套
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose（BOM 统一版本）
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 设置项持久化
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.1")

    // 头像加载
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
}
