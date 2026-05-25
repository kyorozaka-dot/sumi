plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

android {
    namespace = "com.ogawa.sumi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ogawa.sumi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // AppCompat（manifest テーマ参照に必要）
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Lifecycle（IME の ComposeView に必須）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON (AI候補のパース)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTTP (AI API呼び出し)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // DataStore (設定永続化)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // EncryptedSharedPreferences (APIキー安全保存)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}