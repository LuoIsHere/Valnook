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

/**
 * The version code follows from the version name and the upload number for that version: major × 1,000,000 +
 * minor × 10,000 + patch × 100 + build, so 0.0.18 build 1 is 1801 and 0.1.0 build 1 is 10001. Minor, patch and
 * build stay within 0-99 (build 1-99) and major within 0-2099, so the code rises with every version and upload and
 * stays under Play's limit; anything outside fails the build. Backups record it and a build refuses one made by a
 * larger code, so a release is the last build tested, never an earlier one. The rules for editing are in
 * version.properties.
 */
val releaseVersionCode: Int = run {
    val name = releaseVersion.getProperty("version.name")
    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(name)?.groupValues?.drop(1)?.map(String::toInt)
        ?: error("version.name must be major.minor.patch: $name")
    val build = releaseVersion.getProperty("version.build")?.toIntOrNull()
        ?: error("version.build must be a number from 1 to 99")
    val (major, minor, patch) = parts
    require(minor in 0..99 && patch in 0..99) { "minor and patch must stay below 100: $name" }
    require(build in 1..99) { "version.build must be from 1 to 99: $build" }
    require(major in 0..2099) { "major too large for a version code: $name" }
    major * 1_000_000 + minor * 10_000 + patch * 100 + build
}

/**
 * The commit's date and its place among that day's commits, such as 20261108.3. Backups record it and are refused
 * by a build with a smaller one, so it must be the same for every build of one commit and rise with history: the
 * date is the commit's own (in the time zone it was made in), not the day of building. Without git, the value in
 * version.properties. A shallow clone counts only the commits it has, so release builds need the day's history.
 */
val internalBuildId: String = run {
    val dates = runCatching {
        val log = providers.exec {
            commandLine("git", "log", "--format=%cd", "--date=format:%Y%m%d", "HEAD")
            workingDir = rootDir
            isIgnoreExitValue = true
        }
        if (log.result.get().exitValue == 0) log.standardOutput.asText.get().lines().filter { it.isNotBlank() } else null
    }.getOrNull()
    val day = dates?.firstOrNull()
    if (day == null) {
        logger.warn("Valnook: git history unavailable; internal build falls back to version.properties")
        releaseVersion.getProperty("build.id")
    } else {
        "$day.${dates.count { it == day }}"
    }
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
        minSdk = 33
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersion.getProperty("version.name")
        buildConfigField("String", "INTERNAL_BUILD_ID", "\"$internalBuildId\"")

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
    implementation(libs.msal)
    constraints {
        // MSAL brings these in with older versions. Hold the tested ones; no dependency is added.
        implementation(libs.play.services.auth)
        implementation(libs.androidx.loader)
    }
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
