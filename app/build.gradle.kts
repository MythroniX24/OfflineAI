import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ── Clone llama.cpp before CMake runs (guaranteed) ────────────────────────────
// This runs inside Gradle itself, so CMake always finds llama.h
val cloneLlama = tasks.register<Exec>("cloneLlama") {
    val target = File(projectDir, "src/main/cpp/llama.cpp")
    onlyIf {
        val hasLlama = File(target, "llama.h").exists()
        if (hasLlama) logger.lifecycle("llama.cpp already present, skipping clone")
        else logger.lifecycle("llama.cpp not found — cloning now...")
        !hasLlama
    }
    commandLine(
        "git", "clone", "--depth", "1",
        "https://github.com/ggerganov/llama.cpp.git",
        target.absolutePath
    )
}

android {
    namespace = "com.om.offlineai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.om.offlineai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Lock NDK version so Gradle doesn't auto-download a different one
        ndkVersion = "26.3.11579264"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3 -ffast-math"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLLAMA_ANDROID=ON"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Suppress coroutines preview/experimental warnings
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ── Wire cloneLlama to run before ANY CMake/native task ─────────────────────
afterEvaluate {
    // Hook into preBuild so llama.cpp is always present before compilation
    tasks.named("preBuild").configure {
        dependsOn(cloneLlama)
    }
    // Also hook all configureCMake tasks explicitly
    tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
        dependsOn(cloneLlama)
    }
    tasks.matching { it.name.startsWith("generateJsonModel") }.configureEach {
        dependsOn(cloneLlama)
    }
    tasks.matching { it.name.startsWith("buildCMake") }.configureEach {
        dependsOn(cloneLlama)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.work.runtime)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Markdown rendering
    implementation("com.github.jeziellago:compose-markdown:0.5.0")

    // Gson
    implementation(libs.gson)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
