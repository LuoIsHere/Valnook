package dev.valnook.data

/**
 * A test that does nothing unless its run asks for it with an instrumentation argument (a real service, a measurement,
 * a generator). Gradle's device runs leave these out through the `notAnnotation` runner argument set in this module's
 * build file, since Gradle reports their skipped assumption as a failure; `am instrument` still runs them on request.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class OptInTest
