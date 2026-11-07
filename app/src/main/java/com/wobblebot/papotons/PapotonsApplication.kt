package com.wobblebot.papotons

import android.app.Application
import android.util.Log
import com.wobblebot.papotons.data.backup.BoardBackup
import com.wobblebot.papotons.data.photo.CellPhotos
import com.wobblebot.papotons.data.pictogram.PictogramImages
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "PapotonsApplication"

@HiltAndroidApp
class PapotonsApplication : Application() {
    @Inject
    lateinit var pictogramImages: PictogramImages

    @Inject
    lateinit var cellPhotos: CellPhotos

    @Inject
    lateinit var boardBackup: BoardBackup

    // Une erreur imprévue au chargement des images se journalise : elle ne doit pas faire planter
    // l'appli, qui parle sans elles.
    private val applicationScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, throwable -> Log.e(TAG, "Background work failed", throwable) }
    )

    override fun onCreate() {
        super.onCreate()
        // Une fois par processus, pendant le carton du studio : la grille trouve ses pictos prêts.
        applicationScope.launch { pictogramImages.loadBundled() }
        applicationScope.launch { cellPhotos.cleanUp() }
        // Une sauvegarde lue et jamais confirmée (processus tué) : elle ne sert plus.
        applicationScope.launch { boardBackup.discard() }
    }
}
