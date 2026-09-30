package com.stream4k60.app.di

import android.content.Context
import androidx.room.Room
import com.stream4k60.app.data.local.AppDatabase
import com.stream4k60.app.data.repository.ProfileRepository
import com.stream4k60.app.data.repository.SceneRepository
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.StreamEngine
import com.stream4k60.app.engine.StreamEngineImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "stream4k60.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides fun sceneRepo(db: AppDatabase) = SceneRepository(db.sceneDao(), db.sourceDao())
    @Provides fun profileRepo(db: AppDatabase) = ProfileRepository(db.profileDao())
    @Provides @Singleton fun settingsRepo(db: AppDatabase) = SettingsRepository(db.profileDao())
    @Provides @Singleton fun engine(impl: StreamEngineImpl): StreamEngine = impl
}
