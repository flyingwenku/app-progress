// ===== 应用层 =====
// 约定：不得在此文件写入流水线 / 签名 / 环境相关逻辑，
//      保证 app/ 模块可被整体替换而流水线无需改动。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fred.progress"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fred.progress"
        minSdk = 21          // 面向旧手机，门槛放低
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"

        resourceConfigurations.add("zh")
    }

    buildTypes {
        getByName("debug") {
            // debug 关闭 R8 与资源缩减（构建中最耗时的一步）
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 零第三方依赖：普通 App 用系统控件 + HttpURLConnection + org.json，
    // 只用 androidx.core-ktx + JUnit 单测（本地不一定装 SDK，合并规则靠 CI 把关）。
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
