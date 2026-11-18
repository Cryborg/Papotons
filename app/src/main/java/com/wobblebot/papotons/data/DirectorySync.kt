package com.wobblebot.papotons.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * Un renommage n'est durable qu'une fois son dossier synchronisé : sans cela, après une coupure de
 * courant, la base (synchronisée) pourrait viser un fichier dont l'entrée n'existe plus. La classe de
 * l'erreur seulement : son message peut contenir un nom de photo.
 */
internal fun syncDirectory(directory: File, logTag: String) {
    try {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    } catch (exception: ErrnoException) {
        Log.w(logTag, "Directory could not be synced: ${exception.javaClass.simpleName}")
    }
}
