package dev.khanlabs.dashcam.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.khanlabs.dashcam.data.local.AppDatabase
import dev.khanlabs.dashcam.data.local.RemovedClipDao
import dev.khanlabs.dashcam.data.local.SyncedFileDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "khanlabs-dashcam.db")
            // Pre-1.0, single-device app -- no real migration to preserve,
            // a destructive reset on schema bump is fine (worst case: one
            // false "everything new" Dashboard flash that self-heals via
            // the disk-union logic in RealDashcamRepository.computePendingSync).
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSyncedFileDao(database: AppDatabase): SyncedFileDao = database.syncedFileDao()

    @Provides
    fun provideRemovedClipDao(database: AppDatabase): RemovedClipDao = database.removedClipDao()
}
