import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val keystoreProps = Properties()
rootProject.file("keystore.properties").let { ks ->
    if (ks.canRead())
        keystoreProps.load(FileInputStream(ks))
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity)
            implementation(libs.androidx.annotation)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.constraintlayout)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.databinding.runtime)
            implementation(libs.androidx.fragment)
            implementation(libs.androidx.lifecycle.livedata.ktx)
            implementation(libs.androidx.lifecycle.service)
            implementation(libs.androidx.lifecycle.viewmodel.ktx)
            implementation(libs.androidx.media3.common.ktx)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.navigation.fragment.ktx)
            implementation(libs.androidx.navigation.ui.ktx)
            implementation(libs.androidx.preference.ktx)
            implementation(libs.androidx.recyclerview)
            implementation(libs.androidx.room.ktx)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.startup.runtime)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.bumptech.glide.glide)
            implementation(libs.bumptech.glide.recyclerview)
            implementation(libs.google.material)
            implementation(libs.ktor.client.android)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.logging)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.guava)
            implementation(libs.kotlinx.serialization.json)
        }
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
}

android {
    namespace = "net.joshe.pandplay"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        dataBinding = true
        viewBinding = true
    }
    defaultConfig {
        applicationId = "net.joshe.pandplay"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.0.2"
        base.archivesName = "pandplay"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        register("config") {
            storeFile = keystoreProps.getProperty("storeFile")?.let {file(it)}
            storePassword = keystoreProps.getProperty("storePassword")
            keyPassword = keystoreProps.getProperty("keyPassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
        }
    }
    buildTypes {
        getByName("debug") {
            if (signingConfigs["config"].storeFile != null)
                signingConfig = signingConfigs["config"]
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
            if (signingConfigs["config"].storeFile != null)
                signingConfig = signingConfigs["config"]
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    dependencies {
        annotationProcessor(libs.androidx.room.compiler)
        add("kspAndroid", libs.androidx.room.compiler)
        annotationProcessor(libs.bumptech.glide.compiler)
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
