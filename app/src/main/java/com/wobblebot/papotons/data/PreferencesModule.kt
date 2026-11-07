package com.wobblebot.papotons.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

private const val TAG = "PreferencesModule"
private const val FILE_NAME = "settings"

/**
 * Préférences de l'appli, sauvegardées avec la base par la sauvegarde Android (voir
 * `res/xml/data_extraction_rules.xml`). Un seul DataStore par fichier dans tout le processus.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        createPreferences { context.preferencesDataStoreFile(FILE_NAME) }
}

/** Partagée avec les tests, qui vérifient le sort d'un fichier corrompu. */
fun createPreferences(produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        // Un fichier illisible coûte les réglages, jamais l'appli : il repart vide.
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            Log.e(TAG, "Preferences file corrupted, starting from empty preferences", exception)
            emptyPreferences()
        },
        produceFile = produceFile,
    )
