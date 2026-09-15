package com.example.mmtv.ui

import android.net.Uri
import android.util.Log
import com.example.mmtv.ui.theme.AccentColor
import com.example.mmtv.ui.theme.FocusBorderColor
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import coil.compose.AsyncImage
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.Episode
import com.example.mmtv.model.EpgListing
import com.example.mmtv.ui.components.*
import com.example.mmtv.ui.components.EpgGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.absoluteValue

enum class OverlayState {
    NONE, CHANNELS, CATEGORIES, SUBTITLES, AUDIO_TRACKS, QUICK_INFO, EPG_INFO, FULL_EPG, FAVORITE_TIMELINE
}

private enum class QuickInfoFocusTarget { TV_TABLE, SUBTITLES, AUDIO_TRACKS }

private data class AudioTrackOption(
    val group: Tracks.Group,
    val trackIndex: Int,
    val format: Format
)

private fun AudioTrackOption.displayName(position: Int): String {
    val label = format.label?.trim()?.takeIf { it.isNotEmpty() }
    val language = format.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { languageTag ->
            Locale.forLanguageTag(languageTag).getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                .takeIf { it.isNotBlank() }
        }
    val channelLayout = when (format.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        in 6..7 -> "5.1"
        in 8..Int.MAX_VALUE -> "7.1"
        else -> null
    }
    return listOfNotNull(label ?: language ?: "Ljudspår ${position + 1}", channelLayout)
        .joinToString(" · ")
}

