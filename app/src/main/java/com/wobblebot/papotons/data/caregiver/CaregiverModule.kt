package com.wobblebot.papotons.data.caregiver

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.wobblebot.papotons.data.createPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

private const val FILE_NAME = "caregivers"

/** Le DataStore des accompagnants, à part des réglages : un seul DataStore par fichier dans le processus. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CaregiversStore

/**
 * Hors de la base Room : une copie de base corrompue part dans la sauvegarde `.obz`, et les codes
 * hachés n'y vont jamais. Le fichier part, lui, dans la sauvegarde Android, comme toutes les préférences.
 */
@Module
@InstallIn(SingletonComponent::class)
object CaregiverModule {
    @Provides
    @Singleton
    @CaregiversStore
    fun caregivers(@ApplicationContext context: Context): DataStore<Preferences> =
        createPreferences { context.preferencesDataStoreFile(FILE_NAME) }
}
