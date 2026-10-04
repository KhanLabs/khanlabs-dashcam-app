package dev.khanlabs.dashcam.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.model.CameraIndex
import dev.khanlabs.dashcam.data.model.VideoClip
import dev.khanlabs.dashcam.data.repository.ClipThumbnailRepository
import dev.khanlabs.dashcam.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CameraFilter(val label: String) {
    ALL("All"),
    ROAD("Road (Cam 0)"),
    CABIN("Cabin (Cam 1)")
}

data class GalleryClipItem(
    val clip: VideoClip,
    val isDualCamEvent: Boolean
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val thumbnailRepository: ClipThumbnailRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(CameraFilter.ALL)
    val filter: StateFlow<CameraFilter> = _filter

    // Single shared subscription: VideoRepository.observeClips() is a plain
    // cold Flow with no caching of its own, and it independently re-resolves
    // a remote-vs-local fallback on each subscriber (RealVideoRepository's
    // fetchClips/localClips race against the dashcam's single-threaded
    // httpd). Two independent stateIn() chains here used to each start their
    // own subscription, so visibleClips and totalClipCount could resolve to
    // different underlying listings (one timing out to a small local
    // fallback while the other got the full remote one) and disagree with
    // each other -- not just duplicate the (already expensive) work.
    private val clips: StateFlow<List<VideoClip>> =
        repository.observeClips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visibleClips: StateFlow<List<GalleryClipItem>> =
        combine(clips, _filter) { clips, filter ->
            val epochsWithBothCams = clips.groupBy { it.epochSeconds }
                .filterValues { group ->
                    group.any { it.cameraIndex == CameraIndex.ROAD } &&
                        group.any { it.cameraIndex == CameraIndex.CABIN }
                }
                .keys

            clips
                .filter { clip ->
                    when (filter) {
                        CameraFilter.ALL -> true
                        CameraFilter.ROAD -> clip.cameraIndex == CameraIndex.ROAD
                        CameraFilter.CABIN -> clip.cameraIndex == CameraIndex.CABIN
                    }
                }
                .sortedByDescending { it.epochSeconds }
                .map { clip -> GalleryClipItem(clip, isDualCamEvent = clip.epochSeconds in epochsWithBothCams) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Unfiltered count, so the empty state can tell "nothing synced yet"
     *  (this is 0) apart from "nothing for this camera filter" (this is >0
     *  but [visibleClips] is empty) -- see BACKLOG's Gallery-tabs item.
     *  Derived from the same [clips] subscription as [visibleClips] so the
     *  two can never disagree. */
    val totalClipCount: StateFlow<Int> =
        clips.map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setFilter(newFilter: CameraFilter) {
        _filter.value = newFilter
    }

    fun removeClip(filename: String) {
        viewModelScope.launch { repository.removeClip(filename) }
    }

    // Selection mode is a separate flag from "selection non-empty" rather
    // than derived from it -- entering selection mode with nothing yet
    // checked (the moment right after tapping the select icon) needs to
    // show checkboxes, not fall back to the normal Play/Share/Delete row.
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode

    private val _selectedFilenames = MutableStateFlow<Set<String>>(emptySet())
    val selectedFilenames: StateFlow<Set<String>> = _selectedFilenames

    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    /** Also clears the selection -- re-entering selection mode later should
     *  start from nothing checked, not silently resume a stale selection
     *  from before (clips could have scrolled out/changed by then). */
    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedFilenames.value = emptySet()
    }

    fun toggleSelected(filename: String) {
        _selectedFilenames.update { current ->
            if (filename in current) current - filename else current + filename
        }
    }

    /** [filenames] is the caller's currently *visible* list (respecting the
     *  active camera filter) -- "select all" means all in this view, not
     *  every clip regardless of what's filtered out. */
    fun selectAll(filenames: List<String>) {
        _selectedFilenames.value = filenames.toSet()
    }

    fun clearSelection() {
        _selectedFilenames.value = emptySet()
    }

    /** Removes every currently-selected clip and exits selection mode.
     *  Sequential rather than parallel launches -- these are already fast,
     *  local, per-file operations (see [VideoRepository.removeClip]), and
     *  keeping them on one coroutine avoids piling up N concurrent
     *  coroutines for what could be a large batch. */
    fun removeSelected() {
        val toRemove = _selectedFilenames.value
        if (toRemove.isEmpty()) return
        viewModelScope.launch {
            toRemove.forEach { repository.removeClip(it) }
            exitSelectionMode()
        }
    }

    /** Suspends until a local file is ready to hand to the share sheet (or
     *  null on failure) -- called directly from a Composable's own
     *  coroutine scope so the caller can show per-row progress/errors. */
    suspend fun prepareForShare(clip: VideoClip): java.io.File? = repository.prepareForShare(clip)

    /** Suspends until a cached/extracted thumbnail frame is ready, or null
     *  on failure -- see [ClipThumbnailRepository]. */
    suspend fun thumbnailFor(clip: VideoClip): java.io.File? = thumbnailRepository.getThumbnail(clip)
}
