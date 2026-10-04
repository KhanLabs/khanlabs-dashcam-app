package dev.khanlabs.dashcam.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.khanlabs.dashcam.data.repository.DashcamRepository
import dev.khanlabs.dashcam.data.repository.RealDashcamRepository
import dev.khanlabs.dashcam.data.repository.RealSettingsRepository
import dev.khanlabs.dashcam.data.repository.RealTripRepository
import dev.khanlabs.dashcam.data.repository.RealVideoRepository
import dev.khanlabs.dashcam.data.repository.SettingsRepository
import dev.khanlabs.dashcam.data.repository.TripRepository
import dev.khanlabs.dashcam.data.repository.VideoRepository
import javax.inject.Singleton

/**
 * Video/Trip/Dashcam/Settings are all bound to their real implementations
 * now. Video/Trip/Dashcam talk to the dashcam over HTTP (see
 * RealVideoRepository/RealTripRepository/RealDashcamRepository) and degrade
 * to empty/off-network defaults when not on the dashcam's network, so the
 * app stays fully usable off-network. Settings (see RealSettingsRepository)
 * is DataStore-backed local app preferences -- no dashcam involvement at
 * all. The Mock* classes are kept in the codebase (not deleted) for
 * reference and in case a fallback is ever needed.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindDashcamRepository(impl: RealDashcamRepository): DashcamRepository

    @Binds
    @Singleton
    abstract fun bindVideoRepository(impl: RealVideoRepository): VideoRepository

    @Binds
    @Singleton
    abstract fun bindTripRepository(impl: RealTripRepository): TripRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: RealSettingsRepository): SettingsRepository
}
