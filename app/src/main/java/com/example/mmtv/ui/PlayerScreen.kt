package com.example.mmtv.ui

import com.example.mmtv.ui.theme.FocusBorderColor
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.Episode
import com.example.mmtv.ui.components.*
import com.example.mmtv.ui.components.EpgGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.absoluteValue

enum class OverlayState {
    NONE, CHANNELS, CATEGORIES, SUBTITLES, QUICK_INFO, EPG_INFO, FULL_EPG
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
    onPlayNextEpisode: (Episode) -> Unit = {},
    viewModel: MediaViewModel
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val sessionManager = remember { SessionManager(context) }
    val isLiveStream = media?.type == MediaType.LIVE
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
    var focusedChannel by remember { mutableStateOf(media) }
    var showSeekFeedback by remember { mutableStateOf(false) }
    var seekMessage by remember { mutableStateOf("") }
    
    var accumulatedSeekMs by remember { mutableLongStateOf(0L) }
    var isLongPressSeeking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var infoJob by remember { mutableStateOf<Job?>(null) }

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
    
    // --- FOCUS REQUESTERS ---
    val mainFocusRequester = remember { FocusRequester() }
    val epgFocusRequester = remember { FocusRequester() }
    val subtitleIconFocusRequester = remember { FocusRequester() }
    val tvGuideFocusRequester = remember { FocusRequester() }
    val nextEpisodeButtonFocusRequester = remember { FocusRequester() }
    val favoriteButtonFocusRequester = remember { FocusRequester() }
    
    val channelFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val categoryFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val subtitleFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val recentChannelsFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    // --- MEDIA STATE ---
    var availableSubtitles by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var lastCenterClickTime by remember { mutableLongStateOf(0L) }
    val doubleClickTimeout = 650L

    // Technical info
    var videoFormat by remember { mutableStateOf<Format?>(null) }
    var audioFormat by remember { mutableStateOf<Format?>(null) }
    
    val isSeries = media?.type == MediaType.SERIES
    val favorites by viewModel.favorites.collectAsState()

