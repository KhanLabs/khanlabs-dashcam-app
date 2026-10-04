package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.model.DeviceStatus
import dev.khanlabs.dashcam.data.network.DeviceStats
import dev.khanlabs.dashcam.data.network.LteStatus
import kotlinx.coroutines.flow.Flow

/**
 * Everything a screen needs from "the dashcam" goes through here.
 * [RealDashcamRepository] backs this with the device's real `cgi-bin/status`
 * endpoint; [MockDashcamRepository] is kept for reference/fallback.
 */
interface DashcamRepository {
    fun observeDeviceStatus(): Flow<DeviceStatus>

    /** Null while not on the dashcam's network, or before the first poll
     *  resolves -- see [RealDashcamRepository.observeLteStatus] for the
     *  polling cadence. */
    fun observeLteStatus(): Flow<LteStatus?>

    /** Null while not on the dashcam's network, or before the first poll
     *  resolves -- same polling pattern as [observeLteStatus]. */
    fun observeDeviceStats(): Flow<DeviceStats?>
}
