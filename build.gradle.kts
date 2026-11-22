// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

// ---------------------------------------------------------------------------------------------------------------------
// One definition of what gets checked, used the same way on a developer's machine and in CI (.github/workflows/ci.yml).
// The modules are found here rather than listed, so a new module is checked without touching anything else.
//
//   ./gradlew quickCheck    builds every module, runs every JVM unit test, compiles the device tests without running
//                           them, and syntax-checks the web admin's scripts. Needs no device.
//   ./gradlew deviceTests   runs the device tests of every module that has them, on the one device ANDROID_SERIAL
//                           names. Instrumentation switches pass through, e.g.
//                           -Pandroid.testInstrumentationRunnerArguments.shots=true
// ---------------------------------------------------------------------------------------------------------------------

/** Parses each script with Node without running it; Node is required in CI and only recommended locally. */
abstract class CheckWebAdminScripts : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val scripts: ConfigurableFileCollection
    @get:Input abstract val nodeRequired: Property<Boolean>
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction fun check() {
        val node = runCatching {
            exec.exec { commandLine("node", "--version"); isIgnoreExitValue = true; standardOutput = java.io.OutputStream.nullOutputStream() }.exitValue == 0
        }.getOrDefault(false)
        if (!node) {
            if (nodeRequired.get()) throw GradleException("Node is needed to check the web admin scripts")
            logger.warn("Node not found: web admin scripts were not checked")
            report.get().asFile.writeText("skipped: node not found\n")
            return
        }
        val files = scripts.files.sortedBy { it.name }
        files.forEach { file -> exec.exec { commandLine("node", "--check", file.absolutePath) } }
        report.get().asFile.writeText(files.joinToString("\n", postfix = "\n") { it.name })
    }
}

val checkWebAdminScripts = tasks.register<CheckWebAdminScripts>("checkWebAdminScripts") {
    group = "verification"
    description = "Syntax-checks the web admin's JavaScript with Node."
    scripts.from(fileTree("app/src/main/assets/webadmin") { include("**/*.js") })
    nodeRequired.set(providers.environmentVariable("CI").map { it == "true" }.orElse(false))
    report.set(layout.buildDirectory.file("reports/webadmin-scripts.txt"))
}

val quickCheck = tasks.register("quickCheck") {
    group = "verification"
    description = "Build, JVM unit tests, device-test compilation and web admin script check; what CI runs."
    dependsOn(checkWebAdminScripts)
}
/**
 * Every device-test run, through deviceTests or a module's own connected task, first needs ANDROID_SERIAL: those tasks
 * install and then uninstall the app on every attached device, so a phone plugged in next to the emulator must never
 * be one of them.
 */
val checkDeviceSerial = tasks.register("checkDeviceSerial") {
    val serial = providers.environmentVariable("ANDROID_SERIAL")
    doLast {
        if (serial.orNull.isNullOrBlank()) throw GradleException("Set ANDROID_SERIAL to the emulator to test on, e.g. emulator-5556")
    }
}
val deviceTests = tasks.register("deviceTests") {
    group = "verification"
    description = "Device tests on the device ANDROID_SERIAL names (refuses to run without it)."
}

subprojects {
    val project = this
    val hasDeviceTests = file("src/androidTest").isDirectory
    plugins.withId("com.android.application") { wire(project, android = true, hasDeviceTests) }
    plugins.withId("com.android.library") { wire(project, android = true, hasDeviceTests) }
    plugins.withId("org.jetbrains.kotlin.jvm") { wire(project, android = false, hasDeviceTests = false) }
}

fun wire(project: Project, android: Boolean, hasDeviceTests: Boolean) {
    val path = project.path
    quickCheck.configure {
        if (android) {
            dependsOn("$path:assembleDebug", "$path:testDebugUnitTest")
            if (hasDeviceTests) dependsOn("$path:compileDebugAndroidTestKotlin")
        } else dependsOn("$path:assemble", "$path:test")
    }
    if (hasDeviceTests) deviceTests.configure { dependsOn("$path:connectedDebugAndroidTest") }
    if (android) project.tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }
        .configureEach { dependsOn(checkDeviceSerial) }
}
