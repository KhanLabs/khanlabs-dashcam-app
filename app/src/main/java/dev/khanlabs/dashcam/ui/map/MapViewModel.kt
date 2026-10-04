package dev.khanlabs.dashcam.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.model.GpsTrack
import dev.khanlabs.dashcam.data.model.TripStats
import dev.khanlabs.dashcam.data.model.computeStats
import dev.khanlabs.dashcam.data.repository.TripRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: TripRepository
) : ViewModel() {

    private val _selectedDateIndex = MutableStateFlow(0)
    private val _replayIndex = MutableStateFlow(0)
    private val _isPlaying = MutableStateFlow(false)

    val tracks: StateFlow<List<GpsTrack>> = repository.observeTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedDateIndex: StateFlow<Int> = _selectedDateIndex

    val selectedTrack: StateFlow<GpsTrack?> = combine(tracks, _selectedDateIndex) { tracks, index ->
        tracks.getOrNull(index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stats: StateFlow<TripStats?> = selectedTrack
        .map { it?.computeStats() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val replayIndex: StateFlow<Int> = _replayIndex
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private var playbackJob: Job? = null

    fun selectDate(index: Int) {
        _selectedDateIndex.value = index
        _replayIndex.value = 0
        stopPlayback()
    }

    fun setReplayIndex(index: Int) {
        _replayIndex.value = index
    }

    fun togglePlay() {
        if (_isPlaying.value) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        val track = selectedTrack.value ?: return
        _isPlaying.value = true
        playbackJob = viewModelScope.launch {
            while (_isPlaying.value && _replayIndex.value < track.points.size - 1) {
                delay(500)
                _replayIndex.value += 1
            }
            _isPlaying.value = false
        }
    }

    private fun stopPlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }
}
