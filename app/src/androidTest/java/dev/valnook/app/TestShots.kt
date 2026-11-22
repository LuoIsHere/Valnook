package dev.valnook.app

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Screenshots are for looking at by hand, not for any check: they are written only when the run asks for them with
 * `-e shots true`, so an ordinary run skips the capture and the file.
 */
object TestShots {
    val enabled: Boolean get() = InstrumentationRegistry.getArguments().getString("shots") == "true"
}
