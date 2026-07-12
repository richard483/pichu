plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nephren.pichu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nephren.pichu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a = the Galaxy A56 (real device). x86_64 = Android Studio
        // emulator on a PC. Both ABIs are vendored under src/main/jniLibs so the
        // app runs in either place. (Drop x86_64 for a release build to the A56
        // if you want a smaller APK.)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Landmine #2: ONNX Runtime memory-maps model files and reads word-aligned
    // values. If a .onnx is stored *compressed* it crashes with SIGBUS. We load
    // models from the filesystem (getExternalFilesDir) in Phase 1, so this is
    // belt-and-suspenders for any future bundling of models in assets.
    androidResources {
        noCompress += "onnx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
