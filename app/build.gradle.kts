import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 签名配置：密钥与口令都放在 local.properties（该文件不入库）里。
// 没有密钥库时 release 会自动产出未签名包，方便任何人 clone 下来直接编译。
// ---------------------------------------------------------------------------
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeystore = localProperties.getProperty("dougao.keystore")
    ?.let { rootProject.file(it) }
    ?: rootProject.file("keystore/dougao.jks")
val hasReleaseKeystore = releaseKeystore.exists()

android {
    namespace = "com.dougao.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dougao.app"
        // 尽量兼容更多安卓版本：API 24 = Android 7.0
        // （Shizuku 依赖库下限为 24，再低会导致运行期崩溃）
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "1.3.2"

        vectorDrawables {
            useSupportLibrary = true
        }

        // 只保留必要语言，减小包体
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("dougaoRelease") {
            if (hasReleaseKeystore) {
                storeFile = releaseKeystore
                storePassword = localProperties.getProperty("dougao.storePassword")
                keyAlias = localProperties.getProperty("dougao.keyAlias")
                keyPassword = localProperties.getProperty("dougao.keyPassword")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("dougaoRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("dougaoRelease")
            }
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
        aidl = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Security (Encrypted SharedPreferences) - 用于加密保存 API Key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SAF 文件访问（豆糕文件工作区）
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // JSON
    implementation("org.json:json:20231013")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
