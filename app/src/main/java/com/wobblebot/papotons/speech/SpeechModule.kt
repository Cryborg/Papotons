package com.wobblebot.papotons.speech

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Non scopé : chaque ViewModel a sa voix et l'éteint dans `onCleared`. Un singleton éteint
 * par un premier ViewModel laisserait le suivant muet.
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class SpeechModule {
    @Binds
    abstract fun speaker(recoveringSpeaker: RecoveringSpeaker): Speaker

    @Binds
    abstract fun engineFactory(factory: AndroidSpeechEngine.Factory): SpeechEngineFactory

    @Binds
    abstract fun scheduler(handlerScheduler: HandlerScheduler): MainThreadScheduler
}
