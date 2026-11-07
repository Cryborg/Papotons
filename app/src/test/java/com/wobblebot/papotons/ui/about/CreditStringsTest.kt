package com.wobblebot.papotons.ui.about

import com.wobblebot.papotons.resourceStrings
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/** Invariant 5 : le crédit ARASAAC complet, dans chaque langue de l'appli. */
class CreditStringsTest {
    private val requiredParts = listOf("Sergio Palao", "ARASAAC", "CC BY-NC-SA", "Aragon")

    @Test
    fun `the ARASAAC credit names author, origin, license and owner in every language`() {
        // Toute langue ajoutée à l'appli est vérifiée d'office.
        val languages = File("src/main/res").listFiles().orEmpty()
            .filter { it.name.startsWith("values") && File(it, "strings_about.xml").isFile }
            .map { it.name }
        assertTrue("values" in languages)
        for (values in languages) {
            val credit = checkNotNull(resourceStrings(values, "strings_about.xml")["about_arasaac_credit"]) { "$values: no credit" }
            for (part in requiredParts) {
                assertTrue("$values: « $part » missing from the credit", part in credit)
            }
        }
    }
}
