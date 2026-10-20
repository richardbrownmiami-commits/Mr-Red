plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.redmi9.offlineai"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.redmi9.offlineai"
        minSdk = 23
        targetSdk = 30
        versionCode = 1
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-23",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // This branch targets Android 11/API 30 for sideloaded ARMv7 devices.
    // Current Play policy rejects old target SDKs; keep the requested runtime target
    // and suppress only this policy lint while retaining all other lint checks.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