    // --- NEXT EPISODE LOGIC ---
    val nextEpisode = remember(media, viewModel.selectedSeriesInfo, viewModel.playingEpisode) {
        if (!isSeries || viewModel.selectedSeriesInfo?.episodes == null) {
            null
        } else {
            val episodesMap = viewModel.selectedSeriesInfo!!.episodes!!
            val allEpisodes = episodesMap.keys
                .sortedBy { it.toIntOrNull() ?: 0 }
                .flatMap { seasonKey ->
                    episodesMap[seasonKey]?.sortedBy { it.id?.toIntOrNull() ?: 0 } ?: emptyList()
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

    LaunchedEffect(exoPlayer) {
        var counter = 0
        while (true) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration
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

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                availableSubtitles = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                videoFormat = exoPlayer.videoFormat
                audioFormat = exoPlayer.audioFormat
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                videoFormat = exoPlayer.videoFormat
                audioFormat = exoPlayer.audioFormat
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
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
            OverlayState.CHANNELS -> {
                if (playlist.isNotEmpty()) {
                    // SideOverlay sätter fokus först när den förpositionerade raden
                    // faktiskt har komponerats. Ett gammalt FocusRequester får inte
                    // flytta listan en andra gång.
                    if (media != null) focusedChannel = media
                }
            }
            OverlayState.QUICK_INFO -> {
                resetAutoHideTimer()
                scope.launch {
                    delay(60)
                    tvGuideFocusRequester.safeFocus()
                }
            }
            OverlayState.SUBTITLES -> {
                delay(60)
                subtitleFocusRequesters[0]?.safeFocus()
            }
            OverlayState.EPG_INFO -> {
                // EPG-modalens innehåll läser enbart den redan förberedda cachen.
            }
            OverlayState.FULL_EPG -> {
                // EpgGrid hanterar sitt eget fokus internt
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
                    player = exoPlayer
                    useController = false
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
                                        overlayState = OverlayState.QUICK_INFO
                                    } else {
                                        showSeekFeedback = !showSeekFeedback
                                        if (showSeekFeedback) {
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
                when (nativeEvent.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (overlayState == OverlayState.QUICK_INFO) resetAutoHideTimer()
                        val isRepeat = nativeEvent.repeatCount > 0
                        if (isRepeat) isLongPressSeeking = true

                        when (nativeEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                            when (overlayState) {
                                OverlayState.NONE -> {
                                    if (showNextEpisodeButton) {
                                        nextEpisodeButtonFocusRequester.safeFocus()
                                        true
                                    } else if (media?.type == MediaType.LIVE) {
                                        // Den kompakta tablåvyn är trygg på TV:ns begränsade CPU.
                                        overlayState = OverlayState.EPG_INFO
                                        true
                                    } else {
                                        if (!showSeekFeedback) {
                                            showSeekFeedback = true
                                            seekJob?.cancel()
                                            seekJob = scope.launch { delay(5000); showSeekFeedback = false }
                                        }
                                        subtitleIconFocusRequester.safeFocus()
                                        true
                                    }
                                }
                                else -> false
                            }
                        }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (overlayState == OverlayState.NONE) {
                                    if (media?.type == MediaType.LIVE) {
                                        overlayState = OverlayState.EPG_INFO
                                    } else {
                                        overlayState = OverlayState.SUBTITLES
                                    }
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (overlayState == OverlayState.NONE) {
                                    if (media?.type == MediaType.LIVE) {
                                        // SideOverlay positionerar och fokuserar den aktuella kanalen
                                        // efter att rätt rad faktiskt har komponerats.
                                        focusedChannel = media
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
                                    if (media?.type != MediaType.LIVE) {
                                        if (isRepeat) performSeek(10000L, true)
                                        else performSeek(10000L)
                                    } else {
                                        overlayState = OverlayState.SUBTITLES
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
                                if (showNextEpisodeButton && nextEpisode != null && overlayState == OverlayState.NONE) {
                                    onPlayNextEpisode(nextEpisode)
                                    true
                                } else if (overlayState == OverlayState.NONE) {
                                    if (media != null && media.type == MediaType.LIVE) {
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastCenterClickTime < doubleClickTimeout) {
                                            overlayState = OverlayState.EPG_INFO
                                        } else {
                                            overlayState = OverlayState.QUICK_INFO
                                        }
                                        lastCenterClickTime = currentTime
                                    } else {
                                        if (exoPlayer.isPlaying) {
                                            exoPlayer.pause()
                                            isPlaying = false
                                        } else {
                                            exoPlayer.play()
                                            isPlaying = true
                                        }
                                        showSeekFeedback = true
                                        seekJob?.cancel()
                                        if (isPlaying) {
                                            seekJob = scope.launch { delay(3000); if (isPlaying) showSeekFeedback = false }
                                        }
                                    }
                                    true
                                } else false
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
                            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_M -> {
                                overlayState = OverlayState.EPG_INFO
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                when (overlayState) {
                                    OverlayState.NONE -> onBackPressed()
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
                            } else if (overlayState == OverlayState.NONE && media?.type != MediaType.LIVE) {
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
        AndroidView(
            factory = { ctx -> 
                PlayerView(ctx).apply { 
                    player = exoPlayer
                    useController = false
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
            visible = (showSeekFeedback || !isPlaying) && media?.type != MediaType.LIVE,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.fillMaxSize()
        ) {
            VodControlOverlay(
                media = media,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                accumulatedSeekMs = accumulatedSeekMs,
                seekMessage = seekMessage,
                availableSubtitles = availableSubtitles,
                subtitleIconFocusRequester = subtitleIconFocusRequester,
                isTvMode = viewModel.isTvMode,
                themeColor = viewModel.currentThemeColor,
                onToggleSubtitles = { overlayState = OverlayState.SUBTITLES }
            )
        }

        if (isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = viewModel.currentThemeColor, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
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
        AnimatedVisibility(
            visible = isQuickInfoVisible && media != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            ) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            media?.let { 
                QuickInfoOverlay(
                    media = it,
                    viewModel = viewModel,
                    categories = categories,
                    tvGuideFocusRequester = tvGuideFocusRequester,
                    favoriteButtonFocusRequester = favoriteButtonFocusRequester,
                    recentChannelsFocusRequesters = recentChannelsFocusRequesters,
                    videoFormat = videoFormat,
                    audioFormat = audioFormat,
                    favorites = favorites,
                    onTvGuideClick = {
                        overlayState = OverlayState.EPG_INFO
                    },
                    onRecentChannelClick = { item ->
                        overlayState = OverlayState.NONE
                        onMediaSelected(item)
                    },
                    onCategoryRequest = { overlayState = OverlayState.CATEGORIES },
                    onCloseRequest = { overlayState = OverlayState.NONE },
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
            focusedChannel = focusedChannel,
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
            onFocusedChannelChanged = { focusedChannel = it },
            onOverlayStateChange = { overlayState = OverlayState.valueOf(it) },
            onDismiss = { overlayState = OverlayState.NONE }
        )

        if (overlayState == OverlayState.EPG_INFO && media != null) {
            EpgModal(
                media = media,
                viewModel = viewModel,
                epgListState = epgListState,
                epgFocusRequester = epgFocusRequester,
                onClose = { overlayState = OverlayState.NONE }
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
                                        overlayState = OverlayState.NONE
                                    }
                                )
                            }
                            itemsIndexed(availableSubtitles, key = { index, _ -> index }) { index, group ->
                                val trackName = group.mediaTrackGroup.getFormat(0).language ?: "Spår ${index + 1}"
                                SubtitleOptionItem(
                                    label = trackName.uppercase(),
                                    isSelected = exoPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) && group.isSelected,
                                    modifier = Modifier.focusRequester(subtitleFocusRequesters.getOrPut(index + 1) { FocusRequester() }),
                                    onClick = {
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                                        overlayState = OverlayState.NONE
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
}

@Composable
fun VodControlOverlay(
    media: MediaSource?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    accumulatedSeekMs: Long,
    seekMessage: String,
    availableSubtitles: List<Tracks.Group>,
    subtitleIconFocusRequester: FocusRequester,
    isTvMode: Boolean = true,
    themeColor: Color = Color(0xFF2196F3),
    onToggleSubtitles: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.85f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.9f)
                    ),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
    ) {
        // --- TOP INFO ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(48.dp)
                .widthIn(max = 600.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Movie Poster (Mini)
                Card(
                    modifier = Modifier.size(80.dp, 120.dp),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = media?.icon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (media?.icon == null) {
                            Box(Modifier.fillMaxSize().background(Color.DarkGray))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(24.dp))
                
                Column {
                    Text(
                        text = media?.title ?: "",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val rating = media?.rating
                        if (!rating.isNullOrBlank() && rating != "0.0") {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = rating, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                        
                        val genre = media?.genre
                        if (!genre.isNullOrBlank()) {
                            Text(text = genre, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = media?.plot ?: "Ingen beskrivning tillgänglig.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 24.sp
            )
        }

        // --- CENTER STATE ICON ---
        Box(modifier = Modifier.align(Alignment.Center)) {
            if (accumulatedSeekMs != 0L) {
                Text(
                    text = seekMessage,
                    style = MaterialTheme.typography.displayMedium,
                    color = themeColor,
                    fontWeight = FontWeight.Black
                )
            } else if (!isPlaying) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
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
                .padding(start = 48.dp, end = 48.dp, bottom = 48.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
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

                    Text(
                        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isTvMode) {
                        CastButton(modifier = Modifier.size(40.dp).padding(end = 16.dp))
                    }

                    // Subtitles Button
                    var isSubFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onToggleSubtitles,
                    modifier = Modifier
                        .focusRequester(subtitleIconFocusRequester)
                        .onFocusChanged { isSubFocused = it.isFocused },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSubFocused) themeColor else Color.White.copy(alpha = 0.1f),
                    contentColor = if (isSubFocused) Color.Black else Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Subtitles, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "UNDERTEXTER",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            ),
                            color = if (availableSubtitles.isNotEmpty()) 
                                    (if (isSubFocused) Color.Black else Color.White) 
                                  else (if (isSubFocused) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.3f))
                        )
                    }
                }
            }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Progress Bar
            val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(themeColor)
                )
            }
        }
    }
}
