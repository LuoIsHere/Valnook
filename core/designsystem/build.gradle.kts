plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.valnook.core.designsystem"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 33; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    buildFeatures { compose = true }
}

dependencies {


    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}


// StringResourcesTest reads every module's text, so a change to any of it reruns the unit tests here.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree(rootDir) {
        include("app/src/main/res/values*/*.xml", "core/*/src/main/res/values*/*.xml", "feature/*/src/main/res/values*/*.xml")
    }).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("stringResources")
}
