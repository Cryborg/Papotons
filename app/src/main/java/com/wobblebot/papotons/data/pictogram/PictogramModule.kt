package com.wobblebot.papotons.data.pictogram

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PictogramModule {
    @Binds
    abstract fun arasaacClient(client: HttpArasaacClient): ArasaacClient

    @Binds
    abstract fun pictogramStore(images: PictogramImages): PictogramStore
}
