package com.example.mmtv.ui

import com.example.mmtv.ui.theme.FocusBorderColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import android.util.Log
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.HomeDiscoveryItem
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

private const val HOME_FOCUS_LOG_TAG = "MMTV_HOME_FOCUS" // TEMP diagnostics

@Composable
fun HomeScreen(
    viewModel: MediaViewModel,
    onNavigate: (String) -> Unit,
    onMediaSelected: (MediaSource) -> Unit,
    resetToTopToken: Int = 0,
    topBarFocusRequester: FocusRequester? = null
) {
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val uiState = viewModel.uiState
    val history = uiState.history
    val discoveryMovies = viewModel.discoveryMovies
    val discoverySeries = viewModel.discoverySeries
    val homeLiveChannels = viewModel.homeLiveChannels

    val favoriteMovies = favorites.filter { it.type == MediaType.MOVIE }
    val favoriteSeries = favorites.filter { it.type == MediaType.SERIES }
    val filteredHistory = history.filter { it.type != MediaType.LIVE }

    val homeRowKeys = buildList {
        if (homeLiveChannels.isNotEmpty()) add("live")
        if (filteredHistory.isNotEmpty()) add("continue")
        if (discoveryMovies.isNotEmpty()) add("trending_movies")
        if (discoverySeries.isNotEmpty()) add("trending_series")
        if (recentlyAdded.isNotEmpty()) add("recently_added")
        if (favoriteMovies.isNotEmpty()) add("favorite_movies")
        if (favoriteSeries.isNotEmpty()) add("favorite_series")
    }
    val homeRowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val homeRowListStates = remember { mutableMapOf<String, LazyListState>() }
    var focusedHomeRowKey by remember { mutableStateOf<String?>(null) }
    val homeGridState = rememberLazyGridState()
    val homeFocusScope = rememberCoroutineScope()
    var verticalFocusJob by remember { mutableStateOf<Job?>(null) }
    var appliedResetToTopToken by rememberSaveable { mutableIntStateOf(0) }
    fun homeRowFocusRequester(key: String): FocusRequester =
        homeRowFocusRequesters.getOrPut(key) { FocusRequester() }
    fun homeRowListState(key: String): LazyListState =
        homeRowListStates.getOrPut(key) { LazyListState() }
    val homeRowAnchors = remember(homeRowKeys) {
        homeRowKeys.withIndex().associate { (index, key) -> key to index }
    }
    fun homeRowItemModifier(key: String, index: Int): Modifier =
        if (index == 0) Modifier.focusRequester(homeRowFocusRequester(key)) else Modifier

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var focusedLiveId by remember { mutableStateOf<Int?>(null) }
    var presentedLiveId by remember { mutableStateOf<Int?>(null) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }

    // 2. Senaste spelade TV-kanalen för mini-spelaren
    /*
    val lastLiveMedia = remember(history) { 
        history.find { it.type == MediaType.LIVE } 
    }
    var miniPlayer by remember { mutableStateOf<Player?>(null) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    // Vi triggar omspelning om lastLiveMedia ändras eller när vi kommer tillbaka till skärmen
    LaunchedEffect(lastLiveMedia) {
        if (lastLiveMedia != null && miniPlayer != null) {
            val login = sessionManager.getLogin()
            if (login != null) {
                val (host, user, pass) = login
                val streamUrl = "$host/live/$user/$pass/${lastLiveMedia.id}.ts"
                miniPlayer?.setMediaItem(MediaItem.fromUri(streamUrl))
                miniPlayer?.prepare()
            }
        }
    }

    DisposableEffect(lifecycleOwner, lastLiveMedia) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    if (lastLiveMedia != null && miniPlayer == null) {
                        val player = MmtvPlayer(context).createPlayer(isLive = true).apply {
                            repeatMode = Player.REPEAT_MODE_OFF
                            playWhenReady = true
                            volume = 0f
                            
                            val login = sessionManager.getLogin()
                            if (login != null) {
                                val (host, user, pass) = login
                                val streamUrl = "$host/live/$user/$pass/${lastLiveMedia.id}.ts"
                                setMediaItem(MediaItem.fromUri(streamUrl))
                                prepare()
                            }
                        }
                        miniPlayer = player
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    miniPlayer?.stop()
                    miniPlayer?.release()
                    miniPlayer = null
                }
                else -> {}
            }
        }

        // Om vi redan är i RESUMED-läge (t.ex. vid omstart), kör logiken direkt
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            if (lastLiveMedia != null && miniPlayer == null) {
                val player = MmtvPlayer(context).createPlayer(isLive = true).apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    playWhenReady = true
                    volume = 0f
                    
                    val login = sessionManager.getLogin()
                    if (login != null) {
                        val (host, user, pass) = login
                        val streamUrl = "$host/live/$user/$pass/${lastLiveMedia.id}.ts"
                        setMediaItem(MediaItem.fromUri(streamUrl))
                        prepare()
                    }
                }
                miniPlayer = player
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            miniPlayer?.stop()
            miniPlayer?.release()
            miniPlayer = null
        }
    }

    */
    LaunchedEffect(history, uiState.isLoading, uiState.liveCategories) {
        viewModel.loadHomeLiveChannels(history)
    }
    LaunchedEffect(homeLiveChannels) {
        val firstId = homeLiveChannels.firstOrNull()?.media?.id
        if (presentedLiveId == null || homeLiveChannels.none { it.media.id == presentedLiveId }) {
            presentedLiveId = firstId
        }
    }
    LaunchedEffect(resetToTopToken, homeRowKeys) {
        if (resetToTopToken <= 0 || resetToTopToken == appliedResetToTopToken) {
            return@LaunchedEffect
        }
        val firstRowKey = homeRowKeys.firstOrNull() ?: return@LaunchedEffect
        val firstRowIndex = homeRowAnchors[firstRowKey] ?: return@LaunchedEffect
        verticalFocusJob?.cancel()
        homeGridState.scrollToItem(firstRowIndex)
        homeRowListState(firstRowKey).scrollToItem(0)
        snapshotFlow {
            homeGridState.layoutInfo.visibleItemsInfo.any { it.index == firstRowIndex }
        }.first { it }
        withFrameNanos { }
        focusedHomeRowKey = firstRowKey
        homeRowFocusRequester(firstRowKey).requestFocus()
        appliedResetToTopToken = resetToTopToken
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            currentTime = System.currentTimeMillis() / 1000
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            val direction = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
            if (direction == 0) return@onPreviewKeyEvent false
            val currentIndex = homeRowKeys.indexOf(focusedHomeRowKey)
            if (currentIndex < 0) return@onPreviewKeyEvent false
            val targetKey = homeRowKeys.getOrNull(currentIndex + direction)
            if (targetKey != null) {
                val targetIndex = homeRowAnchors[targetKey] ?: return@onPreviewKeyEvent false
                val directionLabel = if (direction > 0) "DOWN" else "UP"
                fun gridDebugState(): String {
                    val layout = homeGridState.layoutInfo
                    val targetInfo = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                    return "gridFirst=${homeGridState.firstVisibleItemIndex} " +
                        "gridOffset=${homeGridState.firstVisibleItemScrollOffset} " +
                        "targetOffset=${targetInfo?.offset} targetSize=${targetInfo?.size} " +
                        "viewportStart=${layout.viewportStartOffset} " +
                        "viewportEnd=${layout.viewportEndOffset}"
                }
                val targetRowState = homeRowListState(targetKey)
                Log.d(
                    HOME_FOCUS_LOG_TAG,
                    "TEMP event=$directionLabel source=$focusedHomeRowKey sourceIndex=$currentIndex " +
                        "sourceGridFirst=${homeGridState.firstVisibleItemIndex} " +
                        "sourceGridOffset=${homeGridState.firstVisibleItemScrollOffset} " +
                        "target=$targetKey targetIndex=$targetIndex " +
                        "targetRowFirst=${targetRowState.firstVisibleItemIndex} " +
                        "targetRowOffset=${targetRowState.firstVisibleItemScrollOffset}"
                )
                verticalFocusJob?.cancel()
                verticalFocusJob = homeFocusScope.launch {
                    Log.d(HOME_FOCUS_LOG_TAG, "TEMP before_grid_scroll event=$directionLabel target=$targetKey ${gridDebugState()}")
                    homeGridState.scrollToItem(targetIndex)
                    Log.d(HOME_FOCUS_LOG_TAG, "TEMP after_grid_scroll event=$directionLabel target=$targetKey ${gridDebugState()}")
                    targetRowState.scrollToItem(0)
                    Log.d(
                        HOME_FOCUS_LOG_TAG,
                        "TEMP after_row_scroll event=$directionLabel target=$targetKey " +
                            "rowFirst=${targetRowState.firstVisibleItemIndex} " +
                            "rowOffset=${targetRowState.firstVisibleItemScrollOffset}"
                    )
                    withFrameNanos { }
                    Log.d(HOME_FOCUS_LOG_TAG, "TEMP before_focus event=$directionLabel target=$targetKey ${gridDebugState()}")
                    runCatching { homeRowFocusRequester(targetKey).requestFocus() }
                    withFrameNanos { }
                    Log.d(HOME_FOCUS_LOG_TAG, "TEMP after_focus_next_frame event=$directionLabel target=$targetKey ${gridDebugState()}")
                }
                true
            } else false
        }
        .onKeyEvent { 
            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                if (topBarFocusRequester != null) {
                    topBarFocusRequester.requestFocus()
                    true
                } else {
                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                    true
                }
            } else false
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = homeGridState,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 48.dp)
            ) {
                if (homeLiveChannels.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "SENASTE LIVE KANALER",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            state = homeRowListState("live"),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp)
                        ) {
                            itemsIndexed(homeLiveChannels, key = { _, item -> item.media.id }) { index, item ->
                                val current = viewModel.getCachedCurrentEpgForId(item.media.id, currentTime) ?: item.currentProgram
                                HomeLiveCard(
                                    channel = item.media,
                                    currentProgram = current,
                                    modifier = homeRowItemModifier("live", index),
                                    onFocused = {
                                        focusedLiveId = it
                                        presentedLiveId = it
                                        focusedHomeRowKey = "live"
                                    },
                                    onClick = { onMediaSelected(item.media) }
                                )
                            }
                        }
                        homeLiveChannels.firstOrNull { it.media.id == presentedLiveId }?.let { focused ->
                            val program = viewModel.getCachedCurrentEpgForId(focused.media.id, currentTime) ?: focused.currentProgram
                            if (program != null) HomeLiveFocusInfo(program)
                        }
                    }
                }
                /*
                // Hero Section with MiniPlayer
                item(span = { GridItemSpan(maxLineSpan) }) {
                    var isHeroFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1A1A))
                            .onFocusChanged { isHeroFocused = it.isFocused }
                            .border(
                                width = if (isHeroFocused) 3.dp else 0.dp,
                                color = if (isHeroFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    miniPlayer?.stop()
                                    miniPlayer?.release()
                                    miniPlayer = null
                                    lastLiveMedia?.let { onMediaSelected(it) }
                                }
                            )
                    ) {
                        // MiniPlayer bakgrund
                        if (lastLiveMedia != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        // Vi låter PlayerView använda standard SurfaceView för prestanda, 
                                        // men ser till att den inte tar över hela skärmens Z-order.
                                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        layoutParams = android.view.ViewGroup.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                update = { view ->
                                    view.player = miniPlayer
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // Gradient för att texten ska synas bra
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                                            startY = 300f
                                        )
                                    )
                            )
                        } else {
                            // Standard bakgrund om ingen historik finns
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.DarkGray)
                            )
                        }

                        // Hero Text
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(48.dp)
                        ) {
                            if (lastLiveMedia != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "FORTSÄTT TITTA: ${lastLiveMedia.title}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            Text(
                                "Välkommen till MMTV",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Din ultimata TV-upplevelse",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // 1. Fortsätt titta (History)
                */
                if (filteredHistory.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(modifier = Modifier.padding(top = 0.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("FORTSÄTT TITTA", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(
                                state = homeRowListState("continue"),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp)
                            ) {
                                itemsIndexed(filteredHistory.take(15)) { index, item ->
                                    HistoryCard(
                                        media = item,
                                        viewModel = viewModel,
                                        modifier = homeRowItemModifier("continue", index),
                                        onFocused = { focusedHomeRowKey = "continue" },
                                        onClick = { onMediaSelected(item) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (discoveryMovies.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DiscoveryMediaRow(
                            title = "TRENDAR – FILMER",
                            items = discoveryMovies,
                            viewModel = viewModel,
                            listState = homeRowListState("trending_movies"),
                            focusRequester = homeRowFocusRequester("trending_movies"),
                            onItemFocused = { focusedHomeRowKey = "trending_movies" },
                            onMediaClick = onMediaSelected
                        )
                    }
                }
                if (discoverySeries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DiscoveryMediaRow(
                            title = "TRENDAR – SERIER",
                            items = discoverySeries,
                            viewModel = viewModel,
                            listState = homeRowListState("trending_series"),
                            focusRequester = homeRowFocusRequester("trending_series"),
                            onItemFocused = { focusedHomeRowKey = "trending_series" },
                            onMediaClick = onMediaSelected
                        )
                    }
                }

                // 2. Favoriter TV
                // 2. Nyligen tillagt
                if (recentlyAdded.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaRow(
                            title = "NYLIGEN TILLAGT",
                            items = recentlyAdded,
                            viewModel = viewModel,
                            listState = homeRowListState("recently_added"),
                            focusRequester = homeRowFocusRequester("recently_added"),
                            onItemFocused = { focusedHomeRowKey = "recently_added" },
                            onMediaClick = { onMediaSelected(it) }
                        )
                    }
                }

                // 4. Favoriter Film
                if (favoriteMovies.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaRow(
                            title = "FAVORITER FILM",
                            items = favoriteMovies,
                            viewModel = viewModel,
                            listState = homeRowListState("favorite_movies"),
                            focusRequester = homeRowFocusRequester("favorite_movies"),
                            onItemFocused = { focusedHomeRowKey = "favorite_movies" },
                            onMediaClick = { onMediaSelected(it) }
                        )
                    }
                }

                // 5. Favoriter Serier
                if (favoriteSeries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaRow(
                            title = "FAVORITER SERIER",
                            items = favoriteSeries,
                            viewModel = viewModel,
                            listState = homeRowListState("favorite_series"),
                            focusRequester = homeRowFocusRequester("favorite_series"),
                            onItemFocused = { focusedHomeRowKey = "favorite_series" },
                            onMediaClick = { onMediaSelected(it) }
                        )
                    }
                }

            }
        }

        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.primary)
        }

        // Overlay för uppdateringsstatus - Tas bort härifrån enligt önskemål (finns redan i sidofältet/nere till vänster i Main)
        /*
        val updateStatus = viewModel.updateStatus
        if (updateStatus != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 32.dp)
                    .animateContentSize(),
                color = Color.Black.copy(alpha = 0.9f),
                shape = RoundedCornerShape(50), // Mer rundad "piller"-form
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (updateStatus.contains("Uppdaterar")) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.Green,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        text = updateStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
        */
    }
}

