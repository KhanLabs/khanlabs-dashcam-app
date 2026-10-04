plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "dev.khanlabs.dashcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.khanlabs.dashcam"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    // Lifecycle-aware flow collection (collectAsStateWithLifecycle) so the
    // LTE status / device stats polling loops (RealDashcamRepository)
    // actually stop while their screens aren't STARTED, instead of just
    // pausing recomposition -- plain collectAsState() keeps the underlying
    // flow (and its 2.5s/3.5s httpd polling) running in the background.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MVVM + DI (Hilt) wired in starting with the Dashboard screen.
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Video playback (Gallery / Player screen)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // GPS Trip Map screen (OpenStreetMap tiles -- the one narrow exception to
    // the zero-cloud rule, see IMPLEMENTATION_NOTES.md; no API key needed)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Real dashcam-hotspot connectivity probe (Dashboard SYNC button)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Real settings persistence (Settings screen survives app restart, A7)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room-backed sync-state tracking (A3): replaces SyncEngine's
    // file-existence-only dedup and feeds the Dashboard's real new-clip/
    // new-track/ready-to-sync counts.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
