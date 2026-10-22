import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing credentials live in keystore.properties, committed alongside
// the keystore (this is a sideload app, not a Play release). Absent the file,
// release builds are produced unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// ABI splits are only worth the extra build time for release artifacts.
val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

android {
    namespace = "com.changyow.mediadler"
    compileSdk = 35
    ndkVersion = "23.2.8568313"

    defaultConfig {
        applicationId = "com.changyow.mediadler"
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 15
        versionName = "0.1.14"

        // yt-dlp / ffmpeg ship native libraries for these ABIs.
        // TEMP (Phase 1): arm64-v8a (devices) + x86_64 (emulator testing) only, to keep the
        // whisper.cpp native build fast during iteration.
        // TODO: restore listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") before release.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The debug variant would otherwise compile whisper.cpp/ggml at -O0, which makes
            // inference ~10-50x slower — unusable for on-device testing and meaningless for timing.
            // Force an optimized native build so debug speed reflects what release ships.
            // AGP forces CMAKE_BUILD_TYPE=Debug here, so override the Debug-config opt flags instead.
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_C_FLAGS_DEBUG=-O3 -DNDEBUG",
                        "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -DNDEBUG",
                    )
                }
            }
        }
        release {
            // R8 is disabled for now: the bundled yt-dlp/ffmpeg engine must load
            // reliably, and minification is a release-only variable we can't
            // runtime-verify in this environment. ABI splits already give the
            // meaningful size reduction; re-enable R8 once the engine is verified.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // One APK per ABI (plus a universal fallback) so each download is ~1/4 the
    // size of the all-ABI universal APK. Native Python/ffmpeg dominates size,
    // so this matters far more than R8 here.
    splits {
        abi {
            isEnable = isReleaseTask
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        // jUPnP uses Java 9 collection factories; keep sharing compatible with Android 9.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    // Bundled model: ad analysis is entirely on-device, including first use.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation(libs.opencc4j)
    // Extracts the sherpa-onnx model .tar.bz2 archives on-device (bzip2 + tar, pure Java).
    implementation(libs.commons.compress)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.apache.sshd:sshd-sftp:2.20.0")
}

// Host experiments and Android execute the same extractor and frozen scorer.
val weightedAdAssets = tasks.register<Sync>("weightedAdAssets") {
    from(rootProject.file("scripts/probe-hls-ad-features.py")) { rename { "probe_hls_ad_features.py" } }
    from(rootProject.file("scripts/score-hls-ad-features.py")) { rename { "score_hls_ad_features.py" } }
    into(layout.buildDirectory.dir("generated/weightedAdAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(weightedAdAssets.map { it.destinationDir })
tasks.named("preBuild").configure { dependsOn(weightedAdAssets) }
