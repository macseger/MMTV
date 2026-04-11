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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
    var showSeekFeedback by remember { mutableStateOf(false) }
    var seekMessage by remember { mutableStateOf("") }
    
    var accumulatedSeekMs by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var infoJob by remember { mutableStateOf<Job?>(null) }

    var lastCenterClickTime by remember { mutableLongStateOf(0L) }
    val doubleClickTimeout = 500L

    val channelListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val subtitleListState = rememberLazyListState()
    val epgListState = rememberLazyListState()
    
    val mainFocusRequester = remember { FocusRequester() }
    val epgFocusRequester = remember { FocusRequester() }
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
    
    // För "Spela nästa avsnitt"
    val isSeries = media?.type == MediaType.SERIES
    val nextEpisode = remember(media, viewModel.selectedSeriesInfo) {
        if (!isSeries || media == null || viewModel.selectedSeriesInfo?.episodes == null) null
        else {
            val episodesMap = viewModel.selectedSeriesInfo!!.episodes!!
            val allEpisodes = episodesMap.keys.sortedBy { it.toIntOrNull() ?: 0 }
                .flatMap { episodesMap[it] ?: emptyList() }
            
            val currentIndex = allEpisodes.indexOfFirst { it.id == media.id.toString() }
            if (currentIndex != -1 && currentIndex < allEpisodes.size - 1) {
                allEpisodes[currentIndex + 1]
            } else null
        }
    }
    
    var showNextEpisodeButton by remember { mutableStateOf(false) }
    val nextEpisodeButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(currentPosition, duration) {
        if (isSeries && nextEpisode != null && duration > 0) {
            val remainingSeconds = (duration - currentPosition) / 1000
            showNextEpisodeButton = remainingSeconds in 1..30
        } else {
            showNextEpisodeButton = false
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration
            delay(1000)
        }
    }

    LaunchedEffect(url) {
        focusManager.clearFocus()
        val mediaItem = MediaItem.Builder().setUri(url).build()
        exoPlayer.setMediaItem(mediaItem)
        if (media?.type != MediaType.LIVE) {
            val savedPos = sessionManager.getPlaybackPosition(media?.id.toString())
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
                    // Backa ur när videon är slut (enligt önskemål)
                    onBackPressed()
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
                    sessionManager.savePlaybackPosition(media.id.toString(), currentPos)
                } else if (duration != C.TIME_UNSET && currentPos >= duration - 5000) {
                    sessionManager.clearPlaybackPosition(media.id.toString())
                }
            }
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
                categoryListState.scrollToItem(currentCategoryIndex)
                delay(100)
                categoryFocusRequesters[currentCategoryIndex]?.requestFocus()
            }
            OverlayState.CHANNELS -> {
                val index = playlist.indexOfFirst { it.id == media?.id }.coerceAtLeast(0)
                channelListState.scrollToItem(index)
                delay(100)
                channelFocusRequesters[playlist.getOrNull(index)?.id ?: -1]?.requestFocus()
            }
            OverlayState.QUICK_INFO -> {
                infoJob?.cancel()
                infoJob = scope.launch {
                    delay(5000)
                    if (overlayState == OverlayState.QUICK_INFO) overlayState = OverlayState.NONE
                }
            }
            OverlayState.SUBTITLES -> {
                delay(50)
                subtitleFocusRequesters[0]?.requestFocus()
            }
            OverlayState.EPG_INFO -> {
                delay(50)
                if (media != null && viewModel.getFullEpgForId(media.id).isNotEmpty()) {
                    epgFocusRequester.requestFocus()
                }
            }
            else -> { mainFocusRequester.requestFocus() }
        }
    }

    fun performSeek(offsetMs: Long) {
        seekJob?.cancel()
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
        seekJob = scope.launch {
            delay(800)
            val dur = exoPlayer.duration
            if (dur != C.TIME_UNSET) {
                val newPos = (exoPlayer.currentPosition + accumulatedSeekMs).coerceIn(0, dur)
                exoPlayer.seekTo(newPos)
            }
            accumulatedSeekMs = 0
            delay(2500)
            showSeekFeedback = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (overlayState == OverlayState.NONE) {
                                if (media?.type == MediaType.LIVE && playlist.isNotEmpty()) {
                                    val currentIndex = playlist.indexOfFirst { it.id == media.id }
                                    if (currentIndex != -1) {
                                        val prevIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
                                        onMediaSelected(playlist[prevIndex])
                                    }
                                    true
                                } else if (showNextEpisodeButton) {
                                    nextEpisodeButtonFocusRequester.requestFocus()
                                    true
                                } else false
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (overlayState == OverlayState.NONE && media?.type == MediaType.LIVE && playlist.isNotEmpty()) {
                                val currentIndex = playlist.indexOfFirst { it.id == media.id }
                                if (currentIndex != -1) {
                                    val nextIndex = (currentIndex + 1) % playlist.size
                                    onMediaSelected(playlist[nextIndex])
                                }
                                true
                            } else if (overlayState == OverlayState.NONE && media?.type != MediaType.LIVE) {
                                overlayState = OverlayState.SUBTITLES
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (overlayState == OverlayState.NONE) {
                                if (media?.type == MediaType.LIVE) overlayState = OverlayState.CHANNELS
                                else performSeek(-60000L) // 1 min klick
                                true
                            } else if (overlayState == OverlayState.CHANNELS) {
                                overlayState = OverlayState.CATEGORIES
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (overlayState == OverlayState.NONE) {
                                if (media?.type != MediaType.LIVE) performSeek(60000L) // 1 min klick
                                else overlayState = OverlayState.SUBTITLES
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
                            if (showNextEpisodeButton && nextEpisode != null) {
                                onPlayNextEpisode(nextEpisode)
                                true
                            } else if (overlayState == OverlayState.NONE) {
                                if (media?.type == MediaType.LIVE) {
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastCenterClickTime < doubleClickTimeout) {
                                        if (media != null) {
                                            viewModel.getFullEpgForId(media.id)
                                        }
                                        overlayState = OverlayState.EPG_INFO
                                    } else {
                                        overlayState = OverlayState.QUICK_INFO
                                    }
                                    lastCenterClickTime = currentTime
                                } else {
                                    // För VOD, toggla Play/Pause
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                        isPlaying = false
                                    } else {
                                        exoPlayer.play()
                                        isPlaying = true
                                    }
                                    // Visa tidsstapeln kort vid paus/play
                                    showSeekFeedback = true
                                    seekJob?.cancel()
                                    seekJob = scope.launch {
                                        delay(3000)
                                        showSeekFeedback = false
                                    }
                                }
                                true
                            } else if (overlayState == OverlayState.QUICK_INFO) {
                                if (media != null) {
                                    viewModel.getFullEpgForId(media.id)
                                }
                                overlayState = OverlayState.EPG_INFO
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            when (overlayState) {
                                OverlayState.CHANNELS, OverlayState.CATEGORIES -> {
                                    overlayState = OverlayState.NONE
                                }
                                OverlayState.QUICK_INFO -> {
                                    overlayState = OverlayState.NONE
                                    onBackPressed()
                                }
                                OverlayState.EPG_INFO -> {
                                    overlayState = OverlayState.QUICK_INFO
                                }
                                OverlayState.NONE -> onBackPressed()
                                else -> overlayState = OverlayState.NONE
                            }
                            true
                        }
                        else -> false
                    }
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

        // --- NEXT EPISODE BUTTON ---
        AnimatedVisibility(
            visible = showNextEpisodeButton && nextEpisode != null,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 120.dp, end = 48.dp)
        ) {
            var isFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { nextEpisode?.let { onPlayNextEpisode(it) } },
                modifier = Modifier
                    .height(64.dp)
                    .width(280.dp)
                    .focusRequester(nextEpisodeButtonFocusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFocused) Color.White else MaterialTheme.colorScheme.primary,
                    contentColor = if (isFocused) Color.Black else Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("NÄSTA AVSNITT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(nextEpisode?.title ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            
            LaunchedEffect(showNextEpisodeButton) {
                if (showNextEpisodeButton) {
                    // Vi vill inte tvinga fokus direkt om man sitter och tittar, 
                    // men om man trycker på en knapp ska man kunna nå den.
                    // För TV är det bäst att INTE stjäla fokus automatiskt mitt i en scen.
                }
            }
        }

        // --- QUICK INFO OVERLAY (BOTTOM) ---
        AnimatedVisibility(
            visible = overlayState == OverlayState.QUICK_INFO && media != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val epg = viewModel.getEpgForId(media?.id ?: 0)
            val nextEpg = viewModel.getNextEpgForId(media?.id ?: 0)
            val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                color = Color.Black.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.large
            ) {
                Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = media?.title ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (epg != null) {
                            val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))
                            val stop = timeFormatter.format(Instant.ofEpochSecond(epg.stopTimestamp ?: 0))
                            Text(text = epg.title ?: "", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(text = "$start - $stop", style = MaterialTheme.typography.bodyLarge, color = Color.LightGray)
                            
                            val now = System.currentTimeMillis() / 1000
                            val progress = (now - (epg.startTimestamp ?: 0)).toFloat() / ((epg.stopTimestamp ?: 0) - (epg.startTimestamp ?: 0)).toFloat()
                            if (progress in 0f..1f) {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).clip(MaterialTheme.shapes.small))
                            }
                        } else {
                            Text(text = "Ingen programinfo", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                        }
                    }
                    
                    if (nextEpg != null) {
                        Spacer(modifier = Modifier.width(40.dp))
                        Column(modifier = Modifier.width(300.dp)) {
                            val nextStart = timeFormatter.format(Instant.ofEpochSecond(nextEpg.startTimestamp ?: 0))
                            Text(text = "NÄSTA ($nextStart)", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                            Text(text = nextEpg.title ?: "", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2)
                        }
                    }
                }
            }
        }

        // --- SIDE OVERLAY (CHANNELS & CATEGORIES) ---
        if (overlayState == OverlayState.CHANNELS || overlayState == OverlayState.CATEGORIES) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { overlayState = OverlayState.NONE })

                Row(modifier = Modifier.fillMaxHeight().wrapContentWidth()) {
                    // Categories
                    AnimatedVisibility(
                        visible = overlayState == OverlayState.CATEGORIES || overlayState == OverlayState.CHANNELS,
                        enter = slideInHorizontally(initialOffsetX = { -it }),
                        exit = slideOutHorizontally(targetOffsetX = { -it })
                    ) {
                        Row {
                            if (overlayState == OverlayState.CATEGORIES) {
                                LazyColumn(
                                    state = categoryListState,
                                    modifier = Modifier.fillMaxHeight().width(300.dp).background(Color.Black.copy(alpha = 0.95f)).padding(vertical = 16.dp)
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
                                                        scope.launch {
                                                            channelListState.scrollToItem(0)
                                                        }
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
                                Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.1f)))
                            }

                            // Channels
                            if (overlayState == OverlayState.CHANNELS || overlayState == OverlayState.CATEGORIES) {
                                val currentCategoryTitle = categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title ?: ""
                                Column(
                                    modifier = Modifier.fillMaxHeight().width(450.dp).background(Color.Black.copy(alpha = 0.9f))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = currentCategoryTitle,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Sök",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    LazyColumn(
                                        state = channelListState,
                                        modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)
                                    ) {
                                        itemsIndexed(playlist) { index, item ->
                                            ChannelListItem(
                                                item = item,
                                                isSelected = item.id == media?.id,
                                                epg = viewModel.getEpgForId(item.id),
                                                nextEpg = viewModel.getNextEpgForId(item.id),
                                                modifier = Modifier
                                                    .focusRequester(channelFocusRequesters.getOrPut(item.id) { FocusRequester() })
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
                            }
                        }
                    }
                }
            }
        }

        // --- FULL EPG INFO (MODAL) ---
        if (overlayState == OverlayState.EPG_INFO && media != null) {
            val fullEpg = viewModel.getFullEpgForId(media.id)
            val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .onKeyEvent { 
                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            overlayState = OverlayState.QUICK_INFO
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
            .clickable { onClick() }, 
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
            .height(64.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable { onClick() },
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = item.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(4.dp).fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title ?: "", 
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), 
                        color = Color.White, 
                        maxLines = 1, 
                        modifier = Modifier.weight(1f), 
                        overflow = TextOverflow.Ellipsis
                    )
                    if (epg != null) {
                        val start = formatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))
                        val stop = formatter.format(Instant.ofEpochSecond(epg.stopTimestamp ?: 0))
                        Text(
                            text = "$start - $stop", 
                            style = MaterialTheme.typography.labelSmall, 
                            color = if (hasFocus) Color.White else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = epg?.title ?: "Ingen programinfo", 
                        style = MaterialTheme.typography.bodySmall, 
                        color = if (epg != null) (if (hasFocus) Color.White else Color.LightGray) else Color.Gray.copy(alpha = 0.4f), 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                    val now = System.currentTimeMillis() / 1000
                    val start = epg?.startTimestamp ?: 0L
                    val stop = epg?.stopTimestamp ?: 0L
                    if (start > 0 && stop > start && now in start..stop) {
                        val progress = (now - start).toFloat() / (stop - start).toFloat()
                        LinearProgressIndicator(
                            progress = { progress }, 
                            modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraSmall), 
                            color = if (hasFocus) Color.White else MaterialTheme.colorScheme.primary, 
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }
                }
            }
        }
    }
}
