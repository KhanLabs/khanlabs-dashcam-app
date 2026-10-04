package dev.khanlabs.dashcam.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.model.CameraIndex
import dev.khanlabs.dashcam.data.model.VideoClip
import dev.khanlabs.dashcam.data.repository.VideoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    repository: VideoRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val epochSeconds: Long = checkNotNull(savedStateHandle["epochSeconds"])
    val initialCameraIndex: CameraIndex =
        CameraIndex.valueOf(checkNotNull(savedStateHandle["cameraIndex"]))

    /** All clips sharing this event's epoch -- 1 clip normally, 2 for a dual-cam event. */
    val eventClips: StateFlow<List<VideoClip>> = repository.observeClips()
        .map { clips -> clips.filter { it.epochSeconds == epochSeconds } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
