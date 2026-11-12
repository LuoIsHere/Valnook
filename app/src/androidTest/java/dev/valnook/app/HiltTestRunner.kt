package dev.valnook.app
import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class HiltTestRunner:AndroidJUnitRunner() {
    override fun newApplication(cl:ClassLoader?,className:String?,context:Context?):Application =
        super.newApplication(cl,HiltTestApplication::class.java.name,context)

    /**
     * Tests find controls by their Chinese text, so the app runs in Simplified Chinese whatever the device language.
     * Tests that check another language switch it themselves and put it back.
     */
    override fun onStart() {
        val target = targetContext
        val application = target.applicationContext
        val resources = listOf(target.resources, application.resources).distinct()
        val arrived = CountDownLatch(1)
        // Strings that tests read through the target context do not follow the app language, so its resources are
        // switched directly, and again after each configuration change, which puts the device language back.
        application.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                if (newConfig.locales[0].language == "zh") arrived.countDown()
                resources.forEach(::useChinese)
            }
            @Deprecated("Deprecated in Java") override fun onLowMemory() = Unit
        })
        // Activities follow the app language. The platform applies a change asynchronously; the first test waits for
        // it, or the change would recreate that test's Activity part way through.
        val locales = target.getSystemService(LocaleManager::class.java)
        if (locales.applicationLocales != CHINESE) {
            locales.applicationLocales = CHINESE
            arrived.await(10, TimeUnit.SECONDS)
        }
        resources.forEach(::useChinese)
        super.onStart()
    }

    private fun useChinese(resources: Resources) {
        if (resources.configuration.locales == CHINESE) return
        val configuration = Configuration(resources.configuration).apply { setLocales(CHINESE) }
        @Suppress("DEPRECATION") resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private companion object {
        val CHINESE: LocaleList = LocaleList.forLanguageTags("zh-Hans")
    }
}
