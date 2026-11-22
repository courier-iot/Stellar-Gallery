plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Bump this for every release. The workflow reads it to tag the GitHub release (vX.Y.Z).
val appVersion = "2.1.0"

val versionParts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
val computedVersionCode = versionParts.getOrElse(0) { 0 } * 10000 +
    versionParts.getOrElse(1) { 0 } * 100 +
    versionParts.getOrElse(2) { 0 }

val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE")

android {
    namespace = "com.courieriot.stellargallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.courieriot.stellargallery"
        minSdk = 30
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = appVersion
        // Photo sorter runtime: phones only (keeps the APK smaller)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    androidResources {
        noCompress += "onnx"
    }

    signingConfigs {
        create("release") {
            if (!signingKeystore.isNullOrBlank()) {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (!signingKeystore.isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Open-source image loading + video playback (no Google services)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")

    // v2.0.0
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.osmdroid:osmdroid-android:6.1.20")       // OpenStreetMap (no Google Maps)
    implementation("com.google.zxing:core:3.5.3")               // QR codes (open-source library, no services)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")        // WebDAV backup + model download

    // v2.1.0 photo sorter: on-device AI (open source, runs offline)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
