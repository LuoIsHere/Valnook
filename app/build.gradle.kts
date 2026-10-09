import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// Public application registration values; no client secret belongs in an Android client.
val microsoftClientId = providers.gradleProperty("valnook.microsoft.clientId")
    .orElse("397cfb5c-e171-4b47-a3a2-fc0f6513a447")
val microsoftReleaseHash = providers.gradleProperty("valnook.microsoft.releaseSignatureHash").orElse("")

android {
    namespace = "dev.valnook.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.valnook.app"
        minSdk = 36
        targetSdk = 36
        versionCode = releaseVersion.getProperty("version.code").toInt()
        versionName = releaseVersion.getProperty("version.name")
        buildConfigField("String", "INTERNAL_BUILD_ID", "\"${releaseVersion.getProperty("build.id")}\"")

        buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"${microsoftClientId.get()}\"")
        testInstrumentationRunner = "dev.valnook.app.HiltTestRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "MICROSOFT_SIGNATURE_HASH", "\"K4M9AB4sa40+0618D66aZRIAYZU=\"")
            manifestPlaceholders["msalSignaturePath"] = "/K4M9AB4sa40+0618D66aZRIAYZU="
        }
        release {
            buildConfigField("String", "MICROSOFT_SIGNATURE_HASH", "\"${microsoftReleaseHash.get()}\"")
            manifestPlaceholders["msalSignaturePath"] = "/${microsoftReleaseHash.get().ifBlank { "not-configured" }}"
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:wallet"))
    implementation(project(":feature:cash"))
    implementation(project(":feature:deposits"))
    implementation(project(":feature:investments"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:statistics"))
    implementation(project(":feature:backup"))
    implementation(project(":feature:webadmin"))
    implementation(libs.androidx.viewmodel.navigation3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.serialization.core)
    implementation(libs.serialization.json)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.auth) // Legacy adapter retained, not injected.
    implementation(libs.msal)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.hilt.testing)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.coroutines.test)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
