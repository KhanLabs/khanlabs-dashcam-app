package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.model.VideoClip
import dev.khanlabs.dashcam.data.parser.VideoFilenameParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject

/**
 * Fixture clips built by running real-shaped filenames through
 * [VideoFilenameParser] -- the same code path the eventual manifest-backed
 * repository will use -- covering the cases the Gallery screen needs to
 * demonstrate: a legacy (2009 clock-bug) dual-cam pair, a legacy road-only
 * clip, a recent (GPS-synced) dual-cam pair, and recent singles.
 */
class MockVideoRepository @Inject constructor() : VideoRepository {

    private val fixtureFilenames = listOf(
        // Legacy dual-cam event (clock never corrected this boot session)
        "351234567890123_0_2009-01-01_03-00-35_1230760835.mp4" to (118 to 47_400_000L),
        "351234567890123_1_2009-01-01_03-00-35_1230760835.mp4" to (118 to 39_800_000L),
        "351234567890123_0_2009-01-01_03-06-40_1230761200.mp4" to (95 to 30_100_000L),
        // Recent, GPS-time-synced dual-cam event
        "351234567890123_0_2026-07-06_14-23-05_1783520585.mp4" to (132 to 52_600_000L),
        "351234567890123_1_2026-07-06_14-23-05_1783520585.mp4" to (132 to 44_200_000L),
        "351234567890123_0_2026-07-06_09-10-00_1783501800.mp4" to (60 to 22_300_000L),
        "351234567890123_1_2026-07-05_18-45-00_1783423500.mp4" to (45 to 15_900_000L)
    )

    private val clips = MutableStateFlow(
        fixtureFilenames.mapNotNull { (name, meta) ->
            VideoFilenameParser.parse(name, durationSeconds = meta.first, sizeBytes = meta.second)
        }
    )

    override fun observeClips(): StateFlow<List<VideoClip>> = clips

    override suspend fun removeClip(filename: String) {
        clips.update { current -> current.filterNot { it.filename == filename } }
    }

    // Fixture clips play from bundled raw resources, not a real file --
    // nothing to hand a FileProvider, so sharing is unsupported in mock mode.
    override suspend fun prepareForShare(clip: VideoClip): File? = null

    // No real dashcam behind this repository -- the fixture list already
    // is the "authoritative" listing, gatewayIp is irrelevant.
    override suspend fun fetchRemoteClips(gatewayIp: String): List<VideoClip> = clips.value
}
