plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.virtucam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.virtucam"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            // ULTRA SIZE REDUCTION: Only include 64-bit ARM (Standard for modern Xiaomi/Android 15)
            // This removes another 15MB+ compared to keeping armeabi-v7a
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
        release {
            isMinifyEnabled = true 
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
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
        viewBinding = true
    }

    packaging {
        jniLibs {
            // ULTRA STRIP: Force exclusion of everything except ARM64
            // This turns the 60MB "Full" library into a ~5MB functional core for modern phones
            excludes.add("lib/x86/**")
            excludes.add("lib/x86_64/**")
            excludes.add("lib/armeabi-v7a/**")
            excludes.add("**/lib/x86/**")
            excludes.add("**/lib/x86_64/**")
            excludes.add("**/lib/armeabi-v7a/**")
        }
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // Xposed API
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
    
    // Image/Video loading
    implementation("io.coil-kt:coil:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    
    // ExoPlayer for Live RTSP/RTMP Streaming from OBS
    val media3_version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3_version")
    implementation("androidx.media3:media3-ui:$media3_version")
    
    // OkHttp DataSource for better stream reliability
    implementation("androidx.media3:media3-datasource-okhttp:$media3_version")
    
    // Local Ultra-lightweight FFmpeg (only ~5MB) for robust RTSP packet parsing
    implementation(files("libs/ffmpeg-kit.aar"))
}
