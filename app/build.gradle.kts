import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voltcore.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voltcore.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    androidResources {
        localeFilters += listOf("en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Lets you install a release build without configuring a keystore first.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // IUserService.aidl: the shell-UID helper Shizuku launches on our behalf.
        aidl = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.version",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

/**
 * Puts the built APK somewhere findable under a human name.
 *
 * AGP buries output at app/build/outputs/apk/<type>/app-<type>.apk, which is neither easy
 * to find nor obvious what it is once it lands in a Downloads folder.
 *
 *   ./gradlew :app:voltcoreApk          -> dist/VoltCore.apk
 *   ./gradlew :app:voltcoreApkRelease   -> dist/VoltCore-release.apk
 */
val distDir = rootProject.layout.projectDirectory.dir("dist")

tasks.register<Copy>("voltcoreApk") {
    group = "distribution"
    description = "Builds the debug APK and copies it to dist/VoltCore.apk"
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("app-debug.apk") }
    into(distDir)
    rename { "VoltCore.apk" }
}

tasks.register<Copy>("voltcoreApkRelease") {
    group = "distribution"
    description = "Builds the release APK and copies it to dist/VoltCore-release.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("app-release.apk") }
    into(distDir)
    rename { "VoltCore-release.apk" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.graphics)
    implementation(libs.androidx.compose.tooling.prv)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.m3)

    // Privileged access without root. `provider` must be on the runtime classpath;
    // `api` is compile-time + runtime.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)
}
