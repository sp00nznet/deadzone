plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.sp00nz.deadzone"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.sp00nz.deadzone"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        // ponytail: R8 off — turn it on (with a media3 keep rule) when APK size actually matters
        release { isMinifyEnabled = false }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST}" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Playback that survives the screen going off, plus lock-screen and bluetooth
    // controls, is a foreground media service. There is no lighter way to get that.
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")

    // Downloads only run when the OS says the network is the kind you asked for.
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Feeds redirect through tracking prefixes (podtrac, chartable) and cross
    // http->https on the way. HttpURLConnection silently drops those; OkHttp doesn't.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Art, from a URL, in a list, without writing a bitmap cache.
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation(kotlin("test"))
}
