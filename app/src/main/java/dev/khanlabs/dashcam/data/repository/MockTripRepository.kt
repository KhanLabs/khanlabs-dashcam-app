package dev.khanlabs.dashcam.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.khanlabs.dashcam.R
import dev.khanlabs.dashcam.data.model.GpsTrack
import dev.khanlabs.dashcam.data.parser.GpxParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import javax.inject.Inject

class MockTripRepository @Inject constructor(
    @ApplicationContext context: Context
) : TripRepository {

    private val tracks = MutableStateFlow(
        listOf(
            GpsTrack(
                date = LocalDate.of(2026, 7, 5),
                points = context.resources.openRawResource(R.raw.sample_trip).use { stream ->
                    GpxParser.parse(stream)
                }
            )
        )
    )

    override fun observeTracks(): StateFlow<List<GpsTrack>> = tracks
}
