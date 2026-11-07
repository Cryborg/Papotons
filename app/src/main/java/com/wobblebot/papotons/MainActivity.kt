package com.wobblebot.papotons

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wobblebot.papotons.data.pictogram.PictogramImages
import com.wobblebot.papotons.ui.board.BoardRoute
import com.wobblebot.papotons.ui.board.BoardViewModel
import com.wobblebot.papotons.data.photo.CellPhotos
import com.wobblebot.papotons.ui.board.LocalPhotoImages
import com.wobblebot.papotons.ui.board.LocalPhotoLoader
import com.wobblebot.papotons.ui.board.LocalPictogramImages
import com.wobblebot.papotons.ui.board.LocalPictogramLoader
import com.wobblebot.papotons.ui.splash.StudioSplashScreen
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import com.wobblebot.papotons.update.InAppUpdateManager
import com.wobblebot.papotons.update.UpdateReadyDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * L'activité unique : splash du studio, bord à bord, mise à jour depuis l'appli.
 * Les écrans sont des composables ; ce fichier ne fait que brancher les briques.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var pictogramImages: PictogramImages

    @Inject
    lateinit var cellPhotos: CellPhotos

    private lateinit var inAppUpdateManager: InAppUpdateManager

    private val updateResultLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            inAppUpdateManager.onUpdateFlowResult(result.resultCode)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // AVANT super.onCreate, sinon le thème de splash reste en place.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Chaque écran consomme ensuite ses insets (la grille : barres système et encoche, pas le clavier).
        enableEdgeToEdge()
        // Les touches de volume règlent la voix (flux média), pas la sonnerie, même quand rien ne joue.
        volumeControlStream = AudioManager.STREAM_MUSIC
        inAppUpdateManager = InAppUpdateManager(this)
        setContent {
            PapotonsTheme {
                CompositionLocalProvider(
                    LocalPictogramImages provides pictogramImages::image,
                    LocalPictogramLoader provides pictogramImages::ensureLoaded,
                    LocalPhotoImages provides cellPhotos::image,
                    LocalPhotoLoader provides cellPhotos::ensureLoaded,
                ) {
                    PapotonsApp(inAppUpdateManager)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        inAppUpdateManager.checkForUpdate(updateResultLauncher)
    }

    override fun onResume() {
        super.onResume()
        inAppUpdateManager.resumeUpdateIfNeeded(updateResultLauncher)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::inAppUpdateManager.isInitialized) inAppUpdateManager.unregister()
    }
}

@Composable
private fun PapotonsApp(inAppUpdateManager: InAppUpdateManager) {
    // rememberSaveable : une rotation ou un changement de langue ne rejoue pas le carton du studio.
    var isSplashFinished by rememberSaveable { mutableStateOf(false) }
    val isUpdateReady by inAppUpdateManager.isReadyToInstall.collectAsStateWithLifecycle()
    // « Plus tard » vaut pour la session : le flux reste vrai et la boîte reviendrait à chaque retour.
    var isUpdatePostponed by rememberSaveable { mutableStateOf(false) }
    val boardViewModel: BoardViewModel = hiltViewModel()
    val boardState by boardViewModel.uiState.collectAsStateWithLifecycle()
    // Le redémarrage de l'installation ne doit jamais couper une phrase ni une modification en cours.
    val isBusy = boardState.isBusy

    Box {
        BoardRoute(viewModel = boardViewModel, state = boardState)
        // Dernier enfant de la Box : dessiné au-dessus, il reçoit les taps en premier.
        if (!isSplashFinished) {
            StudioSplashScreen(onFinished = { isSplashFinished = true })
        }
        if (isSplashFinished && isUpdateReady && !isUpdatePostponed && !isBusy) {
            UpdateReadyDialog(
                onInstall = inAppUpdateManager::completeUpdate,
                onLater = { isUpdatePostponed = true }
            )
        }
    }
}
