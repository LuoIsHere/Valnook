package dev.valnook.designsystem

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every module's English and Chinese text have the same keys, and each string takes the same placeholders in both, so
 * no screen falls back to the other language or formats a value into the wrong slot. Reads the resource files directly.
 */
class StringResourcesTest {
    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfx]""")

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    /** The `res` folder of every module that has text. */
    private fun resourceFolders() = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != "internal" && !it.name.startsWith(".") }
        .filter { it.isDirectory && it.path.replace('\\', '/').endsWith("src/main/res") && File(it, "values").isDirectory }
        .toList()

    /** Key to placeholder set, for strings and for each plural's "other" form; text marked untranslatable is left out. */
    private fun texts(folder: File): Map<String, Set<String>> {
        if (!folder.isDirectory) return emptyMap()
        val out = sortedMapOf<String, Set<String>>()
        folder.listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = document.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val element = strings.item(i) as Element
                if (element.getAttribute("translatable") == "false") continue
                out[element.getAttribute("name")] = placeholders(element.textContent)
            }
            val plurals = document.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val element = plurals.item(i) as Element
                val items = element.getElementsByTagName("item")
                val other = (0 until items.length).map { items.item(it) as Element }.firstOrNull { it.getAttribute("quantity") == "other" }
                out["plurals:" + element.getAttribute("name")] = placeholders(other?.textContent.orEmpty())
            }
        }
        return out
    }

    private fun placeholders(text: String) = placeholder.findAll(text.replace("%%", "")).map { match ->
        // "%s" alone is the first argument; the type letter has to agree as well.
        (match.groupValues[1].ifEmpty { "1$" }) + match.value.last()
    }.toSet()

    @Test fun english_and_chinese_have_the_same_keys_and_placeholders() {
        val folders = resourceFolders()
        assertTrue("no resource folders found under $root", folders.size > 5)
        val problems = folders.flatMap { res ->
            val module = res.parentFile.parentFile.parentFile.relativeTo(root).path
            val english = texts(File(res, "values"))
            val chinese = texts(File(res, "values-b+zh+Hans"))
            (english.keys - chinese.keys).map { "$module: no Chinese for $it" } +
                (chinese.keys - english.keys).map { "$module: no English for $it" } +
                english.keys.intersect(chinese.keys).filter { english[it] != chinese[it] }
                    .map { "$module: $it takes ${english[it]} in English but ${chinese[it]} in Chinese" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
