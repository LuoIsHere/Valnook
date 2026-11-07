plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.valnook.feature.settings"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 33; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    buildFeatures { compose = true }
}

/** Copies the repository's LICENSE into raw resources at build time, so the app shows the one file the repository keeps. */
abstract class LicenseResources : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val license: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun copy() {
        val raw = output.get().dir("raw").asFile
        raw.deleteRecursively()
        raw.mkdirs()
        license.get().asFile.copyTo(raw.resolve("license.txt"))
    }
}
val licenseResources = tasks.register<LicenseResources>("licenseResources") {
    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
}
androidComponents {
    onVariants { variant -> variant.sources.res?.addGeneratedSourceDirectory(licenseResources, LicenseResources::output) }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
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


