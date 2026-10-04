package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.model.GpsTrack
import kotlinx.coroutines.flow.Flow

/**
 * Same staging as the other repositories: mock-backed today (parses a
 * bundled sample GPX), swaps to reading real synced `.gpx` files from the
 * dashcam's file server later behind this same interface.
 */
interface TripRepository {
    fun observeTracks(): Flow<List<GpsTrack>>
}
