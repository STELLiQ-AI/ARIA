/**
 * app/build.gradle.kts
 *
 * ARIA Demo APK module build configuration. Configures NDK for whisper.cpp and llama.cpp
 * native compilation, and sets up proper asset handling for ML models.
 *
 * CRITICAL: noCompress must include "gguf", "bin", "onnx" — DEFLATE compression corrupts
 * binary model files silently. Verify with: unzip -v app-debug.apk | grep ggml
 * Expected: "Stored" not "Deflated"
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.stelliq.aria"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stelliq.aria"
        minSdk = 29       // Android 10 — ONNX Runtime minimum
        targetSdk = 34    // Android 14 — required for FOREGROUND_SERVICE_MICROPHONE
        versionCode = 1
        versionName = "0.1.0-demo"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // WHY: arm64-v8a ONLY — we target S24 Ultra specifically
        // No x86_64 (emulator only), no armeabi-v7a (legacy 32-bit)
        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        // Native build configuration for whisper.cpp and llama.cpp
        externalNativeBuild {
            cmake {
                // C++17 for llama.cpp compatibility
                cppFlags += listOf(
                    "-std=c++17",
                    "-O2",
                    "-DANDROID",
                    "-DNDEBUG",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden"
                )

                // CMake arguments for native build
                arguments += listOf(
                    "-DANDROID_ABI=arm64-v8a",
                    "-DANDROID_PLATFORM=android-29",
                    "-DANDROID_STL=c++_shared",
                    // WHY: Do NOT set GGML_OPENCL=ON here — it bleeds into whisper's
                    // ggml FetchContent build, causing 8+ seconds of wasted SPIR-V
                    // kernel compilation for a backend whisper never uses.
                    // Llama.cpp uses prebuilt .so libraries that already have OpenCL
                    // compiled in, so this flag is not needed at the gradle level.
                    // Disable x86 SIMD flags — we're ARM only
                    "-DWHISPER_NO_AVX=ON",
                    "-DWHISPER_NO_AVX2=ON",
                    "-DWHISPER_NO_F16C=ON",
                    "-DWHISPER_NO_FMA=ON",
                    // Build whisper.cpp as shared library
                    "-DBUILD_SHARED_LIBS=ON",
                    // Disable examples and tests for faster build
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF"
                )
            }
        }
    }

    // CMake configuration path
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    /*
     * CRITICAL: Prevent AAPT from compressing binary model files.
     *
     * Android's build system applies DEFLATE compression to assets by default.
     * GGUF/GGML model files are binary blobs that CANNOT be compressed — doing so
     * corrupts the file structure and causes silent model load failures.
     *
     * This config tells AAPT to store these files uncompressed (Stored, not Deflated).
     *
     * Validation:
     *   unzip -v app/build/outputs/apk/debug/app-debug.apk | grep ggml
     *   Should show: 0% Stored (not Deflated)
     */
    androidResources {
        noCompress += listOf(
            "gguf",   // GGUF model format (whisper, llama)
            "bin",    // Binary model files
            "onnx",   // ONNX models (Silero VAD)
            "tflite"  // TFLite models (future-proofing)
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // PHASE 2: Enable ProGuard for production
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            // WHY: Enable native debugging for JNI development
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Packaging options for native libraries
    packaging {
        jniLibs {
            // WHY: Keep all native libs uncompressed for faster load time
            useLegacyPackaging = true
            // WHY: Exclude the OpenCL link-time stub from APK packaging.
            // This stub is only used by the NDK linker to resolve OpenCL symbols
            // in libasr_jni.so. At runtime, Android's dynamic linker resolves
            // libOpenCL.so from the Adreno vendor path (/system/vendor/lib64/).
            // Packaging the vendor stub causes "libc++.so not found" errors because
            // the vendor .so has system dependencies not available in the app namespace.
            excludes += setOf("lib/arm64-v8a/libOpenCL.so")
        }
        resources {
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    // Lint configuration
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // WHY: Return default values for unmocked Android methods in unit tests
    // Without this, android.util.Log calls will throw RuntimeException
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)

    // AndroidX Lifecycle (LiveData, ViewModel)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime)

    // AndroidX Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Room Database
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // ONNX Runtime for Silero VAD
    implementation(libs.onnxruntime.android)

    // SplashScreen API (Android 12+ compatibility)
    implementation(libs.splashscreen)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
