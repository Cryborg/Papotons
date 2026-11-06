package com.wobblebot.papotons

import java.io.File

/**
 * Les chaînes d'un fichier de `res/` (values, values-en), telles qu'Android les affichera, pour les tests
 * JVM qui ont besoin du vrai texte (élision, crédit). Les tests tournent depuis le dossier du module.
 */
fun resourceStrings(valuesDirectory: String, fileName: String): Map<String, String> {
    val xml = File("src/main/res/$valuesDirectory/$fileName").readText()
    return Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(xml).associate { match ->
        match.groupValues[1] to match.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&")
    }
}

/** Nom de ressource de chaque identifiant `R.string`. */
val stringNamesById: Map<Int, String> by lazy {
    R.string::class.java.fields.associate { field -> field.getInt(null) to field.name }
}
