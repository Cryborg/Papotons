package com.wobblebot.papotons.data.caregiver

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Un code d'accompagnant : exactement 4 chiffres. */
const val CAREGIVER_CODE_LENGTH = 4

private const val HASH_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val HASH_ITERATIONS = 20_000
private const val HASH_LENGTH_BITS = 256
private const val SALT_LENGTH_BYTES = 16

fun isValidCaregiverCode(code: String): Boolean = code.length == CAREGIVER_CODE_LENGTH && code.all { it in '0'..'9' }

/**
 * Le code n'est jamais gardé en clair. Le haché ne protège pas un code de 4 chiffres d'un essai
 * systématique (10 000 possibilités) : il évite seulement de le lire tel quel dans une copie du
 * fichier, et c'est pourquoi les accompagnants ne partent jamais dans une sauvegarde `.obz`.
 */
object CaregiverCode {
    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SALT_LENGTH_BYTES).also(random::nextBytes)

    fun hash(code: String, salt: ByteArray): ByteArray {
        require(isValidCaregiverCode(code)) { "A caregiver code is $CAREGIVER_CODE_LENGTH digits" }
        val spec = PBEKeySpec(code.toCharArray(), salt, HASH_ITERATIONS, HASH_LENGTH_BITS)
        try {
            return SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** En temps constant ; un code mal formé est faux, sans lever. */
    fun matches(code: String, salt: ByteArray, expectedHash: ByteArray): Boolean =
        isValidCaregiverCode(code) && MessageDigest.isEqual(hash(code, salt), expectedHash)
}