@Composable
private fun HomeLiveCard(
    channel: MediaSource,
    currentProgram: EpgListing?,
    modifier: Modifier = Modifier,
    onFocused: (Int) -> Unit,
    onClick: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val picon = channel.resolvedIcon?.takeIf { it.isNotBlank() }
        ?: channel.icon?.takeIf { it.isNotBlank() }
    var piconLoadFailed by remember(picon) { mutableStateOf(false) }
    val progress = currentProgram?.let { program ->
        val start = program.startTimestamp ?: 0L
        val stop = program.stopTimestamp ?: 0L
        if (stop > start) {
            ((System.currentTimeMillis() / 1000 - start).toFloat() / (stop - start).toFloat())
                .coerceIn(0f, 1f)
        } else null
    }

    Column(
        modifier = modifier
            .width(160.dp)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocused(channel.id)
            }
            .scale(if (hasFocus) 1.01f else 1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.7f)
                .border(
                    width = if (hasFocus) 2.dp else 1.dp,
                    color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF17191C)),
                contentAlignment = Alignment.Center
            ) {
                if (picon != null && !piconLoadFailed) {
                    AsyncImage(
                        model = picon,
                        contentDescription = channel.title,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
                        contentScale = ContentScale.Fit,
                        onError = { piconLoadFailed = true }
                    )
                } else {
                    Text(
                        text = channel.title.orEmpty(),
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 7.dp)
                            .height(5.dp)
                            .offset(y = (-4).dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Black.copy(alpha = 0.55f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = currentProgram?.title.orEmpty(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (hasFocus) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (hasFocus) Color.White else Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeLiveFocusInfo(program: EpgListing) {
    Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) {
        Text(program.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = program.description.orEmpty(),
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            minLines = 3,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DiscoveryMediaRow(
    title: String,
    items: List<HomeDiscoveryItem>,
    viewModel: MediaViewModel,
    listState: LazyListState,
    focusRequester: FocusRequester? = null,
    onItemFocused: (() -> Unit)? = null,
    onMediaClick: (MediaSource) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp)
        ) {
            itemsIndexed(items.take(10), key = { _, item -> item.media.id }) { index, item ->
                val posterUrl = item.posterUrl?.takeIf { it.isNotBlank() }
                val presentationMedia = item.media.copy(
                    title = item.title,
                    icon = posterUrl ?: item.media.icon
                )
                MediaCard(
                    media = presentationMedia,
                    viewModel = viewModel,
                    modifier = Modifier
                        .then(if (index == 0 && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    onFocused = onItemFocused,
                    onClick = { onMediaClick(item.media) },
                    onToggleFavorite = { viewModel.toggleFavorite(item.media) }
                )
            }
        }
    }
}

@Composable
fun MediaRow(
    title: String, 
    items: List<MediaSource>, 
    viewModel: MediaViewModel, 
    listState: LazyListState,
    isHorizontal: Boolean = false,
    focusRequester: FocusRequester? = null,
    onItemFocused: (() -> Unit)? = null,
    onMediaClick: (MediaSource) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp)
        ) {
            itemsIndexed(items) { index, item ->
                val itemModifier = Modifier
                    .then(if (index == 0 && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                if (isHorizontal) {
                    HistoryCard(
                        media = item,
                        viewModel = viewModel,
                        modifier = itemModifier,
                        onFocused = onItemFocused,
                        onToggleFavorite = { viewModel.toggleFavorite(item) },
                        onClick = { onMediaClick(item) }
                    )
                } else {
                    MediaCard(
                        media = item,
                        viewModel = viewModel,
                        modifier = itemModifier,
                        onFocused = onItemFocused,
                        onClick = { onMediaClick(item) },
                        onToggleFavorite = { viewModel.toggleFavorite(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun SmallActionCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .height(60.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (hasFocus) 3.dp else 1.dp,
                color = if (hasFocus) FocusBorderColor else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        color = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Gray)
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (hasFocus) Color.White else Color.Gray)
        }
    }
}

@Composable
fun HistoryCard(
    media: MediaSource, 
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val displayIcon = viewModel.getIconForId(media.id, media.type, media.title) ?: media.icon

    Column(
        modifier = modifier
            .width(160.dp)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .scale(if (hasFocus) 1.05f else 1.0f)
            .onKeyEvent { keyEvent ->
                val isCenterKey = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                                 keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                val isRedKey = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_PROG_RED || 
                               keyEvent.nativeKeyEvent.keyCode == 183
                
                if (isRedKey && keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    onToggleFavorite?.invoke()
                    return@onKeyEvent true
                }

                if (isCenterKey) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (pressJob == null) {
                            pressJob = scope.launch {
                                delay(650)
                                onToggleFavorite?.invoke()
                                pressJob = null
                            }
                        }
                    } else if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                        val isLongPress = pressJob == null
                        pressJob?.cancel()
                        pressJob = null
                        if (!isLongPress) {
                            onClick()
                        }
                        return@onKeyEvent true
                    }
                    true
                } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .border(
                    width = if (hasFocus) 3.dp else 0.dp,
                    color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
                val isMovie = media.type == MediaType.MOVIE || media.type == MediaType.SERIES
                AsyncImage(
                    model = displayIcon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (displayIcon == null && media.icon == null) {
                    ChannelPlaceholder(media.title ?: "?", Modifier.fillMaxSize(), isMovie = isMovie)
                }
                
                if (media.type == MediaType.LIVE) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = media.title ?: "",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (hasFocus) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (hasFocus) Color.White else Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun HomeCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    
    // Använd primärfärg (turkos) vid fokus, annars grå/vit
    val contentColor = if (hasFocus) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)
    val iconSize = if (hasFocus) 22.dp else 20.dp

    Surface(
        modifier = modifier
            .wrapContentWidth()
            .height(48.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.1f else 1.0f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(10.dp),
        border = if (hasFocus) androidx.compose.foundation.BorderStroke(3.dp, FocusBorderColor) else null,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(iconSize),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (hasFocus) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                    fontSize = 13.sp
                ),
                color = contentColor,
                textAlign = TextAlign.Start
            )
        }
    }
}