@OptIn(UnstableApi::class)
private enum class VideoResizeMode(
    val playerViewResizeMode: Int,
    val label: String,
    val forcedAspectRatio: Float? = null
) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "FIT"),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "ZOOM"),
    STRETCH(AspectRatioFrameLayout.RESIZE_MODE_FILL, "STRETCH"),
    CINEMA(AspectRatioFrameLayout.RESIZE_MODE_FILL, "CINEMA", 2.39f);

    fun next(): VideoResizeMode = when (this) {
        FIT -> ZOOM
        ZOOM -> STRETCH
        STRETCH -> CINEMA
        CINEMA -> FIT
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    media: MediaSource? = null,
    playlist: List<MediaSource> = emptyList(),
    categories: List<GroupedMedia> = emptyList(),
    onMediaSelected: (MediaSource) -> Unit = {},
    onCategorySelected: (Int) -> Unit = {},
    onBackPressed: () -> Unit = {},
    onBackgrounded: () -> Unit = {},
    onPlayNextEpisode: (Episode) -> Unit = {},
    onArchiveSelected: (EpgListing) -> Unit = {},
    onReturnToLive: () -> Unit = {},
    viewModel: MediaViewModel
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val focusManager = LocalFocusManager.current
    val sessionManager = remember { SessionManager(context) }
    val isLiveStream = media?.type == MediaType.LIVE
    val playbackPathSegments = remember(url) { Uri.parse(Uri.decode(url)).pathSegments }
    val isActualLiveRoute = playbackPathSegments.any { it.equals("live", ignoreCase = true) }
    val isCatchupPlayback = isLiveStream && playbackPathSegments.any { it.equals("timeshift", ignoreCase = true) }
    val isTimelinePlayback = media != null && (media.type != MediaType.LIVE || isCatchupPlayback)
    val exoPlayer = remember(isLiveStream) { viewModel.getOrInitializePlayer(isLiveStream) }

    val showPlaybackDetails = remember { sessionManager.getShowPlaybackDetails() }
    var showIntroDetails by remember(url) { mutableStateOf(true) }
    val detailsVisible = showPlaybackDetails || showIntroDetails
    val frameRateEstimator = remember(url) { FrameRateEstimator() }
    var measuredFps by remember(url) { mutableStateOf<Float?>(null) }
    var networkBitrate by remember(url) { mutableStateOf<Long?>(null) }
    var detailVideoFormat by remember(url) { mutableStateOf<Format?>(null) }
    DisposableEffect(exoPlayer, url, detailsVisible) {
        val listener = object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                if (totalLoadTimeMs > 0 && totalBytesLoaded > 0 && bitrateEstimate > 0) {
                    networkBitrate = bitrateEstimate
                }
            }
        }
        val frameListener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            frameRateEstimator.addFrame(presentationTimeUs)
        }
        if (detailsVisible) {
            exoPlayer.addAnalyticsListener(listener)
            exoPlayer.setVideoFrameMetadataListener(frameListener)
        }
        onDispose {
            if (detailsVisible) {
                exoPlayer.removeAnalyticsListener(listener)
                exoPlayer.clearVideoFrameMetadataListener(frameListener)
            }
        }
    }
    LaunchedEffect(exoPlayer, url, detailsVisible) {
        if (detailsVisible) {
            while (true) {
                detailVideoFormat = exoPlayer.videoFormat
                measuredFps = frameRateEstimator.framesPerSecond()
                delay(500)
            }
        }
    }

    val hasVideoDetails = detailVideoFormat != null
    LaunchedEffect(url, hasVideoDetails) {
        if (hasVideoDetails) {
            delay(5000)
            showIntroDetails = false
        }
    }

    // --- STATES ---
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var overlayState by remember { mutableStateOf(OverlayState.NONE) }
    var showSeekFeedback by remember { mutableStateOf(false) }
    var channelNumberBuffer by remember { mutableStateOf("") }
    var channelNumberJob by remember { mutableStateOf<Job?>(null) }
    var channelNumberFeedback by remember { mutableStateOf(false) }
    var videoResizeMode by remember(url) { mutableStateOf(VideoResizeMode.FIT) }
    var vodControlsDismissed by remember(url) { mutableStateOf(false) }
    var isCurrentMediaSeekable by remember(url) { mutableStateOf(false) }
    val showVodControls = media != null &&
        isTimelinePlayback &&
        !isActualLiveRoute &&
        (!isCatchupPlayback || isCurrentMediaSeekable) &&
        (showSeekFeedback || !isPlaying) &&
        !vodControlsDismissed

    var seekMessage by remember { mutableStateOf("") }
    
    var accumulatedSeekMs by remember { mutableLongStateOf(0L) }
    var isLongPressSeeking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var infoJob by remember { mutableStateOf<Job?>(null) }

    val commitChannelNumber = {
        val number = channelNumberBuffer.toIntOrNull()
        val target = number?.takeIf { it > 0 }?.let(viewModel::getCachedLiveChannelByNumber)
        val targetCategoryId = target?.categoryId
        val targetPlaylist = targetCategoryId?.let(viewModel::getCachedLiveCategoryPlaylist).orEmpty()
        if (target?.type == MediaType.LIVE && !targetCategoryId.isNullOrBlank() && targetPlaylist.isNotEmpty()) {
            val targetCategoryIndex = categories.indexOfFirst { it.categoryId == targetCategoryId }
            if (targetCategoryIndex >= 0) onCategorySelected(targetCategoryIndex)
            viewModel.currentPlaylist = targetPlaylist
            onMediaSelected(target)
        } else {
            channelNumberFeedback = true
            scope.launch {
                delay(900)
                channelNumberFeedback = false
            }
        }
        channelNumberBuffer = ""
        channelNumberJob?.cancel()
        channelNumberJob = null
    }

    DisposableEffect(Unit) {
        onDispose { channelNumberJob?.cancel() }
    }

    LaunchedEffect(media?.id, media?.type, overlayState) {
        if (overlayState != OverlayState.NONE) {
            channelNumberJob?.cancel()
            channelNumberJob = null
            channelNumberBuffer = ""
        }
    }

    // --- TIMERS ---
    val resetAutoHideTimer = {
        infoJob?.cancel()
        infoJob = scope.launch {
            delay(5000)
            if (overlayState == OverlayState.QUICK_INFO) {
                overlayState = OverlayState.NONE
            }
        }
    }

    // --- LIST STATES ---
    val channelListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val subtitleListState = rememberLazyListState()
    val epgListState = rememberLazyListState()
    val audioTrackListState = rememberLazyListState()
    
    // --- FOCUS REQUESTERS ---
    val mainFocusRequester = remember { FocusRequester() }
    val timelineFocusRequester = remember { FocusRequester() }
    val epgFocusRequester = remember { FocusRequester() }
    val subtitleIconFocusRequester = remember { FocusRequester() }
    val audioIconFocusRequester = remember { FocusRequester() }
    val quickInfoSubtitleFocusRequester = remember { FocusRequester() }
    val quickInfoAudioFocusRequester = remember { FocusRequester() }
    val tvGuideFocusRequester = remember { FocusRequester() }
    val nextEpisodeButtonFocusRequester = remember { FocusRequester() }
    val favoriteButtonFocusRequester = remember { FocusRequester() }
    
    val channelFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val categoryFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val subtitleFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val audioTrackFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val recentChannelsFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    // --- MEDIA STATE ---
    var availableSubtitles by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var availableAudioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
    var trackModalReturnState by remember { mutableStateOf(OverlayState.NONE) }
    var quickInfoFocusTarget by remember { mutableStateOf(QuickInfoFocusTarget.TV_TABLE) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var lastCenterClickTime by remember { mutableLongStateOf(0L) }
    val doubleClickTimeout = 650L

    // Technical info
    var videoFormat by remember { mutableStateOf<Format?>(null) }
    var audioFormat by remember { mutableStateOf<Format?>(null) }
    
    val isSeries = media?.type == MediaType.SERIES
    val favorites by viewModel.favorites.collectAsState()

    fun String?.nonBlankValue(): String? = this?.takeIf { it.isNotBlank() }

    val currentMediaId = media?.id
    val matchingMovieInfo = viewModel.selectedMovieInfo?.takeIf { details ->
        media?.type == MediaType.MOVIE && details.movieData?.streamId == currentMediaId
    }
    val matchingSeriesInfo = viewModel.selectedSeriesInfo?.takeIf { details ->
        isSeries && details.info?.seriesId == currentMediaId
    }
    val currentEpisode = viewModel.playingEpisode.takeIf { isSeries }

    val currentEpisodeLabel = remember(media, matchingSeriesInfo, currentEpisode) {
        if (!isSeries || currentEpisode == null) null else {
            val seasonEntry = matchingSeriesInfo?.episodes?.entries?.firstOrNull { entry ->
                currentEpisode.id != null && entry.value.any { it.id == currentEpisode.id }
            }
            val episodeIndex = seasonEntry?.value?.indexOfFirst { it.id == currentEpisode.id } ?: -1
            val episodeNumber = currentEpisode.episodeNumber?.takeIf { it > 0 }
                ?: (episodeIndex + 1).takeIf { episodeIndex >= 0 }
            val seasonNumber = currentEpisode.seasonNumber ?: seasonEntry?.key?.toIntOrNull()
            val episodeTitle = currentEpisode.title.nonBlankValue()
            buildList {
                if (seasonNumber != null) add("Säsong $seasonNumber")
                if (episodeNumber != null) add("Avsnitt $episodeNumber")
                if (episodeTitle != null) add(episodeTitle)
            }.joinToString(" · ").takeIf { it.isNotBlank() }
        }
    }

    val movieDetails = matchingMovieInfo?.info
    val seriesDetails = matchingSeriesInfo?.info
    val presentationTitle = if (isSeries) {
        seriesDetails?.name.nonBlankValue() ?: media?.title.nonBlankValue()
    } else {
        matchingMovieInfo?.movieData?.name.nonBlankValue() ?: media?.title.nonBlankValue()
    }
    val presentationPoster = if (isSeries) {
        currentEpisode?.info?.icon.nonBlankValue()
            ?: seriesDetails?.cover.nonBlankValue()
            ?: media?.icon.nonBlankValue()
    } else {
        movieDetails?.movieImage.nonBlankValue() ?: media?.icon.nonBlankValue()
    }
    val presentationDescription = if (isSeries) {
        currentEpisode?.info?.plot.nonBlankValue()
            ?: seriesDetails?.plot.nonBlankValue()
            ?: media?.plot.nonBlankValue()
    } else {
        movieDetails?.plot.nonBlankValue() ?: media?.plot.nonBlankValue()
    }
    val presentationGenre = if (isSeries) {
        seriesDetails?.genre.nonBlankValue() ?: media?.genre.nonBlankValue()
    } else {
        movieDetails?.genre.nonBlankValue() ?: media?.genre.nonBlankValue()
    }
    val presentationRating = if (isSeries) {
        seriesDetails?.rating.nonBlankValue() ?: media?.rating.nonBlankValue()
    } else {
        movieDetails?.rating.nonBlankValue() ?: media?.rating.nonBlankValue()
    }
    val movieReleaseYear = if (isSeries) null else {
        movieDetails?.releaseDate.nonBlankValue()?.take(4)
            ?.takeIf { year -> year.length == 4 && year.all(Char::isDigit) }
    }
    val presentationMetadata = listOfNotNull(movieReleaseYear, presentationGenre)
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }

    // --- NEXT EPISODE LOGIC ---
    val nextEpisode = remember(media, viewModel.selectedSeriesInfo, viewModel.playingEpisode) {
        if (!isSeries || viewModel.selectedSeriesInfo?.episodes == null) {
            null
        } else {
            val episodesMap = viewModel.selectedSeriesInfo!!.episodes!!
            val allEpisodes = episodesMap.keys
                .sortedBy { it.toIntOrNull() ?: 0 }
                .flatMap { seasonKey ->
                    episodesMap[seasonKey] ?: emptyList()
                }
            
            val currentId = viewModel.playingEpisode?.id ?: media.id.toString()
            val currentIndex = allEpisodes.indexOfFirst { it.id == currentId }

            if (currentIndex != -1 && currentIndex < allEpisodes.size - 1) {
                allEpisodes[currentIndex + 1]
            } else null
        }
    }
    
    var showNextEpisodeButton by remember { mutableStateOf(false) }
    
    // Optimering: Använd derivedStateOf för att undvika onödiga omritningar i AnimatedVisibility
    val isQuickInfoVisible by remember { derivedStateOf { overlayState == OverlayState.QUICK_INFO } }
    val isNextEpisodeVisible by remember { derivedStateOf { showNextEpisodeButton && nextEpisode != null && overlayState == OverlayState.NONE } }
    val isSubtitlesVisible by remember { derivedStateOf { overlayState == OverlayState.SUBTITLES } }
    val isSideOverlayVisible by remember { derivedStateOf { overlayState == OverlayState.CHANNELS || overlayState == OverlayState.CATEGORIES } }

    val currentPlaybackId = remember(media, viewModel.playingEpisode) {
        if (isSeries && viewModel.playingEpisode != null) {
            viewModel.playingEpisode?.id ?: media.id.toString()
        } else {
            media?.id?.toString() ?: "0"
        }
    }

    // --- FEEDBACK STATES ---
    var showFavoriteFeedback by remember { mutableStateOf(false) }
    var favoriteMessage by remember { mutableStateOf("") }
    var favoriteJob by remember { mutableStateOf<Job?>(null) }

    // --- HELPER FUNCTIONS ---
    fun FocusRequester.safeFocus() {
        runCatching { this.requestFocus() }
    }

    fun performSeek(offsetMs: Long, isLongPress: Boolean = false) {
        seekJob?.cancel()
        accumulatedSeekMs += offsetMs
        
        val totalSecs = (accumulatedSeekMs.absoluteValue / 1000).toInt()
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        
        seekMessage = if (minutes > 0) {
            val sign = if (accumulatedSeekMs > 0) "+" else "-"
            "$sign$minutes:${String.format(Locale.getDefault(), "%02d", seconds)}"
        } else {
            val sign = if (accumulatedSeekMs > 0) "+" else "-"
            "$sign$seconds s"
        }
        
        vodControlsDismissed = false
        showSeekFeedback = true
        
        if (isLongPress) {
            val dur = exoPlayer.duration
            if (dur != C.TIME_UNSET) {
                val newPos = (exoPlayer.currentPosition + accumulatedSeekMs).coerceIn(0, dur)
                exoPlayer.seekTo(newPos)
                accumulatedSeekMs = 0
            }
        }
    }

    fun setPlayback(playing: Boolean) {
        vodControlsDismissed = false
        if (playing) {
            exoPlayer.play()
            isPlaying = true
        } else {
            exoPlayer.pause()
            isPlaying = false
        }
        showSeekFeedback = true
        seekJob?.cancel()
        if (playing) {
            seekJob = scope.launch { delay(3000); if (isPlaying) showSeekFeedback = false }
        }
    }

    fun performMediaSeek(offsetMs: Long) {
        performSeek(offsetMs, true)
        seekJob?.cancel()
        seekJob = scope.launch { delay(2500); showSeekFeedback = false }
    }

    fun togglePlayback() {
        setPlayback(!exoPlayer.isPlaying)
    }

    fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val totalSeconds = (ms / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun openTrackModal(state: OverlayState, returnFocus: QuickInfoFocusTarget) {
        val openedFromQuickInfo = isLiveStream && overlayState == OverlayState.QUICK_INFO
        trackModalReturnState = if (openedFromQuickInfo) OverlayState.QUICK_INFO else OverlayState.NONE
        if (openedFromQuickInfo) {
            quickInfoFocusTarget = returnFocus
            infoJob?.cancel()
        }
        overlayState = state
    }

    fun closeTrackModal() {
        val returnState = trackModalReturnState
        trackModalReturnState = OverlayState.NONE
        overlayState = returnState
    }

    // --- EFFECTS ---
    LaunchedEffect(viewModel.lastLiveCategoryIndex) {
        val currentCat = categories.getOrNull(viewModel.lastLiveCategoryIndex)
        if (currentCat != null && currentCat.items.isEmpty()) {
            viewModel.loadItemsForCategory(MediaType.LIVE, currentCat.categoryId)
        }
    }
    
    LaunchedEffect(categories, viewModel.lastLiveCategoryIndex) {
        val items = categories.getOrNull(viewModel.lastLiveCategoryIndex)?.items ?: emptyList()
        if (items.isNotEmpty() && viewModel.currentPlaylist.isEmpty()) {
            viewModel.currentPlaylist = items
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!viewModel.isInPipMode) {
                        exoPlayer.pause()
                        isPlaying = false
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    val activityIsBackgrounded =
                        activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false
                    if (!viewModel.isInPipMode && activityIsBackgrounded) {
                        if (isLiveStream) {
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                            isPlaying = false
                        }
                        onBackgrounded()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Spelaren återupptar om den var pausad av lifecycle
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(currentPosition, duration) {
        if (isSeries && nextEpisode != null && duration > 0) {
            val remainingSeconds = (duration - currentPosition) / 1000
            showNextEpisodeButton = remainingSeconds in 0..120
        } else {
            showNextEpisodeButton = false
        }
    }

    LaunchedEffect(exoPlayer, url) {
        var counter = 0
        while (true) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration
            if (isCatchupPlayback) {
                isCurrentMediaSeekable = exoPlayer.isCurrentMediaItemSeekable &&
                    exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
            if (media?.type != MediaType.LIVE && isPlaying) {
                counter++
                if (counter >= 30) {
                    sessionManager.savePlaybackPosition(currentPlaybackId, currentPosition, duration)
                    counter = 0
                }
            }
            delay(1000)
        }
    }

    LaunchedEffect(url) {
        isCurrentMediaSeekable = false
        availableSubtitles = emptyList()
        availableAudioTracks = emptyList()
        focusManager.clearFocus()
        if (media != null) {
            viewModel.addToHistory(media, if (isSeries) viewModel.playingEpisode else null)
            viewModel.updateThemeColorFromIcon(media.icon)
        }
        if (media?.type == MediaType.LIVE) {
            viewModel.setLiveCategoryByMediaId(media.id)
        }

        val mediaItem = MediaItem.Builder().setUri(url).build()
        
        // En liten delay hjälper vissa enheter att släppa den förra ytan (Surface)
        // innan vi förbereder nästa ström, vilket förhindrar svart bild.
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        delay(if (isLiveStream) 50 else 150)

        exoPlayer.setMediaItem(mediaItem)
        if (media?.type != MediaType.LIVE) {
            val savedPos = sessionManager.getPlaybackPosition(currentPlaybackId)
            if (savedPos > 0) exoPlayer.seekTo(savedPos)
        }
        exoPlayer.prepare()
        exoPlayer.play()
    }

    DisposableEffect(exoPlayer, url) {
        val listener = object : Player.Listener {
            private var compatibilityRetryAttempted = false

            fun logSeekCapabilities(event: String) {
                if (!isLiveStream) return

                val timeline = exoPlayer.currentTimeline
                val mediaItemIndex = exoPlayer.currentMediaItemIndex
                val window = if (
                    !timeline.isEmpty &&
                    mediaItemIndex != C.INDEX_UNSET &&
                    mediaItemIndex < timeline.windowCount
                ) {
                    timeline.getWindow(mediaItemIndex, Timeline.Window())
                } else {
                    null
                }

                Log.i(
                    "MMTV_SEEK_DIAG",
                    buildString {
                        append("event=").append(event)
                        append(" mediaType=").append(media?.type)
                        append(" isCatalogLive=").append(isLiveStream)
                        append(" mediaId=").append(media?.id)
                        append(" urlHash=").append(Integer.toHexString(url.hashCode()))
                        append(" playerIsLive=").append(exoPlayer.isCurrentMediaItemLive)
                        append(" playerIsSeekable=").append(exoPlayer.isCurrentMediaItemSeekable)
                        append(" seekCommandAvailable=").append(
                            exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                        )
                        append(" duration=").append(exoPlayer.duration)
                        append(" currentPosition=").append(exoPlayer.currentPosition)
                        append(" currentLiveOffset=").append(exoPlayer.currentLiveOffset)
                        append(" timelineIsEmpty=").append(timeline.isEmpty)
                        if (window != null) {
                            append(" windowIsLive=").append(window.isLive)
                            append(" windowIsSeekable=").append(window.isSeekable)
                            append(" windowIsDynamic=").append(window.isDynamic)
                            append(" windowDurationMs=").append(window.durationMs)
                            append(" windowDefaultPositionMs=").append(window.defaultPositionMs)
                            append(" windowStartTimeMs=").append(window.windowStartTimeMs)
                        }
                    }
                )
            }

            fun clearCompatibilityRecovery() {
                viewModel.disableVodStereoDownmix()
                compatibilityRetryAttempted = false
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                clearCompatibilityRecovery()
            }

            override fun onTracksChanged(tracks: Tracks) {
                availableSubtitles = tracks.groups.filter { group ->
                    group.type == C.TRACK_TYPE_TEXT &&
                        (0 until group.length).any { trackIndex -> group.isTrackSupported(trackIndex) }
                }
                availableAudioTracks = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMap { group ->
                        (0 until group.length)
                            .filter { trackIndex -> group.isTrackSupported(trackIndex) }
                            .map { trackIndex ->
                                AudioTrackOption(
                                    group = group,
                                    trackIndex = trackIndex,
                                    format = group.getTrackFormat(trackIndex)
                                )
                            }
                    }
                videoFormat = exoPlayer.videoFormat
                audioFormat = exoPlayer.audioFormat
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                isCurrentMediaSeekable = exoPlayer.isCurrentMediaItemSeekable &&
                    exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                logSeekCapabilities("onTimelineChanged(reason=$reason)")
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                isCurrentMediaSeekable = exoPlayer.isCurrentMediaItemSeekable &&
                    availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                logSeekCapabilities("onAvailableCommandsChanged")
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isLiveStream || compatibilityRetryAttempted ||
                    error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                ) return

                val sinkError = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<AudioSink.InitializationException>()
                    .firstOrNull() ?: return
                val failedChannelCount = sinkError.format.channelCount
                if (failedChannelCount <= 2) return

                val mediaItemIndex = exoPlayer.currentMediaItemIndex
                if (mediaItemIndex == C.INDEX_UNSET) return
                val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                val shouldPlay = exoPlayer.playWhenReady
                if (!viewModel.enableVodStereoDownmix(failedChannelCount)) return

                // Mark the attempt first so a failing stereo output cannot start another retry.
                compatibilityRetryAttempted = true
                exoPlayer.seekTo(mediaItemIndex, position)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = shouldPlay
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                videoFormat = exoPlayer.videoFormat
                audioFormat = exoPlayer.audioFormat
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isCurrentMediaSeekable = exoPlayer.isCurrentMediaItemSeekable &&
                    exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                logSeekCapabilities("onPlaybackStateChanged(state=$playbackState)")
                isBuffering = playbackState == Player.STATE_BUFFERING
                videoFormat = exoPlayer.videoFormat
                audioFormat = exoPlayer.audioFormat
                
                if (playbackState == Player.STATE_READY) {
                    // Ready
                }

                if (playbackState == Player.STATE_ENDED) {
                    if (isSeries && nextEpisode != null && sessionManager.getAutoPlayNext()) {
                        onPlayNextEpisode(nextEpisode)
                    } else {
                        onBackPressed()
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            listener.clearCompatibilityRecovery()
            exoPlayer.removeListener(listener)
            if (media != null && media.type != MediaType.LIVE) {
                val currentPos = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                if (currentPos > 10000 && (dur == C.TIME_UNSET || currentPos < dur - 5000)) {
                    sessionManager.savePlaybackPosition(currentPlaybackId, currentPos, dur)
                } else if (dur != C.TIME_UNSET && currentPos >= dur - 5000) {
                    sessionManager.clearPlaybackPosition(currentPlaybackId)
                }
            }
            // Vi stoppar inte längre spelaren här eftersom den ägs av ViewModel
            // och kan behövas för PiP eller snabba kanalbyten.
        }
    }

    LaunchedEffect(overlayState) {
        when (overlayState) {
            OverlayState.CATEGORIES -> Unit
            OverlayState.CHANNELS -> Unit
            OverlayState.QUICK_INFO -> {
                resetAutoHideTimer()
                scope.launch {
                    delay(60)
                    when (quickInfoFocusTarget) {
                        QuickInfoFocusTarget.TV_TABLE -> tvGuideFocusRequester.safeFocus()
                        QuickInfoFocusTarget.SUBTITLES -> {
                            if (availableSubtitles.isNotEmpty()) quickInfoSubtitleFocusRequester.safeFocus()
                            else tvGuideFocusRequester.safeFocus()
                        }
                        QuickInfoFocusTarget.AUDIO_TRACKS -> {
                            if (availableAudioTracks.size > 1) quickInfoAudioFocusRequester.safeFocus()
                            else tvGuideFocusRequester.safeFocus()
                        }
                    }
                }
            }
            OverlayState.SUBTITLES -> {
                delay(60)
                subtitleFocusRequesters[0]?.safeFocus()
            }
            OverlayState.AUDIO_TRACKS -> {
                delay(60)
                audioTrackFocusRequesters[0]?.safeFocus()
            }
            OverlayState.EPG_INFO -> {
                // EPG-modalens innehåll läser enbart den redan förberedda cachen.
            }
            OverlayState.FULL_EPG -> {
                // EpgGrid hanterar sitt eget fokus internt
            }
            OverlayState.FAVORITE_TIMELINE -> {
                // FavoriteEpgTimeline owns focus and D-pad navigation while mounted.
            }
            else -> { 
                delay(50)
                mainFocusRequester.safeFocus() 
            }
        }
    }

    // --- RENDER ---
    if (viewModel.isInPipMode) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { 
                    useController = false
                    player = exoPlayer
                    keepScreenOn = true
                    // Anpassa undertexternas utseende och tvinga dem att använda systemets inställningar
                    // vilket ofta löser problem med teckenkodning och saknade glyphs på Android TV.
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setUserDefaultStyle()
                        setUserDefaultTextSize()
                        setBottomPaddingFraction(0.1f)
                        // Tvinga CANVAS-rendering istället för WEB (som är standard i Media3)
                        // WEB-motorn är strikt med UTF-8, medan CANVAS är mer förlåtande.
                        setViewType(androidx.media3.ui.SubtitleView.VIEW_TYPE_CANVAS)
                    }
                } 
            },
            update = { view -> 
                if (view.player != exoPlayer) view.player = exoPlayer
                view.onResume()
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        var dragOffsetY by remember { mutableStateOf(0f) }
        var dragOffsetX by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    if (!viewModel.isTvMode) {
                        detectTapGestures(
                            onTap = {
                                if (overlayState != OverlayState.NONE) {
                                    overlayState = OverlayState.NONE
                                } else {
                                    if (media?.type == MediaType.LIVE) {
                                        quickInfoFocusTarget = QuickInfoFocusTarget.TV_TABLE
                                        overlayState = OverlayState.QUICK_INFO
                                    } else {
                                        showSeekFeedback = !showSeekFeedback
                                        if (showSeekFeedback) {
                                            vodControlsDismissed = false
                                            seekJob?.cancel()
                                            seekJob = scope.launch { delay(5000); showSeekFeedback = false }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
                .pointerInput(Unit) {
                    if (!viewModel.isTvMode) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (media?.type == MediaType.LIVE && playlist.isNotEmpty()) {
                                    val currentIndex = playlist.indexOfFirst { it.id == media.id }
                                    if (dragOffsetY > 100) { // Swipe Down -> Previous
                                        val prevIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1
                                        onMediaSelected(playlist[prevIndex])
                                    } else if (dragOffsetY < -100) { // Swipe Up -> Next
                                        val nextIndex = if (currentIndex < playlist.size - 1) currentIndex + 1 else 0
                                        onMediaSelected(playlist[nextIndex])
                                    }
                                }
                                dragOffsetY = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount
                            }
                        )
                    }
                }
                .pointerInput(Unit) {
                    if (!viewModel.isTvMode) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (media?.type != MediaType.LIVE) {
                                    if (dragOffsetX > 100) { // Swipe Right -> Forward
                                        performSeek(30000L)
                                    } else if (dragOffsetX < -100) { // Swipe Left -> Backward
                                        performSeek(-30000L)
                                    }
                                } else {
                                    // Live TV: Swipe Right to open categories/channels
                                    if (dragOffsetX > 100) {
                                        overlayState = OverlayState.CATEGORIES
                                    } else if (dragOffsetX < -100) {
                                        // Swipe Left to maybe close or show subtitles
                                        if (overlayState == OverlayState.NONE) {
                                            overlayState = OverlayState.SUBTITLES
                                        } else {
                                            overlayState = OverlayState.NONE
                                        }
                                    }
                                }
                                dragOffsetX = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount
                            }
                        )
                    }
                }
                .onKeyEvent { keyEvent ->
                // ... (Key handling logic)
                val nativeEvent = keyEvent.nativeKeyEvent
                if (overlayState == OverlayState.FAVORITE_TIMELINE) {
                    return@onKeyEvent false
                }
                when (nativeEvent.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (overlayState == OverlayState.QUICK_INFO) resetAutoHideTimer()
                        val isRepeat = nativeEvent.repeatCount > 0
                        if (isRepeat) isLongPressSeeking = true

                        when (nativeEvent.keyCode) {
                            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                                if (media?.type == MediaType.LIVE && overlayState == OverlayState.NONE) {
                                    val digit = (nativeEvent.keyCode - KeyEvent.KEYCODE_0).toString()
                                    channelNumberBuffer += digit
                                    channelNumberJob?.cancel()
                                    channelNumberJob = scope.launch {
                                        delay(1300)
                                        commitChannelNumber()
                                    }
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                togglePlayback()
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                setPlayback(true)
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                setPlayback(false)
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                val canSeek = isTimelinePlayback && isCurrentMediaSeekable
                                if (canSeek) performMediaSeek(-10000L)
                                canSeek
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                val canSeek = isTimelinePlayback && isCurrentMediaSeekable
                                if (canSeek) performMediaSeek(10000L)
                                canSeek
                            }
                            KeyEvent.KEYCODE_CAPTIONS -> {
                                openTrackModal(OverlayState.SUBTITLES, QuickInfoFocusTarget.SUBTITLES)
                                true
                            }
                            KeyEvent.KEYCODE_GUIDE -> {
                                overlayState = OverlayState.EPG_INFO
                                true
                            }
                            KeyEvent.KEYCODE_INFO -> {
                                quickInfoFocusTarget = QuickInfoFocusTarget.TV_TABLE
                                overlayState = OverlayState.QUICK_INFO
                                true
                            }
                            KeyEvent.KEYCODE_MENU -> {
                                if (media?.type == MediaType.LIVE) {
                                    overlayState = OverlayState.CHANNELS
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                            when (overlayState) {
                                OverlayState.QUICK_INFO -> true
                                OverlayState.NONE -> {
                                    if (showNextEpisodeButton) {
                                        nextEpisodeButtonFocusRequester.safeFocus()
                                        true
                                    } else if (isActualLiveRoute) {
                                        // Den kompakta tablåvyn är trygg på TV:ns begränsade CPU.
                                        overlayState = OverlayState.EPG_INFO
                                        true
                                    } else {
                                        if (!showSeekFeedback) {
                                            vodControlsDismissed = false
                                            showSeekFeedback = true
                                            seekJob?.cancel()
                                            seekJob = scope.launch { delay(5000); showSeekFeedback = false }
                                        }
                                        scope.launch {
                                            delay(60)
                                            timelineFocusRequester.safeFocus()
                                        }
                                        true
                                    }
                                }
                                else -> false
                            }
                        }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (overlayState == OverlayState.QUICK_INFO) {
                                    true
                                } else if (overlayState == OverlayState.NONE) {
                                    if (isActualLiveRoute) {
                                        overlayState = OverlayState.EPG_INFO
                                        true
                                    } else {
                                        false
                                    }
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (overlayState == OverlayState.NONE) {
                                    if (isActualLiveRoute) {
                                        // SideOverlay positionerar och fokuserar den aktuella kanalen
                                        // efter att rätt rad faktiskt har komponerats.
                                        overlayState = OverlayState.CHANNELS
                                    } else {
                                        if (isRepeat) performSeek(-10000L, true)
                                        else performSeek(-10000L)
                                    }
                                    true
                                } else if (overlayState == OverlayState.CHANNELS) {
                                    overlayState = OverlayState.CATEGORIES
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (overlayState == OverlayState.NONE) {
                                    if (isTimelinePlayback) {
                                        if (isRepeat) performSeek(10000L, true)
                                        else performSeek(10000L)
                                    } else {
                                        viewModel.prepareFavoriteTimeline()
                                        overlayState = OverlayState.FAVORITE_TIMELINE
                                    }
                                    true
                                } else if (overlayState == OverlayState.CATEGORIES) {
                                    overlayState = OverlayState.CHANNELS
                                    true
                                } else if (overlayState == OverlayState.CHANNELS) {
                                    overlayState = OverlayState.NONE
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                val overlayBefore = overlayState
                                val playerSeekable = exoPlayer.isCurrentMediaItemSeekable
                                val seekCommandAvailable = exoPlayer.isCommandAvailable(
                                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                                )
                                val routeClassification = when {
                                    isCatchupPlayback -> "TIMESHIFT"
                                    isActualLiveRoute -> "LIVE"
                                    media?.type == MediaType.MOVIE || media?.type == MediaType.SERIES -> "VOD"
                                    else -> "UNKNOWN"
                                }
                                var overlayDecision = "OTHER"
                                var centerAction = "none"

                                val consumed = if (channelNumberBuffer.isNotEmpty() && media?.type == MediaType.LIVE && overlayState == OverlayState.NONE) {
                                    overlayDecision = "OTHER"
                                    centerAction = "commit_channel_number"
                                    commitChannelNumber()
                                    true
                                } else if (showNextEpisodeButton && nextEpisode != null && overlayState == OverlayState.NONE) {
                                    overlayDecision = "OTHER"
                                    centerAction = "play_next_episode"
                                    onPlayNextEpisode(nextEpisode)
                                    true
                                } else if (overlayState == OverlayState.NONE) {
                                    if (isActualLiveRoute) {
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastCenterClickTime < doubleClickTimeout) {
                                            overlayState = OverlayState.EPG_INFO
                                            overlayDecision = "OTHER"
                                        } else {
                                            quickInfoFocusTarget = QuickInfoFocusTarget.TV_TABLE
                                            overlayState = OverlayState.QUICK_INFO
                                            overlayDecision = "QUICK_INFO"
                                        }
                                        lastCenterClickTime = currentTime
                                        centerAction = "overlay_only"
                                        true
                                    } else if (isTimelinePlayback) {
                                        overlayDecision = if (isCatchupPlayback) "CATCHUP_CONTROLS" else "OTHER"
                                        centerAction = "toggle_playback"
                                        isCurrentMediaSeekable = playerSeekable && seekCommandAvailable
                                        togglePlayback()
                                        scope.launch {
                                            delay(60)
                                            if (isCurrentMediaSeekable) timelineFocusRequester.safeFocus()
                                        }
                                        true
                                    } else false
                                } else false

                                Log.i(
                                    "MMTV_CATCHUP_UI",
                                    "route=$routeClassification isCatchUpRoute=$isCatchupPlayback " +
                                        "mediaType=${media?.type} seekable=$playerSeekable " +
                                        "seekCommandAvailable=$seekCommandAvailable durationMs=${exoPlayer.duration} " +
                                        "decision=$overlayDecision overlayBefore=$overlayBefore " +
                                        "overlayAfter=$overlayState consumed=$consumed action=$centerAction"
                                )
                                consumed
                            }
                            KeyEvent.KEYCODE_PROG_RED -> {
                                if (media != null) {
                                    val isFav = favorites.any { it.id == media.id }
                                    viewModel.toggleFavorite(media)
                                    favoriteMessage = if (isFav) "Borttagen från favoriter" else "Tillagd i favoriter"
                                    showFavoriteFeedback = true
                                    favoriteJob?.cancel()
                                    favoriteJob = scope.launch { delay(3000); showFavoriteFeedback = false }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_M -> {
                                overlayState = OverlayState.EPG_INFO
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                when {
                                    channelNumberBuffer.isNotEmpty() && overlayState == OverlayState.NONE -> {
                                        channelNumberJob?.cancel()
                                        channelNumberJob = null
                                        channelNumberBuffer = ""
                                    }
                                    overlayState == OverlayState.NONE -> onBackPressed()
                                    overlayState == OverlayState.SUBTITLES || overlayState == OverlayState.AUDIO_TRACKS -> closeTrackModal()
                                    else -> overlayState = OverlayState.NONE
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        val keyCode = nativeEvent.keyCode
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (isLongPressSeeking) {
                                isLongPressSeeking = false
                                // Vid long-press seekar vi direkt i ACTION_DOWN, så vi nollställer bara här
                                accumulatedSeekMs = 0
                            } else if (overlayState == OverlayState.NONE && isTimelinePlayback) {
                                // För enkla klick, utför sökningen nu när knappen släpps
                                val dur = exoPlayer.duration
                                if (dur != C.TIME_UNSET) {
                                    val newPos = (exoPlayer.currentPosition + accumulatedSeekMs).coerceIn(0, dur)
                                    exoPlayer.seekTo(newPos)
                                }
                                accumulatedSeekMs = 0
                            }

                            seekJob?.cancel()
                            seekJob = scope.launch { delay(2500); showSeekFeedback = false }
                        }
                        false
                    }
                    else -> false
                }
            }
            .focusRequester(mainFocusRequester)
            .focusable()
    ) {
        val forcedVideoAspectRatio = videoResizeMode.forcedAspectRatio
            .takeIf { media != null && media.type != MediaType.LIVE && !isActualLiveRoute }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { 
                    useController = false
                    player = exoPlayer
                    keepScreenOn = true
                    // Anpassa undertexternas utseende och tvinga dem att använda systemets inställningar
                    // vilket ofta löser problem med teckenkodning och saknade glyphs på Android TV.
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setUserDefaultStyle()
                        setUserDefaultTextSize()
                        setBottomPaddingFraction(0.1f)
                        // Tvinga CANVAS-rendering istället för WEB (som är standard i Media3)
                        // WEB-motorn är strikt med UTF-8, medan CANVAS är mer förlåtande.
                        setViewType(androidx.media3.ui.SubtitleView.VIEW_TYPE_CANVAS)
                    }
                } 
            },
            update = { view -> 
                if (view.player != exoPlayer) view.player = exoPlayer
                val resizeMode = if (media != null && media.type != MediaType.LIVE && !isActualLiveRoute) {
                    videoResizeMode.playerViewResizeMode
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
                view.onResume()
            },
            modifier = forcedVideoAspectRatio?.let { aspectRatio ->
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .align(Alignment.Center)
            } ?: Modifier.fillMaxSize()
        )

        if (detailsVisible) {
            val format = detailVideoFormat
            val resolution = if (format != null && format.width > 0 && format.height > 0) {
                "${videoQualityLabel(format.width, format.height)} · ${format.width}×${format.height}"
            } else "Upplösning —"
            val sourceFps = format?.frameRate?.takeIf { it > 0 && it.isFinite() }
            val fps = (sourceFps ?: measuredFps)?.let {
                String.format(Locale.getDefault(), "%.2f", it).trimEnd('0').trimEnd('.', ',')
            }
            val fpsText = fps?.let { " · ${if (sourceFps == null) "≈ " else ""}$it FPS" } ?: ""
            val network = networkBitrate?.let {
                String.format(Locale.getDefault(), "≈ %.1f Mbit/s", it / 1_000_000.0)
            } ?: "—"
            Surface(
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = if (viewModel.isTvMode) 16.dp else 88.dp),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "$resolution$fpsText\nNät $network",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        // --- PHONE MODE CAST BUTTON ---
        if (!viewModel.isTvMode) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                CastButton(modifier = Modifier.size(40.dp).align(Alignment.TopEnd))
            }
        }

        // --- VOD CONTROL OVERLAY ---
        AnimatedVisibility(
            visible = showVodControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.fillMaxSize()
        ) {
            VodControlOverlay(
                title = presentationTitle,
                poster = presentationPoster,
                rating = presentationRating,
                metadata = presentationMetadata,
                description = presentationDescription,
                currentEpisodeLabel = currentEpisodeLabel,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                accumulatedSeekMs = accumulatedSeekMs,
                seekMessage = seekMessage,
                availableSubtitles = availableSubtitles,
                timelineFocusRequester = timelineFocusRequester,
                subtitleIconFocusRequester = subtitleIconFocusRequester,
                audioIconFocusRequester = audioIconFocusRequester,
                videoResizeModeLabel = videoResizeMode.label,
                isTvMode = viewModel.isTvMode,
                themeColor = viewModel.currentThemeColor,
                onPlayNext = if (isSeries && nextEpisode != null) {
                    { onPlayNextEpisode(nextEpisode) }
                } else null,
                onReturnToLive = if (isCatchupPlayback) onReturnToLive else null,
                onCycleVideoResizeMode = { videoResizeMode = videoResizeMode.next() },
                onSeekBy = { offsetMs ->
                    if (duration > 0 && duration != C.TIME_UNSET) {
                        val target = (currentPosition + offsetMs).coerceIn(0L, duration)
                        exoPlayer.seekTo(target)
                        currentPosition = target
                    }
                },
                onContinueWatching = {
                    if (!exoPlayer.isPlaying) {
                        exoPlayer.play()
                    }
                    isPlaying = true
                    vodControlsDismissed = true
                    showSeekFeedback = false
                    mainFocusRequester.safeFocus()
                },
                onToggleSubtitles = {
                    openTrackModal(OverlayState.SUBTITLES, QuickInfoFocusTarget.SUBTITLES)
                },
                onToggleAudioTracks = {
                    openTrackModal(OverlayState.AUDIO_TRACKS, QuickInfoFocusTarget.AUDIO_TRACKS)
                }
            )
        }

        if (isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = viewModel.currentThemeColor, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
            }
        }

        AnimatedVisibility(
            visible = channelNumberBuffer.isNotEmpty() || channelNumberFeedback,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
            ) {
                Text(
                    text = if (channelNumberFeedback) "Kanal ej tillgänglig" else channelNumberBuffer,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    color = Color.White,
                    fontSize = if (channelNumberFeedback) 16.sp else 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- FAVORITE FEEDBACK ---
        AnimatedVisibility(
            visible = showFavoriteFeedback,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Favorite, null, tint = if (favoriteMessage.contains("Tillagd")) Color.Red else Color.Gray, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = favoriteMessage, style = MaterialTheme.typography.bodyLarge, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- NEXT EPISODE BUTTON ---
        AnimatedVisibility(
            visible = isNextEpisodeVisible,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 48.dp, end = 48.dp)
        ) {
            var isFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { 
                    nextEpisode?.let { 
                        showNextEpisodeButton = false
                        onPlayNextEpisode(it) 
                    } 
                },
                modifier = Modifier
                    .width(360.dp)
                    .height(110.dp)
                    .focusRequester(nextEpisodeButtonFocusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                shape = RoundedCornerShape(16.dp),
                color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.85f),
                contentColor = if (isFocused) Color.Black else Color.White,
                border = if (isFocused) androidx.compose.foundation.BorderStroke(3.dp, FocusBorderColor) else androidx.compose.foundation.BorderStroke(2.dp, viewModel.currentThemeColor.copy(alpha = 0.5f)),
                tonalElevation = 12.dp
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(86.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = media?.icon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (media?.icon == null) {
                            Icon(Icons.Default.Movie, null, tint = Color.Gray, modifier = Modifier.padding(16.dp))
                        }
                        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp).align(Alignment.Center))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "SE NÄSTA AVSNITT", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 1.2.sp), color = if (isFocused) Color.Black.copy(alpha = 0.7f) else viewModel.currentThemeColor)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = nextEpisode?.title ?: "Nästa avsnitt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val remainingSecs = ((duration - currentPosition) / 1000).coerceAtLeast(0)
                        Text(text = "Avslutas om $remainingSecs s", style = MaterialTheme.typography.bodySmall, color = if (isFocused) Color.Black.copy(alpha = 0.5f) else Color.Gray)
                    }
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, null, modifier = Modifier.size(32.dp), tint = if (isFocused) Color.Black else viewModel.currentThemeColor)
                }
            }
            
            LaunchedEffect(showNextEpisodeButton) {
                if (showNextEpisodeButton) {
                    delay(2500)
                    if (showNextEpisodeButton && overlayState == OverlayState.NONE) {
                        nextEpisodeButtonFocusRequester.safeFocus()
                    }
                }
            }
        }

        // --- OVERLAYS ---
        if (isQuickInfoVisible && media != null) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                QuickInfoOverlay(
                    media = media,
                    viewModel = viewModel,
                    categories = categories,
                    tvGuideFocusRequester = tvGuideFocusRequester,
                    favoriteButtonFocusRequester = favoriteButtonFocusRequester,
                    subtitleFocusRequester = quickInfoSubtitleFocusRequester,
                    audioFocusRequester = quickInfoAudioFocusRequester,
                    recentChannelsFocusRequesters = recentChannelsFocusRequesters,
                    videoFormat = videoFormat,
                    audioFormat = audioFormat,
                    favorites = favorites,
                    showSubtitles = availableSubtitles.isNotEmpty(),
                    showAudioTracks = availableAudioTracks.size > 1,
                    onTvGuideClick = {
                        infoJob?.cancel()
                        overlayState = OverlayState.EPG_INFO
                    },
                    onRecentChannelClick = { item ->
                        overlayState = OverlayState.NONE
                        onMediaSelected(item)
                    },
                    onCategoryRequest = { overlayState = OverlayState.CATEGORIES },
                    onCloseRequest = { overlayState = OverlayState.NONE },
                    onSubtitlesClick = {
                        openTrackModal(OverlayState.SUBTITLES, QuickInfoFocusTarget.SUBTITLES)
                    },
                    onAudioTracksClick = {
                        openTrackModal(OverlayState.AUDIO_TRACKS, QuickInfoFocusTarget.AUDIO_TRACKS)
                    },
                    onFocusAction = { infoJob?.cancel() },
                    onBlurAction = { resetAutoHideTimer() }
                )
            }
        }

        SideOverlay(
            isVisible = isSideOverlayVisible,
            overlayState = overlayState.name,
            categories = categories,
            playlist = playlist,
            viewModel = viewModel,
            initialFocusedChannel = media,
            categoryListState = categoryListState,
            channelListState = channelListState,
            categoryFocusRequesters = categoryFocusRequesters,
            channelFocusRequesters = channelFocusRequesters,
            onCategorySelected = onCategorySelected,
            onMediaSelected = { selectedChannel ->
                overlayState = OverlayState.NONE
                // Samma kanal spelar redan. Att navigera igen skulle i onödan köra
                // stop/clear/prepare och ge en ny buffring innan bilden kommer tillbaka.
                if (selectedChannel.id != media?.id || selectedChannel.type != media?.type) {
                    onMediaSelected(selectedChannel)
                }
            },
            onOverlayStateChange = { overlayState = OverlayState.valueOf(it) },
            onDismiss = { overlayState = OverlayState.NONE }
        )

        if (overlayState == OverlayState.EPG_INFO && media != null) {
            EpgModal(
                media = media,
                viewModel = viewModel,
                epgListState = epgListState,
                epgFocusRequester = epgFocusRequester,
                onClose = { overlayState = OverlayState.NONE },
                onPlayArchive = { listing ->
                    overlayState = OverlayState.NONE
                    onArchiveSelected(listing)
                }
            )
        }

        if (overlayState == OverlayState.FULL_EPG) {
            EpgGrid(
                channels = playlist,
                viewModel = viewModel,
                onChannelSelected = { 
                    overlayState = OverlayState.NONE
                    onMediaSelected(it) 
                },
                onClose = { overlayState = OverlayState.NONE }
            )
        }

        if (overlayState == OverlayState.FAVORITE_TIMELINE && isLiveStream) {
            val timelineState by viewModel.favoriteTimelineState.collectAsState()
            val initialTimelineChannelId = media?.id?.takeIf { currentId ->
                favorites.any { it.type == MediaType.LIVE && it.id == currentId }
            }
            FavoriteEpgTimeline(
                state = timelineState,
                initialChannelId = initialTimelineChannelId,
                onChannelSelected = { selectedChannel ->
                    overlayState = OverlayState.NONE
                    if (selectedChannel.id != media?.id || selectedChannel.type != media?.type) {
                        onMediaSelected(selectedChannel)
                    }
                },
                onDismiss = { overlayState = OverlayState.NONE }
            )
        }

        // --- SUBTITLES ---
        AnimatedVisibility(
            visible = isSubtitlesVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Card(
                modifier = Modifier.width(300.dp).wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Undertexter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    if (availableSubtitles.isEmpty()) Text("Inga undertexter tillgängliga", color = Color.Gray)
                    else {
                        LazyColumn(state = subtitleListState) {
                            item {
                                SubtitleOptionItem(
                                    label = "Ingen undertext",
                                    isSelected = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
                                    modifier = Modifier.focusRequester(subtitleFocusRequesters.getOrPut(0) { FocusRequester() }),
                                    onClick = {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                        closeTrackModal()
                                    }
                                )
                            }
                            itemsIndexed(availableSubtitles, key = { index, _ -> index }) { index, group ->
                                val trackIndex = (0 until group.length).first { group.isTrackSupported(it) }
                                val trackName = group.getTrackFormat(trackIndex).language ?: "Spår ${index + 1}"
                                SubtitleOptionItem(
                                    label = trackName.uppercase(),
                                    isSelected = exoPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) &&
                                        group.isTrackSelected(trackIndex),
                                    modifier = Modifier.focusRequester(subtitleFocusRequesters.getOrPut(index + 1) { FocusRequester() }),
                                    onClick = {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex)).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                                        closeTrackModal()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = overlayState == OverlayState.AUDIO_TRACKS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Card(
                modifier = Modifier.width(360.dp).wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Ljudspråk", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    LazyColumn(state = audioTrackListState) {
                        item {
                            SubtitleOptionItem(
                                label = "AUTO",
                                isSelected = exoPlayer.trackSelectionParameters.overrides.keys.none {
                                    it.type == C.TRACK_TYPE_AUDIO
                                },
                                modifier = Modifier.focusRequester(audioTrackFocusRequesters.getOrPut(0) { FocusRequester() }),
                                onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                        .build()
                                    closeTrackModal()
                                }
                            )
                        }
                        itemsIndexed(
                            items = availableAudioTracks,
                            key = { index, option -> "${option.group.mediaTrackGroup.id}_${option.trackIndex}_$index" }
                        ) { index, option ->
                            SubtitleOptionItem(
                                label = option.displayName(index),
                                isSelected = option.group.isTrackSelected(option.trackIndex),
                                modifier = Modifier.focusRequester(
                                    audioTrackFocusRequesters.getOrPut(index + 1) { FocusRequester() }
                                ),
                                onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(
                                            TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex)
                                        )
                                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                        .build()
                                    closeTrackModal()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun VodControlOverlay(
    title: String?,
    poster: String?,
    rating: String?,
    metadata: String?,
    description: String?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    accumulatedSeekMs: Long,
    seekMessage: String,
    availableSubtitles: List<Tracks.Group>,
    timelineFocusRequester: FocusRequester,
    subtitleIconFocusRequester: FocusRequester,
    audioIconFocusRequester: FocusRequester,
    videoResizeModeLabel: String,
    isTvMode: Boolean = true,
    themeColor: Color = Color(0xFF2196F3),
    currentEpisodeLabel: String? = null,
    onPlayNext: (() -> Unit)? = null,
    onReturnToLive: (() -> Unit)? = null,
    onCycleVideoResizeMode: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onContinueWatching: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onToggleAudioTracks: () -> Unit
) {
    val resizeModeFocusRequester = remember { FocusRequester() }
    val continueWatchingFocusRequester = remember { FocusRequester() }
    val nextControlFocusRequester = remember { FocusRequester() }
    val returnToLiveFocusRequester = remember { FocusRequester() }
    val controlTextStyle = MaterialTheme.typography.labelLarge.copy(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // --- TOP INFO ---
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            color = Color.Black.copy(alpha = 0.85f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Movie Poster (Mini)
                Card(
                    modifier = Modifier.size(80.dp, 120.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF202020)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (poster == null) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF202020)))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.orEmpty(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (currentEpisodeLabel != null) {
                        Text(
                            text = currentEpisodeLabel,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val resolvedRating = rating
                        if (!resolvedRating.isNullOrBlank() && resolvedRating != "0.0") {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = resolvedRating, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        
                        if (!metadata.isNullOrBlank()) {
                            Text(text = metadata, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = description ?: "Ingen beskrivning tillgänglig.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 24.sp
            )
        }
        }

        // --- CENTER STATE ICON ---
        Box(modifier = Modifier.align(Alignment.Center)) {
            if (accumulatedSeekMs != 0L) {
                Text(
                    text = seekMessage,
                    style = MaterialTheme.typography.displayMedium,
                    color = AccentColor,
                    fontWeight = FontWeight.Black
                )
            } else if (!isPlaying) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF141414),
                    modifier = Modifier.size(100.dp)
                ) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(20.dp).fillMaxSize()
                    )
                }
            }
        }

        // --- BOTTOM CONTROLS ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            fun formatTimelineTime(ms: Long): String {
                val totalSeconds = (ms.coerceAtLeast(0L) / 1000).toInt()
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            }

            val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
            var timelineFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .focusRequester(timelineFocusRequester)
                    .onFocusChanged { timelineFocused = it.isFocused }
                    .onPreviewKeyEvent {
                        when (it.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onSeekBy(-10_000L)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onSeekBy(10_000L)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) continueWatchingFocusRequester.requestFocus()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
                            KeyEvent.KEYCODE_DPAD_UP -> true
                            else -> false
                        }
                    },
                shape = RoundedCornerShape(8.dp),
                border = if (timelineFocused) androidx.compose.foundation.BorderStroke(3.dp, AccentColor) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                color = Color.Black.copy(alpha = 0.85f),
                contentColor = Color.White
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = "${formatTimelineTime(currentPosition)} / ${formatTimelineTime(duration)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF303030))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(AccentColor)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            var continueWatchingFocused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onContinueWatching,
                    modifier = Modifier
                        .width(176.dp)
                        .height(58.dp)
                        .focusRequester(continueWatchingFocusRequester)
                        .onFocusChanged { continueWatchingFocused = it.isFocused }
                        .onPreviewKeyEvent {
                            when (it.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) timelineFocusRequester.requestFocus()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> true
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) subtitleIconFocusRequester.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        },
                    shape = RoundedCornerShape(8.dp),
                    border = if (continueWatchingFocused) androidx.compose.foundation.BorderStroke(3.dp, AccentColor) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                    color = Color.Black.copy(alpha = 0.85f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "FORTSÄTT TITTA",
                            style = controlTextStyle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                    if (!isTvMode) {
                        CastButton(modifier = Modifier.size(40.dp).padding(end = 16.dp))
                    }

                    // Subtitles Button
                    var isSubFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onToggleSubtitles,
                    modifier = Modifier
                        .width(156.dp)
                        .height(58.dp)
                        .focusRequester(subtitleIconFocusRequester)
                        .onFocusChanged { isSubFocused = it.isFocused }
                        .onPreviewKeyEvent {
                            when (it.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) timelineFocusRequester.requestFocus()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> true
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) continueWatchingFocusRequester.requestFocus()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) audioIconFocusRequester.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        },
                    shape = RoundedCornerShape(8.dp),
                    border = if (isSubFocused) androidx.compose.foundation.BorderStroke(3.dp, AccentColor) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                    color = Color.Black.copy(alpha = 0.85f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Subtitles, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "UNDERTEXTER",
                            style = controlTextStyle,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                    Spacer(modifier = Modifier.width(8.dp))
                    var isAudioFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onToggleAudioTracks,
                        modifier = Modifier
                            .width(144.dp)
                            .height(58.dp)
                            .focusRequester(audioIconFocusRequester)
                            .onFocusChanged { isAudioFocused = it.isFocused }
                            .onPreviewKeyEvent {
                                when (it.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) timelineFocusRequester.requestFocus()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> true
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) subtitleIconFocusRequester.requestFocus()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            if (onReturnToLive != null) returnToLiveFocusRequester.requestFocus()
                                            else resizeModeFocusRequester.requestFocus()
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        shape = RoundedCornerShape(8.dp),
                        border = if (isAudioFocused) androidx.compose.foundation.BorderStroke(3.dp, AccentColor) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                        color = Color.Black.copy(alpha = 0.85f),
                        contentColor = Color.White
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LJUDSPRÅK",
                                style = controlTextStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (onReturnToLive != null) {
                        var returnToLiveFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = onReturnToLive,
                            modifier = Modifier
                                .width(144.dp)
                                .height(58.dp)
                                .focusRequester(returnToLiveFocusRequester)
                                .onFocusChanged { returnToLiveFocused = it.isFocused }
                                .onPreviewKeyEvent {
                                    when (it.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_UP -> {
                                            if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) timelineFocusRequester.requestFocus()
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_DOWN -> true
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) audioIconFocusRequester.requestFocus()
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> true
                                        else -> false
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            border = if (returnToLiveFocused) androidx.compose.foundation.BorderStroke(3.dp, AccentColor) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                            color = Color.Black.copy(alpha = 0.85f),
                            contentColor = Color.White
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LiveTv, null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "TILL LIVE",
                                    style = controlTextStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        var resizeModeFocused by remember { mutableStateOf(false) }
                        Surface(
                        onClick = onCycleVideoResizeMode,
                        modifier = Modifier
                            .width(208.dp)
                            .height(58.dp)
                            .focusRequester(resizeModeFocusRequester)
                            .onFocusChanged { resizeModeFocused = it.isFocused }
                            .onPreviewKeyEvent {
                                when (it.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) timelineFocusRequester.requestFocus()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> true
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) audioIconFocusRequester.requestFocus()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        if (onPlayNext != null) {
                                            if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) nextControlFocusRequester.requestFocus()
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        shape = RoundedCornerShape(8.dp),
                        border = if (resizeModeFocused) androidx.compose.foundation.BorderStroke(3.dp, AccentColor) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                        color = Color.Black.copy(alpha = 0.85f),
                        contentColor = Color.White
                        ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AspectRatio, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BILDFORMAT: $videoResizeModeLabel",
                                style = controlTextStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        }
                    }
                    if (onReturnToLive == null && onPlayNext != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        var nextFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = onPlayNext,
                            modifier = Modifier
                                .width(208.dp)
                                .height(58.dp)
                                .focusRequester(nextControlFocusRequester)
                                .onFocusChanged { nextFocused = it.isFocused }
                                .onPreviewKeyEvent {
                                    when (it.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_UP -> {
                                            if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) timelineFocusRequester.requestFocus()
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_DOWN -> true
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) resizeModeFocusRequester.requestFocus()
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> true
                                        else -> false
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            border = if (nextFocused) androidx.compose.foundation.BorderStroke(3.dp, AccentColor) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                            color = Color.Black.copy(alpha = 0.85f),
                            contentColor = Color.White
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Spela nästa avsnitt",
                                    style = controlTextStyle,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
            }
        }
    }
}
