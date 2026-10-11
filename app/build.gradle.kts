import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing is read from keystore.properties (never committed).
// A fixed keystore matters: with a different signature, Android refuses to
// upgrade in place and the user has to uninstall -> all ledger data is lost.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.xiaozhangben.ledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiaozhangben.ledger"
        minSdk = 26
        targetSdk = 34
        // versionCode must increase with every release that ships: Android refuses to
        // install an APK whose versionCode is not greater than the installed one, and
        // reinstalling over the top is the only update path that keeps the ledger data.
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // No native code is shipped, so a single APK is already universal
        // (arm64-v8a / armeabi-v7a / x86_64 all run the same DEX).
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-Xjvm-default=all")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

// The host denies writes to the real user profile and to the shared %TEMP%. Robolectric
// creates a download lock in `user.home` and extracts the instrumented Android SDK, so
// the forked test JVM needs both redirected into the workspace — the same class of
// problem that breaks the Kotlin compiler and adb on this machine.
//
// Kept under the root `build/` directory on purpose. It started out at the project root
// as `.android-build/`, which meant a single `git add -A` staged 364 MB of Robolectric
// jars — including a 190 MB one that GitHub would reject outright, permanently, since a
// pushed blob stays in the history even after the commit is undone.
val sandboxHome = rootProject.layout.buildDirectory.dir("test-sandbox/home").get().asFile
val sandboxTmp = rootProject.layout.buildDirectory.dir("test-sandbox/tmp").get().asFile

tasks.withType<Test>().configureEach {
    // Robolectric writes its download lock into `user.home`, and fails outright if that
    // directory does not exist — so create it as part of the task.
    doFirst {
        sandboxHome.mkdirs()
        sandboxTmp.mkdirs()
    }
    systemProperty("user.home", sandboxHome.absolutePath)
    systemProperty("java.io.tmpdir", sandboxTmp.absolutePath)
    maxHeapSize = "2g"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.androidx.room.testing)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    // Room can only be exercised against a real SQLite, and the bugs worth catching here
    // are exactly the ones a hand-written SQL statement can hide (a missing NOT NULL
    // column, a wrong ON CONFLICT clause). Robolectric runs that in the JVM.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
