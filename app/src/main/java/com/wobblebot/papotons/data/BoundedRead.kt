package com.wobblebot.papotons.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** `null` au-delà de [maxBytes] : rien n'est gardé en mémoire au-delà de la borne. */
internal fun InputStream.readAtMost(maxBytes: Long): ByteArray? {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return bytes.toByteArray()
        total += read
        if (total > maxBytes) return null
        bytes.write(buffer, 0, read)
    }
}
