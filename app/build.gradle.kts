plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val appVersionName = "5.4"
val releaseSigningEnvironment = mapOf(
    "MMTV_KEYSTORE_PATH" to System.getenv("MMTV_KEYSTORE_PATH"),
    "MMTV_KEYSTORE_PASSWORD" to System.getenv("MMTV_KEYSTORE_PASSWORD"),
    "MMTV_KEY_ALIAS" to System.getenv("MMTV_KEY_ALIAS"),
    "MMTV_KEY_PASSWORD" to System.getenv("MMTV_KEY_PASSWORD")
)
val missingReleaseSigningVariables = releaseSigningEnvironment
    .filterValues { it.isNullOrBlank() }
    .keys

android {
    namespace = "com.example.mmtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mmtv"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (missingReleaseSigningVariables.isEmpty()) {
                storeFile = file(releaseSigningEnvironment.getValue("MMTV_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnvironment.getValue("MMTV_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("MMTV_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("MMTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Modernare sätt att sätta namnet på den färdiga APK:n (ersätter applicationVariants)
    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("MMTV-v${appVersionName}.apk")
            }
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.configureEach {
    if (name.contains("Release", ignoreCase = true)) {
        doFirst {
            if (missingReleaseSigningVariables.isNotEmpty()) {
                throw GradleException(
                    "Release signing is not configured. Set: ${missingReleaseSigningVariables.joinToString(", ")}."
                )
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
