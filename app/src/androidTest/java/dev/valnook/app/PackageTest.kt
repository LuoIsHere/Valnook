package dev.valnook.app
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertEquals
class PackageTest {
    @Test fun debug_package_is_isolated() {
        assertEquals("dev.valnook.app.debug",InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }
}
