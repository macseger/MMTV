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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.example.mmtv.player.MmtvPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

enum class OverlayState {
    NONE, CHANNELS, CATEGORIES, SUBTITLES, EPG_INFO
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
    viewModel: MediaViewModel
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val mmtvPlayerFactory = remember { MmtvPlayer(context) }
    val exoPlayer = remember { 
        mmtvPlayerFactory.createPlayer(bufferMs = 5000).apply {
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

    val channelListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val subtitleListState = rememberLazyListState()
    
    val mainFocusRequester = remember { FocusRequester() }
    val channelFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val categoryFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val subtitleFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    var availableSubtitles by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }

    LaunchedEffect(url) {
        val mediaItem = MediaItem.Builder().setUri(url).build()
        exoPlayer.setMediaItem(mediaItem)
        if (media?.type != MediaType.LIVE) {
            val savedPos = sessionManager.getPlaybackPosition(media?.id.toString())
            if (savedPos > 0) exoPlayer.seekTo(savedPos)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
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
            }
        }
        exoPlayer.addListener(listener)
        onDispose { mmtvPlayerFactory.releasePlayer() }
    }

    LaunchedEffect(overlayState) {
        when (overlayState) {
            OverlayState.CATEGORIES -> {
                val currentCatTitle = categories.find { it.items.any { item -> item.id == media?.id } }?.title
                val catIndex = categories.indexOfFirst { it.title == currentCatTitle }.coerceAtLeast(0)
                categoryListState.scrollToItem(catIndex)
                delay(10) // Mycket kort fördröjning för att undvika att klicket "blöder igenom"
                categoryFocusRequesters[catIndex]?.requestFocus()
            }
            OverlayState.CHANNELS -> {
                val index = playlist.indexOfFirst { it.id == media?.id }.coerceAtLeast(0)
                channelListState.scrollToItem(index)
                delay(10)
                channelFocusRequesters[playlist.getOrNull(index)?.id ?: -1]?.requestFocus()
            }
            OverlayState.SUBTITLES -> {
                delay(10)
                subtitleFocusRequesters[0]?.requestFocus()
            }
            OverlayState.EPG_INFO -> {
                media?.let { m ->
                    val catIndex = categories.indexOfFirst { it.items.any { item -> item.id == m.id } }
                    if (catIndex != -1) viewModel.prefetchEpgForCategory(catIndex)
                }
                mainFocusRequester.requestFocus()
            }
            else -> { mainFocusRequester.requestFocus() }
        }
    }

