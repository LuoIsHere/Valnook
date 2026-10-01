plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.valnook.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.valnook.app"
        minSdk = 36
        targetSdk = 37
        versionCode = 2
        versionName = "0.0.2"

        testInstrumentationRunner = "dev.valnook.app.HiltTestRunner"
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
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
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:cash"))
    implementation(project(":feature:deposits"))
    implementation(project(":feature:investments"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.viewmodel.navigation3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.serialization.core)
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
