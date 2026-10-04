package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.model.VideoClip
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Real (manifest-endpoint-backed) implementation is [RealVideoRepository];
 * [MockVideoRepository] is kept for reference. [removeClip] hides a clip
 * from the Gallery and, if a local synced copy exists, deletes it -- it does
 * NOT delete anything from the dashcam's SD card (no write-capable device
 * endpoint exists for that yet).
 */
interface VideoRepository {
    fun observeClips(): Flow<List<VideoClip>>
    suspend fun removeClip(filename: String)

    /** Returns a local [File] for [clip], downloading it first if only a
     *  remote copy exists yet (reuses the same transfer SyncEngine uses).
     *  Null on download failure (e.g. off the dashcam's network). Used by
     *  the Gallery's Share action, which needs a real local file to hand to
     *  a FileProvider -- a bare `http://` URL can't be shared directly. */
    suspend fun prepareForShare(clip: VideoClip): File?

    /**
     * The true, full clip listing straight off the dashcam's SD card at
     * [gatewayIp] -- bypasses [observeClips]'s local-first emission (see
     * RealVideoRepository: that flow emits already-synced local clips
     * immediately so Gallery/Player aren't blocked on the slow remote walk,
     * then replaces it with this same authoritative listing once it
     * resolves). Callers that need to know what's actually new -- SyncEngine
     * deciding what to download, RealDashcamRepository computing new-clip/
     * road/cabin counts -- must call this directly rather than
     * `observeClips().first()`, which would otherwise silently return only
     * the local fallback and make every clip look "already synced."
     */
    suspend fun fetchRemoteClips(gatewayIp: String): List<VideoClip>
}
