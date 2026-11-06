package com.wobblebot.papotons.ui.settings

import androidx.compose.runtime.Immutable
import com.wobblebot.papotons.BuildConfig

/**
 * La version du build qui tourne.
 *
 * [versionCode] accompagne toujours [versionName] : c'est lui qui désigne un build sans
 * ambiguïté (le nom reste le même sur le build de dev et sur celui du Store tant que la
 * version suivante n'est pas taggée), et c'est la première question de tout diagnostic à
 * distance.
 */
@Immutable
data class AppVersion(val versionName: String, val versionCode: Int) {
    companion object {
        val current = AppVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }
}
