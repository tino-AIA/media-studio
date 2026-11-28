plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mediastudio"
    compileSdk = 34

    val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 260
    val appVersionCode = (runNumber - 259).coerceAtLeast(1)

    defaultConfig {
        applicationId = "com.example.mediastudio"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val ksPath = System.getenv("KEYSTORE_PATH")
    val hasPersistentKey = !ksPath.isNullOrBlank() && file(ksPath).exists()

    if (hasPersistentKey) {
        signingConfigs {
            create("persistent") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasPersistentKey) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasPersistentKey) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Android Core & Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Mreža (Retrofit & Moshi)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")

    // Slike (Coil)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Video Player (Media3 / ExoPlayer + FFmpeg audio dekoder za AC3/EAC3/DTS u MKV-u)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1")
}
