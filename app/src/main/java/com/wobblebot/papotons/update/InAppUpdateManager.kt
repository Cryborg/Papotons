package com.wobblebot.papotons.update

import android.app.Activity
import android.os.SystemClock
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
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val LOG_TAG = "InAppUpdateManager"

private val INSTALL_UNDERWAY = setOf(InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING)

/**
 * Mise à jour proposée DEPUIS l'appli (Play In-App Update).
 *
 * Sans ça, une version publiée n'atteint l'utilisateur que quand le Play Store décide de la
 * poser (quelques heures à quelques jours, et jamais si les mises à jour automatiques
 * sont coupées). L'appli demande donc elle-même à Play s'il existe mieux.
 *
 * Deux régimes (cf. [UpdateCheckPolicy.chooseUpdateKind]) :
 * - **FLEXIBLE**, le cas normal : téléchargement en fond, installation quand l'utilisateur
 *   l'accepte ([isReadyToInstall] puis [completeUpdate]).
 * - **IMMEDIATE**, bloquant : seulement quand la release publiée porte une priorité Play
 *   d'au moins [UpdateCheckPolicy.IMMEDIATE_MIN_PRIORITY].
 *
 * ⚠️ Le check ne répond QUE sur une appli installée depuis le Play Store. En debug ou sur
 * un APK posé à la main il échoue, en silence, et c'est voulu : ce n'est pas une panne,
 * c'est l'absence d'interlocuteur.
 */
class InAppUpdateManager(activity: Activity) {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val _isReadyToInstall = MutableStateFlow(false)

    /** Passe à `true` quand une mise à jour FLEXIBLE est téléchargée et n'attend plus que le feu vert. */
    val isReadyToInstall: StateFlow<Boolean> = _isReadyToInstall.asStateFlow()

    private val installStateListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> _isReadyToInstall.value = true
            InstallStatus.FAILED -> Log.w(LOG_TAG, "Téléchargement de la mise à jour échoué")
            else -> Unit
        }
    }

    init {
        appUpdateManager.registerListener(installStateListener)
    }

    /**
     * Demande à Play s'il existe une version plus récente, et lance le flux si oui.
     * Depuis `onStart` : une appli laissée en fond n'est pas recréée, et un check posé au seul
     * `onCreate` ne voyait jamais passer la version suivante. L'espacement
     * ([UpdateCheckPolicy.isCheckDue]) absorbe les recréations d'activité et le retour du
     * dialogue Play ; le refus mémorisé empêche de reproposer la version écartée.
     */
    fun checkForUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val now = SystemClock.elapsedRealtime()
        if (!UpdateCheckPolicy.isCheckDue(lastCheckAtMs, now)) return
        lastCheckAtMs = now
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info -> onUpdateInfo(info, launcher, mayStartFreshFlow = true) }
            .addOnFailureListener { error ->
                // Hors Play Store, c'est l'issue NORMALE : on la trace sans la remonter
                // comme incident.
                Log.i(LOG_TAG, "Vérification indisponible : ${error.javaClass.simpleName}")
            }
    }

    /**
     * Depuis `onResume`. Reprend une mise à jour IMMEDIATE interrompue, ou récupère un
     * téléchargement FLEXIBLE terminé pendant qu'on était ailleurs.
     *
     * Ne lance JAMAIS un flux neuf : ce serait rejouer la proposition à chaque retour au
     * l'appli, y compris quand l'utilisateur vient de la refuser (boîte Play en boucle).
     */
    fun resumeUpdateIfNeeded(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info -> onUpdateInfo(info, launcher, mayStartFreshFlow = false) }
            .addOnFailureListener { /* hors Play Store, l'issue normale : rien à reprendre */ }
    }

    /**
     * Retour du dialogue Play. Un refus de la proposition FLEXIBLE se retient pour le
     * process ; une IMMEDIATE refusée revient au check suivant.
     */
    fun onUpdateFlowResult(resultCode: Int) {
        val proposal = pendingFlexibleVersionCode ?: return
        pendingFlexibleVersionCode = null
        if (resultCode == Activity.RESULT_CANCELED) declinedVersionCode = proposal
    }

    /** Installe la mise à jour téléchargée : l'appli redémarre. */
    fun completeUpdate() {
        _isReadyToInstall.value = false
        appUpdateManager.completeUpdate()
    }

    fun unregister() {
        appUpdateManager.unregisterListener(installStateListener)
    }

    private fun onUpdateInfo(
        info: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        mayStartFreshFlow: Boolean
    ) {
        when {
            // Une IMMEDIATE laissée en plan se reprend dans les deux cas : l'utilisateur est
            // déjà engagé dedans. ⚠️ Gardée par la priorité : un téléchargement FLEXIBLE
            // accepté se dit lui aussi DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, et le
            // reprendre en IMMEDIATE bloquerait l'utilisateur sur un écran qu'il n'a pas choisi.
            UpdateCheckPolicy.isImmediate(info.updatePriority()) &&
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                startUpdate(info, launcher, UpdateKind.Immediate)

            info.installStatus() == InstallStatus.DOWNLOADED -> _isReadyToInstall.value = true

            // Un téléchargement accepté peut encore se dire UPDATE_AVAILABLE : sans ce garde,
            // le check du retour reproposerait ce à quoi l'utilisateur vient de dire oui.
            info.installStatus() in INSTALL_UNDERWAY -> Unit

            mayStartFreshFlow && info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ->
                UpdateCheckPolicy.chooseUpdateKind(
                    updatePriority = info.updatePriority(),
                    isImmediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                    isFlexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                    availableVersionCode = info.availableVersionCode(),
                    declinedVersionCode = declinedVersionCode
                )?.let { kind -> startUpdate(info, launcher, kind) }
        }
    }

    private fun startUpdate(
        info: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        kind: UpdateKind
    ) {
        val updateType = when (kind) {
            UpdateKind.Flexible -> AppUpdateType.FLEXIBLE
            UpdateKind.Immediate -> AppUpdateType.IMMEDIATE
        }
        runCatching {
            val isStarted = appUpdateManager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(updateType).build()
            )
            if (isStarted && kind == UpdateKind.Flexible) {
                pendingFlexibleVersionCode = info.availableVersionCode()
            }
        }.onFailure { error -> Log.w(LOG_TAG, "Dialogue de mise à jour non ouvert", error) }
    }

    // Au niveau du PROCESS et non de l'activité : une recréation (langue, thème, police)
    // reconstruit le gestionnaire, qui oublierait sinon le refus et l'heure du dernier
    // check. Un champ statique survit à la recréation, pas au process.
    private companion object {
        /** Instant du dernier check, sur `elapsedRealtime`. `null` = jamais. */
        @Volatile
        var lastCheckAtMs: Long? = null

        /** Dernière version FLEXIBLE que l'utilisateur a écartée. */
        @Volatile
        var declinedVersionCode: Int? = null

        /** Version proposée dont le dialogue Play n'a pas encore répondu. */
        @Volatile
        var pendingFlexibleVersionCode: Int? = null
    }
}
