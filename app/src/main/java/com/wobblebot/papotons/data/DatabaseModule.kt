package com.wobblebot.papotons.data

import android.content.Context
import androidx.room.Room
import com.wobblebot.papotons.data.board.BoardDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PapotonsDatabase =
        Room.databaseBuilder(context, PapotonsDatabase::class.java, PapotonsDatabase.NAME).build()

    @Provides
    fun boardDao(database: PapotonsDatabase): BoardDao = database.boardDao()
}