    fun performSeek(offsetMs: Long) {
        seekJob?.cancel()
        accumulatedSeekMs += offsetMs
        val totalSecs = (accumulatedSeekMs / 1000).toInt()
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        seekMessage = if (minutes != 0) {
            if (offsetMs > 0) "+$minutes:${String.format("%02d", Math.abs(seconds))}"
            else "-${Math.abs(minutes)}:${String.format("%02d", Math.abs(seconds))}"
        } else {
            if (offsetMs > 0) "+$seconds s" else "-${Math.abs(seconds)} s"
        }
        showSeekFeedback = true
        seekJob = scope.launch {
            delay(1200)
            val newPos = (exoPlayer.currentPosition + accumulatedSeekMs).coerceIn(0, exoPlayer.duration)
            exoPlayer.seekTo(newPos)
            accumulatedSeekMs = 0
            delay(1000)
            showSeekFeedback = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(mainFocusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (overlayState == OverlayState.NONE) {
                                if (media?.type == MediaType.LIVE) {
                                    overlayState = OverlayState.CHANNELS
                                } else {
                                    performSeek(-60000L) 
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
                                    performSeek(60000L)
                                    true
                                } else false
                            } else if (overlayState == OverlayState.CATEGORIES) {
                                overlayState = OverlayState.CHANNELS
                                true
                            } else if (overlayState == OverlayState.CHANNELS) {
                                overlayState = OverlayState.NONE
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (overlayState == OverlayState.NONE) {
                                overlayState = OverlayState.SUBTITLES
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (overlayState == OverlayState.NONE) {
                                if (media?.type != MediaType.LIVE) {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                } else {
                                    overlayState = OverlayState.EPG_INFO 
                                }
                                true
                            } else if (overlayState == OverlayState.CHANNELS) {
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            if (overlayState != OverlayState.NONE) {
                                overlayState = OverlayState.NONE
                                true
                            } else {
                                onBackPressed()
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = false; keepScreenOn = true } },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
            }
        }

        AnimatedVisibility(visible = !isPlaying && media?.type != MediaType.LIVE && overlayState == OverlayState.NONE, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Surface(color = Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.size(100.dp)) {
                Icon(Icons.Default.Pause, null, tint = Color.White, modifier = Modifier.padding(24.dp).fillMaxSize())
            }
        }

        if (showSeekFeedback) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.large) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(text = seekMessage, style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        val currentPos = (exoPlayer.currentPosition + accumulatedSeekMs).coerceIn(0, exoPlayer.duration)
                        val duration = exoPlayer.duration.coerceAtLeast(0L)
                        val progress = if (duration > 0) currentPos.toFloat() / duration.toFloat() else 0f
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(400.dp).height(12.dp).clip(MaterialTheme.shapes.small), color = MaterialTheme.colorScheme.primary, trackColor = Color.DarkGray)
                    }
                }
            }
        }

        // --- SIDE OVERLAY (CHANNELS & CATEGORIES) ---
        if (overlayState == OverlayState.CHANNELS || overlayState == OverlayState.CATEGORIES) {
            val showCategories = overlayState == OverlayState.CATEGORIES
            Box(modifier = Modifier.fillMaxSize()) {
                // Semi-transparent background for the whole screen
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { overlayState = OverlayState.NONE })

                Row(modifier = Modifier.fillMaxHeight().wrapContentWidth()) {
                    if (showCategories) {
                        // Column 1: Categories (320dp)
                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(320.dp)
                                .background(Color.Black.copy(alpha = 0.95f))
                                .padding(vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(categories) { index, category ->
                                val currentCatTitle = categories.find { it.items.any { item -> item.id == media?.id } }?.title
                                val isSelected = category.title == currentCatTitle
                                CategoryListItem(
                                    title = category.title ?: "",
                                    isSelected = isSelected,
                                    modifier = Modifier
                                        .focusRequester(categoryFocusRequesters.getOrPut(index) { FocusRequester() })
                                        .onKeyEvent {
                                            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                                overlayState = OverlayState.CHANNELS
                                                true
                                            } else false
                                        },
                                    onClick = { 
                                        onCategorySelected(index)
                                        overlayState = OverlayState.CHANNELS
                                    }
                                )
                            }
                        }
                        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.1f)))
                    }

                    // Column 2: Channels (500dp)
                    LazyColumn(
                        state = channelListState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(500.dp)
                            .background(Color.Black.copy(alpha = 0.92f))
                            .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 40.dp, end = 24.dp, start = 8.dp)
                    ) {
                        itemsIndexed(playlist) { index, item ->
                            val isCurrent = item.id == media?.id
                            ChannelListItem(
                                item = item,
                                isSelected = isCurrent,
                                epg = viewModel.getEpgForId(item.id),
                                nextEpg = viewModel.getNextEpgForId(item.id),
                                modifier = Modifier
                                    .focusRequester(channelFocusRequesters.getOrPut(item.id) { FocusRequester() })
                                    .onKeyEvent {
                                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            overlayState = OverlayState.CATEGORIES
                                            true
                                        } else if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            overlayState = OverlayState.NONE
                                            true
                                        } else false
                                    },
                                onClick = {
                                    overlayState = OverlayState.NONE
                                    onMediaSelected(item)
                                }
                            )
                        }
                    }
                }
            }
        }

        // --- FULL EPG INFO OVERLAY ---
        if (overlayState == OverlayState.EPG_INFO && media != null) {
            val fullEpg = viewModel.getFullEpgForId(media.id)
            val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { overlayState = OverlayState.NONE },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .fillMaxHeight(0.85f)
                        .clickable(enabled = false) {},
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(modifier = Modifier.padding(32.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = media.title ?: "Programinfo",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { overlayState = OverlayState.NONE }) {
                                Icon(Icons.Default.Close, contentDescription = "Stäng")
                            }
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        
                        if (fullEpg.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Ingen programinformation tillgänglig för denna kanal.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(fullEpg) { _, epg ->
                                    val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))
                                    val stop = timeFormatter.format(Instant.ofEpochSecond(epg.stopTimestamp ?: 0))
                                    val now = System.currentTimeMillis() / 1000
                                    val isCurrent = (epg.startTimestamp ?: 0) <= now && (epg.stopTimestamp ?: 0) > now
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp)
                                            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent, MaterialTheme.shapes.small)
                                            .padding(if (isCurrent) 16.dp else 0.dp)
                                    ) {
                                        Text(
                                            text = "$start - $stop",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = epg.title ?: "Inget namn",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (!epg.description.isNullOrBlank()) {
                                            Text(
                                                text = epg.description,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Subtitle menu
        AnimatedVisibility(visible = overlayState == OverlayState.SUBTITLES, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Card(modifier = Modifier.width(300.dp).wrapContentHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Undertexter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    if (availableSubtitles.isEmpty()) {
                        Text("Inga undertexter tillgängliga", color = Color.Gray)
                    } else {
                        LazyColumn(state = subtitleListState) {
                            item {
                                SubtitleOptionItem(label = "Ingen undertext", isSelected = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), modifier = Modifier.focusRequester(subtitleFocusRequesters.getOrPut(0) { FocusRequester() }), onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setDisabledTrackTypes(setOf(C.TRACK_TYPE_TEXT)).build()
                                    media?.let { sessionManager.saveSubtitlePreference(it.id, null) }
                                    overlayState = OverlayState.NONE
                                })
                            }
                            itemsIndexed(availableSubtitles) { index, group ->
                                val trackName = group.getTrackFormat(0).language ?: "Spår ${index + 1}"
                                SubtitleOptionItem(label = trackName.uppercase(), isSelected = exoPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) && group.isSelected, modifier = Modifier.focusRequester(subtitleFocusRequesters.getOrPut(index + 1) { FocusRequester() }), onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).setDisabledTrackTypes(emptySet()).build()
                                    media?.let { sessionManager.saveSubtitlePreference(it.id, trackName) }
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
    Surface(modifier = modifier.fillMaxWidth().onFocusChanged { hasFocus = it.isFocused }.clickable { onClick() }, color = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent) {
        Text(text = title, modifier = Modifier.padding(24.dp, 16.dp), style = MaterialTheme.typography.bodyLarge, color = if (isSelected || hasFocus) Color.White else Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ChannelListItem(
    item: MediaSource, 
    isSelected: Boolean, 
    epg: EpgListing?, 
    onClick: () -> Unit,
    modifier: Modifier = Modifier, 
    nextEpg: EpgListing? = null
) {
    var hasFocus by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    Surface(
        modifier = modifier.fillMaxWidth().onFocusChanged { hasFocus = it.isFocused }.clickable { onClick() },
        color = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) 
                else if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else Color.Transparent
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title ?: "Okänd kanal", 
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), 
                    color = if (isSelected || hasFocus) Color.White else Color.LightGray, 
                    maxLines = 1, 
                    modifier = Modifier.weight(1f), 
                    overflow = TextOverflow.Ellipsis
                )
                if (epg != null && epg.startTimestamp != null && epg.stopTimestamp != null) {
                    val start = formatter.format(Instant.ofEpochSecond(epg.startTimestamp))
                    val stop = formatter.format(Instant.ofEpochSecond(epg.stopTimestamp))
                    Text(
                        text = "$start - $stop", 
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasFocus) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (epg != null) {
                Text(
                    text = epg.title ?: "", 
                    style = MaterialTheme.typography.titleMedium, 
                    color = if (hasFocus) Color.White else Color.White.copy(alpha = 0.9f), 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                val now = System.currentTimeMillis() / 1000
                val start = epg.startTimestamp ?: 0L
                val stop = epg.stopTimestamp ?: 0L
                if (start > 0 && stop > start && now in start..stop) {
                    val progress = (now - start).toFloat() / (stop - start).toFloat()
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp), color = if (hasFocus) Color.White else MaterialTheme.colorScheme.primary, trackColor = Color.DarkGray)
                }
            }
            
            if (nextEpg != null) {
                val nextStart = formatter.format(Instant.ofEpochSecond(nextEpg.startTimestamp ?: 0))
                Text(
                    text = "NÄSTA ($nextStart): ${nextEpg.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasFocus) Color.White.copy(alpha = 0.7f) else Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
