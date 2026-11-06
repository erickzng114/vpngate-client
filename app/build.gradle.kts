plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.vpngate.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.vpngate.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // آدرس کش لیست سرورها روی GitHub (توسط workflow ساخت پر می‌شود)
        val rawUrl = (project.findProperty("CACHE_RAW_URL") as String?) ?: ""
        val cdnUrl = (project.findProperty("CACHE_CDN_URL") as String?) ?: ""
        buildConfigField("String", "CACHE_RAW_URL", "\"$rawUrl\"")
        buildConfigField("String", "CACHE_CDN_URL", "\"$cdnUrl\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
