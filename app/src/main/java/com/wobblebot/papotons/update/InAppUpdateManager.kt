package com.wobblebot.papotons.update

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val LOG_TAG = "InAppUpdateManager"

/**
 * Mise à jour Play (In-App Update) proposée dans les Réglages, derrière le contrôle des adultes.
 *
 * Sans ça, une version publiée n'atteint l'utilisateur que quand le Play Store décide de la
 * poser (quelques heures à quelques jours, et jamais si les mises à jour automatiques sont
 * coupées). L'appli demande donc à Play s'il existe mieux, mais n'ouvre jamais rien d'elle-même :
 * elle tient [status] à jour, et seul un adulte lance le téléchargement ([startUpdate]) puis
 * l'installation ([completeUpdate]).
 *
 * ⚠️ La demande ne répond QUE sur une appli installée depuis le Play Store. En debug ou sur
 * un APK posé à la main elle échoue, en silence, et c'est voulu : ce n'est pas une panne,
 * c'est l'absence d'interlocuteur.
 */
class InAppUpdateManager(activity: Activity) {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val _status = MutableStateFlow(UpdateStatus.None)

    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** La réponse de Play qui a rendu la version disponible : un flux ne se lance qu'avec elle, une seule fois. */
    private var offeredUpdate: AppUpdateInfo? = null

    private val installStateListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> _status.value = UpdateStatus.ReadyToInstall
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> _status.value = UpdateStatus.Downloading
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                Log.w(LOG_TAG, "Téléchargement de la mise à jour interrompu : ${state.installStatus()}")
                refreshStatus()
            }
            else -> Unit
        }
    }

    init {
        appUpdateManager.registerListener(installStateListener)
    }

    /**
     * Depuis `onResume` : une appli laissée en fond n'est pas recréée, et un téléchargement a pu
     * finir pendant qu'on était ailleurs. Ne fait que demander, n'ouvre rien.
     */
    fun refreshStatus() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                _status.value = UpdateCheckPolicy.status(
                    updateAvailability = info.updateAvailability(),
                    installStatus = info.installStatus(),
                    isFlexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                    isImmediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                )
                offeredUpdate = info.takeIf { _status.value == UpdateStatus.Available }
            }
            .addOnFailureListener { error ->
                // Hors Play Store, c'est l'issue NORMALE : on la trace sans la remonter comme incident.
                Log.i(LOG_TAG, "Vérification indisponible : ${error.javaClass.simpleName}")
            }
    }

    /** L'adulte lance le téléchargement depuis les Réglages : Play montre sa propre confirmation. */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val info = offeredUpdate ?: return
        val kind = UpdateCheckPolicy.chooseUpdateKind(
            isFlexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            isImmediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
        ) ?: return
        val updateType = when (kind) {
            UpdateKind.Flexible -> AppUpdateType.FLEXIBLE
            UpdateKind.Immediate -> AppUpdateType.IMMEDIATE
        }
        offeredUpdate = null
        // Dialogue pas ouvert : sans nouvelle réponse de Play, la ligne resterait affichée et sourde.
        runCatching {
            appUpdateManager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(updateType).build())
        }.onSuccess { isStarted ->
            if (!isStarted) {
                Log.w(LOG_TAG, "Dialogue de mise à jour refusé par Play")
                refreshStatus()
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "Dialogue de mise à jour non ouvert", error)
            refreshStatus()
        }
    }

    /** Retour du dialogue Play : une réponse de Play ne sert qu'une fois, on en redemande une. */
    fun onUpdateFlowResult() = refreshStatus()

    /** Installe la mise à jour téléchargée : l'appli redémarre. */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    fun unregister() {
        appUpdateManager.unregisterListener(installStateListener)
    }
}
