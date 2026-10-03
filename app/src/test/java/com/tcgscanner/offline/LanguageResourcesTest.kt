package com.tcgscanner.offline

import com.tcgscanner.offline.core.AppLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every language the picker offers must be fully translated, declared in locales_config and kept in sync with the base file. */
class LanguageResourcesTest {
    private val res: File = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private fun keys(file: File, tag: String): Set<String> =
        Regex("<$tag name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun placeholders(file: File): Map<String, List<String>> =
        Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).associate { m ->
            m.groupValues[1] to Regex("%\\d\\$[sd]|%d").findAll(m.groupValues[2]).map { it.value }.toList().sorted()
        }

    @Test fun nineLanguagesAreOffered() {
        assertEquals(listOf("es", "en", "fr", "de", "pt", "zh-CN", "ja", "ru", "hi"), AppLanguages.all.map { it.tag })
    }

    @Test fun everyLanguageHasACompleteStringsFile() {
        val base = File(res, "values/strings.xml")
        val baseStrings = keys(base, "string"); val basePlurals = keys(base, "plurals")
        val basePh = placeholders(base)
        AppLanguages.all.filter { it.code != "en" }.forEach { lang ->
            val f = File(res, "values-${lang.code}/strings.xml")
            assertTrue("missing ${f.path}", f.isFile)
            assertEquals("string keys of ${lang.code}", baseStrings, keys(f, "string"))
            assertEquals("plural keys of ${lang.code}", basePlurals, keys(f, "plurals"))
            val ph = placeholders(f)
            baseStrings.filter { it != "app_name" }.forEach { k -> assertEquals("${lang.code}/$k placeholders", basePh[k], ph[k]) }
        }
    }

    @Test fun localesConfigListsEveryLanguage() {
        val text = File(res, "xml/locales_config.xml").readText()
        AppLanguages.all.forEach { assertTrue("${it.tag} not in locales_config", text.contains("android:name=\"${it.tag}\"")) }
    }

    @Test fun localeCodesMapToTheirLanguage() {
        assertEquals("zh-CN", AppLanguages.matching("zh")?.tag)
        assertEquals("हिन्दी", AppLanguages.matching("HI")?.nativeName)
        assertNotNull(AppLanguages.matching("es"))
        assertNull(AppLanguages.matching("it"))
        assertNull(AppLanguages.matching(null))
    }

    @Test fun testAdIdsAreInPlaceAndNoRealOnes() {
        val manifest = File(res, "../AndroidManifest.xml").readText()
        assertTrue(manifest.contains("ca-app-pub-3940256099942544~3347511713"))
        assertTrue(manifest.contains("com.google.android.gms.ads.APPLICATION_ID"))
        val gradle = File(res, "../../../build.gradle.kts").readText()
        assertTrue(gradle.contains("ca-app-pub-3940256099942544/6300978111"))
    }
}
