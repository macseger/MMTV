package com.example.mmtv.ui

import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import androidx.compose.ui.res.painterResource
import coil.request.ImageRequest
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.Episode
import com.example.mmtv.player.MmtvPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

enum class OverlayState {
    NONE, CHANNELS, CATEGORIES, SUBTITLES, QUICK_INFO, EPG_INFO
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
    val mmtvPlayerFactory = remember { MmtvPlayer(context) }
    val exoPlayer = remember { 
        mmtvPlayerFactory.createPlayer().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var overlayState by remember { mutableStateOf(OverlayState.NONE) }
    var focusedChannel by remember { mutableStateOf<MediaSource?>(media) }
    var showSeekFeedback by remember { mutableStateOf(false) }
    var seekMessage by remember { mutableStateOf("") }
    
    var accumulatedSeekMs by remember { mutableLongStateOf(0L) }
    var isLongPressSeeking by remember { mutableStateOf(false) }
    var lastKeyDownTime by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var infoJob by remember { mutableStateOf<Job?>(null) }

    var lastCenterClickTime by remember { mutableLongStateOf(0L) }
    val doubleClickTimeout = 650L

    val channelListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val subtitleListState = rememberLazyListState()
    val epgListState = rememberLazyListState()
    
    // Helper for safe focus
    fun FocusRequester.safeFocus() {
        runCatching { this.requestFocus() }
    }

    val mainFocusRequester = remember { FocusRequester() }
    val epgFocusRequester = remember { FocusRequester() }
    val subtitleIconFocusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val channelFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val categoryFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val subtitleFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    var availableSubtitles by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var interactionJob by remember { mutableStateOf<Job?>(null) }
    
    // För "Spela nästa avsnitt"
    val isSeries = media?.type == MediaType.SERIES
    val nextEpisode = remember(media, viewModel.selectedSeriesInfo, viewModel.playingEpisode) {
        if (!isSeries || media == null || viewModel.selectedSeriesInfo?.episodes == null) {
            null
        } else {
            val episodesMap = viewModel.selectedSeriesInfo!!.episodes!!
            
            // Vi vill ha en platt lista på alla avsnitt i rätt ordning (säsong för säsong)
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
    
    val currentPlaybackId = remember(media, viewModel.playingEpisode) {
        if (isSeries && viewModel.playingEpisode != null) {
            viewModel.playingEpisode?.id ?: media?.id?.toString() ?: "0"
        } else {
            media?.id?.toString() ?: "0"
        }
    }

    val tvGuideFocusRequester = remember { FocusRequester() }
    val historyFocusRequester = remember { FocusRequester() }
    val recentChannelsFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val nextEpisodeButtonFocusRequester = remember { FocusRequester() }
    val favoriteButtonFocusRequester = remember { FocusRequester() }

    var selectedActionIndex by remember { mutableIntStateOf(-1) } // -1: none, 0: TV Guide, 1: History, 2+: Recent Channels

    // Favorit-feedback
    var showFavoriteFeedback by remember { mutableStateOf(false) }
    var favoriteMessage by remember { mutableStateOf("") }
    var favoriteJob by remember { mutableStateOf<Job?>(null) }
    val favorites by viewModel.favorites.collectAsState()

    LaunchedEffect(currentPosition, duration) {
        if (isSeries && nextEpisode != null && duration > 0) {
            val remainingSeconds = (duration - currentPosition) / 1000
            // Visa knappen sista 120 sekunderna ända till slutet
            // Vi kollar inte sessionManager.getAutoPlayNext() här för att visa KNAPPEN, 
            // men vi kan lägga till logik för automatisk uppspelning om vi vill.
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
            
            // Spara position var 30:e sekund för extra stabilitet
            if (media?.type != MediaType.LIVE && isPlaying) {
                counter++
                if (counter >= 30) {
                    sessionManager.savePlaybackPosition(currentPlaybackId, currentPosition)
                    counter = 0
                }
            }
            delay(1000)
        }
    }

    LaunchedEffect(url) {
        focusManager.clearFocus()
        
        // Add to history if it's a live stream
        if (media != null) {
            viewModel.addToHistory(media)
        }

        // Synka kategorin i bakgrunden så att vi hamnar rätt när vi backar ut
        if (media?.type == MediaType.LIVE) {
            viewModel.setLiveCategoryByMediaId(media.id)
        }

        val mediaItem = MediaItem.Builder().setUri(url).build()
        exoPlayer.setMediaItem(mediaItem)
        if (media?.type != MediaType.LIVE) {
            val savedPos = sessionManager.getPlaybackPosition(currentPlaybackId)
            if (savedPos > 0) exoPlayer.seekTo(savedPos)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        
        if (media?.type == MediaType.LIVE) {
            overlayState = OverlayState.QUICK_INFO
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                availableSubtitles = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
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
                val duration = exoPlayer.duration
                if (currentPos > 10000 && (duration == C.TIME_UNSET || currentPos < duration - 5000)) {
                    sessionManager.savePlaybackPosition(currentPlaybackId, currentPos)
                } else if (duration != C.TIME_UNSET && currentPos >= duration - 5000) {
                    sessionManager.clearPlaybackPosition(currentPlaybackId)
                }
            }
            // All cleanup should happen here to avoid race conditions and double releases
            exoPlayer.stop()
            mmtvPlayerFactory.releasePlayer()
        }
    }

    fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val totalSeconds = (ms / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }


    LaunchedEffect(overlayState) {
        when (overlayState) {
            OverlayState.CATEGORIES -> {
                val currentCategoryIndex = viewModel.lastLiveCategoryIndex.coerceAtLeast(0)
                if (categories.isNotEmpty()) {
                    categoryListState.scrollToItem(currentCategoryIndex)
                    delay(150) // Ökad delay för stabilitet
                    categoryFocusRequesters[currentCategoryIndex]?.safeFocus()
                }
            }
            OverlayState.CHANNELS -> {
                if (playlist.isNotEmpty()) {
                    val index = playlist.indexOfFirst { it.id == media?.id }.coerceAtLeast(0)
                    channelListState.scrollToItem(index)
                    delay(150)
                    channelFocusRequesters[playlist.getOrNull(index)?.id ?: -1]?.safeFocus()
                }
            }
            OverlayState.QUICK_INFO -> {
                infoJob?.cancel()
                infoJob = scope.launch {
                    delay(8000)
                    if (overlayState == OverlayState.QUICK_INFO) overlayState = OverlayState.NONE
                }
                // Focus the first action button by default or stay on current
                scope.launch {
                    delay(100)
                    tvGuideFocusRequester.safeFocus()
                }
            }
            OverlayState.SUBTITLES -> {
                delay(100)
                subtitleFocusRequesters[0]?.safeFocus()
            }
            OverlayState.EPG_INFO -> {
                delay(100)
                if (media != null && viewModel.getFullEpgForId(media.id).isNotEmpty()) {
                    epgFocusRequester.safeFocus()
                }
            }
            else -> { 
                delay(50)
                mainFocusRequester.safeFocus() 
            }
        }
    }

    fun performSeek(offsetMs: Long, isLongPress: Boolean = false) {
        seekJob?.cancel()
        interactionJob?.cancel()
        isUserInteracting = true
        accumulatedSeekMs += offsetMs
        
        val totalSecs = (Math.abs(accumulatedSeekMs) / 1000).toInt()
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        
        seekMessage = if (minutes > 0) {
            val sign = if (accumulatedSeekMs > 0) "+" else "-"
            "$sign$minutes:${String.format("%02d", seconds)}"
        } else {
            val sign = if (accumulatedSeekMs > 0) "+" else "-"
            "$sign$seconds s"
        }
        
        showSeekFeedback = true
        
        if (isLongPress) {
            // Vid långtryck, uppdatera positionen direkt för "mjuk" känsla
            val dur = exoPlayer.duration
            if (dur != C.TIME_UNSET) {
                val newPos = (exoPlayer.currentPosition + accumulatedSeekMs).coerceIn(0, dur)
                exoPlayer.seekTo(newPos)
                accumulatedSeekMs = 0
            }
        }

        seekJob = scope.launch {
            delay(if (isLongPress) 2000 else 1200) // Vänta på att användaren slutat trycka
            
            if (!isLongPress) {
                val dur = exoPlayer.duration
                if (dur != C.TIME_UNSET) {
                    val newPos = (exoPlayer.currentPosition + accumulatedSeekMs).coerceIn(0, dur)
                    exoPlayer.seekTo(newPos)
                }
            }
            // NOLLSTÄLL ALLTID efter utförd sökning eller timeout, även om dur == UNSET
            accumulatedSeekMs = 0 
            
            // Vänta lite innan vi tillåter att feedbacken döljs
            delay(2000)
            isUserInteracting = false
            
            delay(3000)
            if (!isLongPressSeeking && !isUserInteracting) {
                showSeekFeedback = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val isRepeat = keyEvent.nativeKeyEvent.repeatCount > 0
                    if (isRepeat) {
                        lastKeyDownTime = System.currentTimeMillis()
                        isLongPressSeeking = true
                    }
                    
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            when (overlayState) {
                                OverlayState.NONE -> {
                                    if (showNextEpisodeButton) {
                                        nextEpisodeButtonFocusRequester.safeFocus()
                                        true
                                    } else if (media?.type != MediaType.LIVE) {
                                        if (!showSeekFeedback) {
                                            showSeekFeedback = true
                                            seekJob?.cancel()
                                            seekJob = scope.launch { delay(5000); showSeekFeedback = false }
                                        }
                                        subtitleIconFocusRequester.safeFocus()
                                        true
                                    } else false
                                }
                                OverlayState.QUICK_INFO -> {
                                    // Move focus up from action row to nothing or hidden elements
                                    true
                                }
                                else -> false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            when (overlayState) {
                                OverlayState.NONE -> {
                                    overlayState = OverlayState.SUBTITLES
                                    true
                                }
                                OverlayState.QUICK_INFO -> {
                                    // Already at the bottom action row
                                    true
                                }
                                else -> false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            when (overlayState) {
                                OverlayState.NONE -> {
                                    if (media?.type == MediaType.LIVE) {
                                        overlayState = OverlayState.CHANNELS
                                    } else {
                                        if (isRepeat) performSeek(-10000L, true)
                                        else performSeek(-10000L)
                                    }
                                    true
                                }
                                OverlayState.CHANNELS -> {
                                    overlayState = OverlayState.CATEGORIES
                                    true
                                }
                                else -> false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            when (overlayState) {
                                OverlayState.NONE -> {
                                    if (media?.type != MediaType.LIVE) {
                                        if (isRepeat) performSeek(10000L, true)
                                        else performSeek(10000L)
                                    } else {
                                        overlayState = OverlayState.SUBTITLES
                                    }
                                    true
                                }
                                OverlayState.CATEGORIES -> {
                                    overlayState = OverlayState.CHANNELS
                                    true
                                }
                                OverlayState.CHANNELS -> {
                                    overlayState = OverlayState.NONE
                                    true
                                }
                                else -> false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (showNextEpisodeButton && nextEpisode != null && overlayState == OverlayState.NONE) {
                                onPlayNextEpisode(nextEpisode)
                                true
                            } else {
                                when (overlayState) {
                                    OverlayState.NONE -> {
                                        if (media?.type == MediaType.LIVE) {
                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastCenterClickTime < doubleClickTimeout) {
                                                if (media != null) scope.launch { viewModel.getFullEpgForId(media.id) }
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
                                            seekJob = scope.launch { delay(3000); showSeekFeedback = false }
                                        }
                                        true
                                    }
                                    OverlayState.QUICK_INFO -> {
                                        // Let the focused button handle it if any, 
                                        // otherwise if we are here it means root got it.
                                        // But Surface onClick should have consumed it if focused.
                                        // So we only get here if focus is NOT on a button.
                                        if (media != null) scope.launch { viewModel.getFullEpgForId(media.id) }
                                        overlayState = OverlayState.EPG_INFO
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                        KeyEvent.KEYCODE_PROG_RED, 183 -> { // 183 är ofta röd knapp på Android TV
                            if (media != null) {
                                val isFav = favorites.any { it.id == media.id }
                                viewModel.toggleFavorite(media)
                                favoriteMessage = if (isFav) "Borttagen från favoriter" else "Tillagd i favoriter"
                                showFavoriteFeedback = true
                                favoriteJob?.cancel()
                                favoriteJob = scope.launch {
                                    delay(3000)
                                    showFavoriteFeedback = false
                                }
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            when (overlayState) {
                                OverlayState.CHANNELS, OverlayState.CATEGORIES -> {
                                    overlayState = OverlayState.NONE
                                }
                                OverlayState.QUICK_INFO -> {
                                    overlayState = OverlayState.NONE
                                }
                                OverlayState.EPG_INFO -> {
                                    overlayState = OverlayState.NONE
                                }
                                OverlayState.NONE -> onBackPressed()
                                else -> overlayState = OverlayState.NONE
                            }
                            true
                        }
                        else -> false
                    }
                } else if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || 
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        isLongPressSeeking = false
                        // Starta timer för att dölja feedback
                        seekJob?.cancel()
                        seekJob = scope.launch {
                            delay(2500)
                            showSeekFeedback = false
                        }
                    }
                    false
                } else false
            }
            .focusRequester(mainFocusRequester)
            .focusable()
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = false; keepScreenOn = true } },
            modifier = Modifier.fillMaxSize()
        )

        // --- MODERN SEEK BAR / PROGRESS OVERLAY (VOD) ---
        AnimatedVisibility(
            visible = showSeekFeedback && media?.type != MediaType.LIVE,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(100.dp),
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = media?.title ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 400.dp)
                            )
                            if (accumulatedSeekMs != 0L) {
                                Text(
                                    text = seekMessage,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!isPlaying && accumulatedSeekMs == 0L) {
                                Icon(Icons.Default.Pause, null, tint = Color.White, modifier = Modifier.size(32.dp).padding(end = 12.dp))
                            }
                            
                            // Interactive Subtitle Button
                            var isSubFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { overlayState = OverlayState.SUBTITLES },
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .focusRequester(subtitleIconFocusRequester)
                                    .onFocusChanged { 
                                        isSubFocused = it.isFocused 
                                        if (it.isFocused) {
                                            // Förläng visningstiden om vi har fokus
                                            seekJob?.cancel()
                                        } else {
                                            // Starta timern igen när vi tappar fokus
                                            seekJob = scope.launch {
                                                delay(5000)
                                                showSeekFeedback = false
                                            }
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSubFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                                contentColor = if (isSubFocused) Color.Black else Color.White
                            ) {
                                Text(
                                    text = "UNDERTEXTER",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.sp,
                                        fontSize = 14.sp
                                    ),
                                    color = if (availableSubtitles.isNotEmpty()) 
                                            (if (isSubFocused) Color.Black else Color.White) 
                                          else (if (isSubFocused) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.3f))
                                )
                            }

                            Text(
                                text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.LightGray,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Modern Progress Bar
                    val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        if (isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
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
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = if (favoriteMessage.contains("Tillagd")) Color.Red else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = favoriteMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- NEXT EPISODE BUTTON (MODERN OVERLAY) ---
        AnimatedVisibility(
            visible = showNextEpisodeButton && nextEpisode != null && overlayState == OverlayState.NONE,
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
                border = if (isFocused) null else androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                tonalElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Miniatyr/Ikon för serien
                    Box(modifier = Modifier.size(86.dp).clip(RoundedCornerShape(8.dp))) {
                        AsyncImage(
                            model = media?.icon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                )
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp).align(Alignment.Center)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SE NÄSTA AVSNITT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            ),
                            color = if (isFocused) Color.Black.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = nextEpisode?.title ?: "Nästa avsnitt",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        val remainingSecs = ((duration - currentPosition) / 1000).coerceAtLeast(0)
                        Text(
                            text = "Avslutas om $remainingSecs s",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isFocused) Color.Black.copy(alpha = 0.5f) else Color.Gray
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (isFocused) Color.Black else MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            LaunchedEffect(showNextEpisodeButton) {
                if (showNextEpisodeButton) {
                    delay(2500)
                    if (showNextEpisodeButton && overlayState == OverlayState.NONE) {
                        nextEpisodeButtonFocusRequester.requestFocus()
                    }
                }
            }
        }

        // --- QUICK INFO OVERLAY (ZAP BANNER) ---
        AnimatedVisibility(
            visible = overlayState == OverlayState.QUICK_INFO && media != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val epg = produceState<EpgListing?>(initialValue = null, key1 = media?.id) {
                value = viewModel.getEpgForId(media?.id ?: 0)
            }.value
            
            val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
            val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d MMM").withLocale(Locale("sv", "SE")).withZone(ZoneId.systemDefault()) }
            val history = viewModel.uiState.history.filter { it.type == MediaType.LIVE }.take(10)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Top Info Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val categoryName = categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title ?: ""
                    Text(
                        text = categoryName.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "${dateFormatter.format(Instant.now())}  ${timeFormatter.format(Instant.now())}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Main Program Banner
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = media?.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = media?.title ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            
                            Text(
                                text = epg?.title ?: "Ingen information",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            
                            if (epg != null) {
                                val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))
                                val stop = timeFormatter.format(Instant.ofEpochSecond(epg.stopTimestamp ?: 0))
                                val now = System.currentTimeMillis() / 1000
                                val duration = (epg.stopTimestamp ?: 0) - (epg.startTimestamp ?: 0)
                                val elapsed = now - (epg.startTimestamp ?: 0)
                                val remaining = (epg.stopTimestamp ?: 0) - now
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$start - $stop",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Box(modifier = Modifier.width(1.dp).height(12.dp).background(Color.Gray))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "${remaining / 60} min kvar",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                val progress = if (duration > 0) elapsed.toFloat() / duration.toFloat() else 0f
                                if (progress in 0f..1f) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.1f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(progress)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Technical Tags (Mockup for now)
                        Row(modifier = Modifier.padding(start = 16.dp)) {
                            TechnicalTag("FHD")
                            Spacer(modifier = Modifier.width(4.dp))
                            TechnicalTag("50FPS")
                            Spacer(modifier = Modifier.width(4.dp))
                            TechnicalTag("2.0")
                        }
                    }
                }

                // Action Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton(
                        icon = Icons.Default.Menu,
                        label = "TV-guide",
                        focusRequester = tvGuideFocusRequester,
                        onFocus = { infoJob?.cancel() },
                        onBlur = { 
                            infoJob = scope.launch {
                                delay(8000)
                                if (overlayState == OverlayState.QUICK_INFO) overlayState = OverlayState.NONE
                            }
                        },
                        onClick = {
                            if (media != null) scope.launch { viewModel.getFullEpgForId(media.id) }
                            overlayState = OverlayState.EPG_INFO
                        }
                    )
                    
                    val isFav = media?.let { m -> favorites.any { it.id == m.id } } ?: false
                    ActionButton(
                        icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = "Favorit",
                        focusRequester = favoriteButtonFocusRequester,
                        onFocus = { infoJob?.cancel() },
                        onBlur = { 
                            infoJob = scope.launch {
                                delay(8000)
                                if (overlayState == OverlayState.QUICK_INFO) overlayState = OverlayState.NONE
                            }
                        },
                        onClick = {
                            media?.let { viewModel.toggleFavorite(it) }
                        }
                    )

                    // Recent Channels
                    history.forEachIndexed { index, historyItem ->
                        RecentChannelButton(
                            item = historyItem,
                            focusRequester = recentChannelsFocusRequesters.getOrPut(historyItem.id) { FocusRequester() },
                            onFocus = { infoJob?.cancel() },
                            onBlur = { 
                                infoJob = scope.launch {
                                    delay(8000)
                                    if (overlayState == OverlayState.QUICK_INFO) overlayState = OverlayState.NONE
                                }
                            },
                            onClick = {
                                overlayState = OverlayState.NONE
                                onMediaSelected(historyItem)
                            }
                        )
                    }
                }
            }
        }

        // --- MODERN TIVIMATE-STYLE INTEGRATED OVERLAY ---
        val isSideOverlayVisible = overlayState == OverlayState.CHANNELS || overlayState == OverlayState.CATEGORIES
        
        AnimatedVisibility(
            visible = isSideOverlayVisible,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(400)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(400)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Dimmed background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { overlayState = OverlayState.NONE }
                        )
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    // 1. Categories Sidebar (Slide out)
                    // Vi använder AnimatedContent eller liknande för att växla mellan vyer om vi vill, 
                    // men här behåller vi logiken för att bara visa kategorier när overlayState är CATEGORIES
                    val showCategories = overlayState == OverlayState.CATEGORIES
                    
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showCategories,
                        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                    ) {
                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(280.dp)
                                .background(Color.Black.copy(alpha = 0.95f))
                                .padding(vertical = 16.dp)
                        ) {
                            itemsIndexed(categories) { index, category ->
                                val isSelected = categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title == category.title
                                CategoryListItem(
                                    title = category.title ?: "",
                                    isSelected = isSelected,
                                    modifier = Modifier
                                        .focusRequester(categoryFocusRequesters.getOrPut(index) { FocusRequester() })
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                onCategorySelected(index)
                                                val cat = categories.getOrNull(index)
                                                if (cat != null && cat.items.isEmpty()) {
                                                    viewModel.loadItemsForCategory(MediaType.LIVE, cat.categoryId)
                                                }
                                                scope.launch { channelListState.scrollToItem(0) }
                                            }
                                        }
                                        .onKeyEvent {
                                            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                                overlayState = OverlayState.CHANNELS
                                                true
                                            } else false
                                        },
                                    onClick = { overlayState = OverlayState.CHANNELS }
                                )
                            }
                        }
                    }

                    // 2. Channel List (Left Side)
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(420.dp)
                            .background(Color.Black.copy(alpha = 0.85f))
                    ) {
                        val currentCategoryTitle = categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title ?: ""
                        Text(
                            text = currentCategoryTitle.uppercase(),
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )

                        LazyColumn(
                            state = channelListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(playlist) { index, item ->
                                ChannelListItem(
                                    item = item,
                                    isSelected = item.id == media?.id,
                                    epg = produceState<EpgListing?>(initialValue = null, key1 = item.id) {
                                        value = viewModel.getEpgForId(item.id)
                                    }.value,
                                    nextEpg = produceState<EpgListing?>(initialValue = null, key1 = item.id) {
                                        value = viewModel.getNextEpgForId(item.id)
                                    }.value,
                                    modifier = Modifier
                                        .focusRequester(channelFocusRequesters.getOrPut(item.id) { FocusRequester() })
                                        .onFocusChanged { if (it.isFocused) focusedChannel = item }
                                        .onKeyEvent {
                                            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                                overlayState = OverlayState.CATEGORIES
                                                true
                                            } else false
                                        },
                                    onClick = { overlayState = OverlayState.NONE; onMediaSelected(item) }
                                )
                            }
                        }
                    }

                    // 3. EPG & Program Details (Right Side) - The TiviMate Look
                    if (overlayState == OverlayState.CHANNELS || overlayState == OverlayState.CATEGORIES) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .padding(32.dp)
                        ) {
                            // Detailed Info Box
                            val currentEpg = produceState<EpgListing?>(initialValue = null, key1 = focusedChannel?.id) {
                                value = viewModel.getEpgForId(focusedChannel?.id ?: 0)
                            }.value
                            
                            ModernProgramDetailBox(epg = currentEpg, channel = focusedChannel)

                            Spacer(modifier = Modifier.height(32.dp))

                            // Upcoming Programs for Focused Channel
                            val fullEpg = produceState<List<EpgListing>>(initialValue = emptyList(), key1 = focusedChannel?.id) {
                                value = viewModel.getFullEpgForId(focusedChannel?.id ?: 0)
                            }.value
                            val now = System.currentTimeMillis() / 1000
                            val upcomingEpg = remember(fullEpg, focusedChannel) { 
                                fullEpg.filter { (it.stopTimestamp ?: 0) > now } 
                            }

                            if (upcomingEpg.isNotEmpty()) {
                                Text(
                                    text = "PROGRAMGUIDE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                    itemsIndexed(upcomingEpg) { idx, epg ->
                                        MiniProgramGuideItem(
                                            epg = epg,
                                            isCurrent = idx == 0 && (epg.startTimestamp ?: 0) <= now
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- FULL EPG INFO (MODAL) ---
        if (overlayState == OverlayState.EPG_INFO && media != null) {
            val fullEpg = produceState<List<EpgListing>>(initialValue = emptyList(), key1 = media.id) {
                value = viewModel.getFullEpgForId(media.id)
            }.value
            val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .onKeyEvent { 
                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            overlayState = OverlayState.NONE
                            true
                        } else false
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .fillMaxHeight(0.85f)
                        .clickable(enabled = false) {},
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = MaterialTheme.shapes.large,
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.1f)))
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = media.icon,
                                contentDescription = null,
                                modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(text = media.title ?: "Programguide", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Text(text = "Kommande program", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { overlayState = OverlayState.NONE }) { 
                                Icon(Icons.Default.Close, null, tint = Color.White) 
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        if (fullEpg.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                                Text("Laddar programinfo...", color = Color.Gray) 
                            }
                        } else {
                            val now = System.currentTimeMillis() / 1000
                            // Filtrera bort program som redan har slutat för att göra listan mer relevant
                            val futureEpg = remember(fullEpg) { fullEpg.filter { (it.stopTimestamp ?: 0) > now } }
                            
                            LaunchedEffect(futureEpg) {
                                if (futureEpg.isNotEmpty()) {
                                    epgListState.scrollToItem(0)
                                    delay(100)
                                    epgFocusRequester.requestFocus()
                                }
                            }
                            
                            LazyColumn(
                                state = epgListState,
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                itemsIndexed(futureEpg) { index, epg ->
                                    var isItemFocused by remember { mutableStateOf(false) }
                                    val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))
                                    val stop = timeFormatter.format(Instant.ofEpochSecond(epg.stopTimestamp ?: 0))
                                    val isCurrent = (epg.startTimestamp ?: 0) <= now && (epg.stopTimestamp ?: 0) > now
                                    
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (index == 0) Modifier.focusRequester(epgFocusRequester) else Modifier)
                                            .onFocusChanged { isItemFocused = it.isFocused }
                                            .clickable { /* Klickbar för fokus */ }
                                            .padding(vertical = 4.dp),
                                        color = if (isItemFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                                else if (isCurrent) Color.White.copy(alpha = 0.05f)
                                                else Color.Transparent,
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Row(modifier = Modifier.padding(16.dp)) {
                                            Column(modifier = Modifier.width(120.dp)) {
                                                Text(text = start, style = MaterialTheme.typography.titleMedium, color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White, fontWeight = FontWeight.Bold)
                                                Text(text = stop, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            }
                                            
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = epg.title ?: "", 
                                                    style = MaterialTheme.typography.titleLarge, 
                                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White
                                                )
                                                if (!epg.description.isNullOrBlank()) {
                                                    Text(
                                                        text = epg.description, 
                                                        style = MaterialTheme.typography.bodyMedium, 
                                                        color = Color.LightGray,
                                                        maxLines = if (isItemFocused) 10 else 2,
                                                        overflow = TextOverflow.Ellipsis
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
            }
        }



        // Subtitles Overlay
        AnimatedVisibility(
            visible = overlayState == OverlayState.SUBTITLES,
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
                                SubtitleOptionItem(label = "Ingen undertext", isSelected = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), modifier = Modifier.focusRequester(subtitleFocusRequesters.getOrPut(0) { FocusRequester() }), onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setDisabledTrackTypes(setOf(C.TRACK_TYPE_TEXT)).build()
                                    overlayState = OverlayState.NONE
                                })
                            }
                            itemsIndexed(availableSubtitles) { index, group ->
                                val trackName = group.getTrackFormat(0).language ?: "Spår ${index + 1}"
                                SubtitleOptionItem(label = trackName.uppercase(), isSelected = exoPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) && group.isSelected, modifier = Modifier.focusRequester(subtitleFocusRequesters.getOrPut(index + 1) { FocusRequester() }), onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).setDisabledTrackTypes(emptySet()).build()
                                    overlayState = OverlayState.NONE
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TechnicalTag(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp)
            .height(80.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { 
                isFocused = it.isFocused
                if (it.isFocused) onFocus() else onBlur()
            },
        color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.5f),
        contentColor = if (isFocused) Color.Black else Color.White,
        shape = RoundedCornerShape(8.dp),
        border = if (isFocused) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RecentChannelButton(
    item: MediaSource,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(120.dp)
            .height(80.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { 
                isFocused = it.isFocused
                if (it.isFocused) onFocus() else onBlur()
            },
        color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.5f),
        contentColor = if (isFocused) Color.Black else Color.White,
        shape = RoundedCornerShape(8.dp),
        border = if (isFocused) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.icon,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isFocused) 0.3f else 0.6f)
                    .padding(12.dp),
                contentScale = ContentScale.Fit
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = item.title ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SubtitleOptionItem(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth().onFocusChanged { hasFocus = it.isFocused }.clickable { onClick() }, color = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = null)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun CategoryListItem(title: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(
        targetValue = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) 
                    else if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) 
                    else Color.Transparent,
        label = "catBg"
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { onClick() }
            ), 
        color = backgroundColor
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            )
            Text(
                text = title, 
                modifier = Modifier.padding(horizontal = 20.dp), 
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal), 
                color = if (isSelected || hasFocus) Color.White else Color.LightGray, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ChannelListItem(item: MediaSource, isSelected: Boolean, epg: EpgListing?, onClick: () -> Unit, modifier: Modifier = Modifier, nextEpg: EpgListing? = null) {
    var hasFocus by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    
    val backgroundColor by animateColorAsState(
        targetValue = when {
            hasFocus -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else -> Color.Transparent
        }, label = "chBg"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { onClick() }
            ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.icon)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.05f)).padding(4.dp),
                contentScale = ContentScale.Fit,
                error = painterResource(id = android.R.drawable.ic_menu_report_image)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title ?: "", 
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), 
                    color = Color.White, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = epg?.title ?: "Ingen programinfo", 
                    style = MaterialTheme.typography.bodySmall, 
                    color = if (hasFocus) Color.White else Color.LightGray, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )

                if (epg != null) {
                    val now = System.currentTimeMillis() / 1000
                    val start = epg.startTimestamp ?: 0L
                    val stop = epg.stopTimestamp ?: 0L
                    if (now in start..stop) {
                        val progress = (now - start).toFloat() / (stop - start).toFloat()
                        LinearProgressIndicator(
                            progress = { progress }, 
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModernProgramDetailBox(epg: EpgListing?, channel: MediaSource?) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(modifier = Modifier.padding(24.dp)) {
            // Channel Icon in Info Box
            if (channel != null) {
                AsyncImage(
                    model = channel.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(24.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                if (epg != null) {
                    val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))
                    val stop = timeFormatter.format(Instant.ofEpochSecond(epg.stopTimestamp ?: 0))
                    val duration = ((epg.stopTimestamp ?: 0) - (epg.startTimestamp ?: 0)) / 60

                    Text(
                        text = epg.title ?: "",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(text = "$start - $stop", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = "$duration min", color = Color.Gray)
                        if (channel != null) {
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = channel.title ?: "", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        text = epg.description ?: "Ingen beskrivning tillgänglig.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.LightGray,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = channel?.title ?: "Välj en kanal",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                    Text("Ingen programinfo tillgänglig", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun MiniProgramGuideItem(epg: EpgListing, isCurrent: Boolean) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = start,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = epg.title ?: "",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isCurrent) Color.White else Color.LightGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
        )
    }
}
