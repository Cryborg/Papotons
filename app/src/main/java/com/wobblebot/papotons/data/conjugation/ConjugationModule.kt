package com.wobblebot.papotons.data.conjugation

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ConjugationModule {
    @Binds
    abstract fun conjugations(conjugations: AssetConjugations): Conjugations
}
