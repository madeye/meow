package io.github.madeye.meow

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards against translation drift.
 *
 * `stringResource` silently falls back to the default locale, so a key that
 * exists only in `values/` looks fine in development and quietly ships English
 * text to Chinese users. meow-ios has the equivalent check
 * (`LocalizableParityTests`); this is the Android half.
 *
 * Plain JVM test — no Robolectric, no device. Gradle runs JVM tests with the
 * module directory as the working directory.
 */
class StringsParityTest {

    private val english = parse("src/main/res/values/strings.xml")
    private val chinese = parse("src/main/res/values-zh-rCN/strings.xml")

    @Test
    fun `both locales define the same string keys`() {
        assertEquals(
            "string keys missing from values-zh-rCN",
            emptySet<String>(),
            english.strings.keys - chinese.strings.keys,
        )
        assertEquals(
            "string keys in values-zh-rCN with no English original",
            emptySet<String>(),
            chinese.strings.keys - english.strings.keys,
        )
    }

    @Test
    fun `both locales define the same plurals`() {
        assertEquals(
            "plurals missing from values-zh-rCN",
            emptySet<String>(),
            english.plurals - chinese.plurals,
        )
        assertEquals(
            "plurals in values-zh-rCN with no English original",
            emptySet<String>(),
            chinese.plurals - english.plurals,
        )
    }

    @Test
    fun `no translated value is blank`() {
        val blank = chinese.strings.filterValues { it.isBlank() }.keys
        assertEquals("blank Chinese translations", emptySet<String>(), blank)
    }

    @Test
    fun `format specifiers match across locales`() {
        val mismatched = english.strings.keys.filter { key ->
            specifiers(english.strings.getValue(key)) != specifiers(chinese.strings.getValue(key))
        }
        assertEquals(
            "these keys have different format arguments in en vs zh-rCN",
            emptyList<String>(),
            mismatched,
        )
    }

    @Test
    fun `multi-argument strings use positional specifiers`() {
        // Bare %s cannot be reordered by a translator, and Android throws if a
        // resource mixes positional and non-positional forms.
        val offenders = (english.strings + chinese.strings).filter { (_, value) ->
            val bare = Regex("%[sd]").findAll(value).count()
            bare > 0 && specifiers(value).isNotEmpty()
        }.keys
        assertTrue("mixed positional and bare specifiers in $offenders", offenders.isEmpty())
    }

    private fun specifiers(value: String): Set<String> =
        Regex("%(\\d+)\\$[sd]").findAll(value).map { it.value }.toSet()

    private fun parse(path: String): Resources {
        val file = File(path)
        assertTrue("missing resource file: ${file.absolutePath}", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        val strings = document.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as Element
                element.getAttribute("name") to element.textContent
            }
        }
        val plurals = document.getElementsByTagName("plurals").let { nodes ->
            (0 until nodes.length).map { index ->
                (nodes.item(index) as Element).getAttribute("name")
            }.toSet()
        }
        return Resources(strings, plurals)
    }

    private data class Resources(val strings: Map<String, String>, val plurals: Set<String>)
}
