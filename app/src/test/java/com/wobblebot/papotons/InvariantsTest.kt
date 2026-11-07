package com.wobblebot.papotons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gardes mécaniques des invariants du CLAUDE.md (voir `docs/regles/INVARIANTS.md`). Les tests
 * tournent depuis le dossier du module : les chemins sont relatifs à `app/`.
 */
class InvariantsTest {
    private val mainSources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    /** Invariant 1 : seul réseau permis, l'API d'ARASAAC (Play passe par sa propre bibliothèque). */
    private val allowedHosts = setOf("api.arasaac.org", "static.arasaac.org")
    private val allowedPermissions = setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")

    /** Ce qui collecte ou coûte : pub, mesure d'audience, rapports de plantage, paiement, compte. */
    private val forbiddenLibraries = listOf(
        "firebase", "crashlytics", "analytics", "play-services-ads", "admob", "billing",
        "sentry", "appcenter", "bugsnag", "facebook", "play-services-auth",
    )

    @Test
    fun `invariant 1 - the manifest asks for no permission beyond the network`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()

        assertTrue("unexpected permissions: ${permissions - allowedPermissions}", (permissions - allowedPermissions).isEmpty())
    }

    @Test
    fun `invariant 1 - no library that collects data or costs money`() {
        // Sans les commentaires : ils rappellent justement ce qu'il ne faut pas réintroduire.
        val catalog = withoutComments(File("../gradle/libs.versions.toml"), "#")
        val buildScript = withoutComments(File("build.gradle.kts"), "//")

        for (library in forbiddenLibraries) {
            assertFalse("$library in the version catalog", library in catalog)
            assertFalse("$library in app/build.gradle.kts", library in buildScript)
        }
    }

    @Test
    fun `invariant 1 - the code reaches no host but ARASAAC`() {
        val hosts = mainSources.flatMap { source ->
            Regex("""https?://([A-Za-z0-9.-]+)""").findAll(source.readText()).map { "${source.name}: ${it.groupValues[1]}" }
        }

        assertEquals(emptyList<String>(), hosts.filterNot { entry -> allowedHosts.any { entry.endsWith(": $it") } })
    }

    @Test
    fun `invariant 4 - no destructive migration anywhere`() {
        // L'appel, pas le mot : la KDoc de la base le nomme pour l'interdire.
        val destructive = mainSources.filter { Regex("""fallbackToDestructiveMigration\w*\s*\(""").containsMatchIn(it.readText()) }.map { it.name }

        assertEquals(emptyList<String>(), destructive)
    }

    @Test
    fun `invariant 4 - every database version has its exported schema`() {
        val database = File("src/main/java/com/wobblebot/papotons/data/PapotonsDatabase.kt").readText()
        val version = checkNotNull(Regex("""version = (\d+)""").find(database)).groupValues[1].toInt()
        val schemas = File("schemas/com.wobblebot.papotons.data.PapotonsDatabase")

        for (each in 1..version) assertTrue("schema $each.json missing", File(schemas, "$each.json").isFile)
    }

    @Test
    fun `invariant 4 - the Android backup excludes nothing`() {
        for (rules in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val xml = File("src/main/res/xml/$rules").readText()
            assertFalse("$rules excludes something", "<exclude" in xml)
        }
    }

    private fun withoutComments(file: File, marker: String): String =
        file.readLines().joinToString("\n") { it.substringBefore(marker) }.lowercase()
}
