package com.example.mmtv.ui.components

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.MediaSource
import com.example.mmtv.ui.FavoriteTimelineRow
import com.example.mmtv.ui.FavoriteTimelineState
import com.example.mmtv.ui.FavoriteTimelineStatus
import com.example.mmtv.ui.theme.AccentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

private val TimelineChannelWidth = 240.dp
private val TimelineHeaderHeight = 52.dp
private val TimelineRowHeight = 72.dp
private const val TimelineDpPerMinute = 3.5f
private const val TimelineMarkerSeconds = 30 * 60L

private data class TimelineProgramUi(
    val listing: EpgListing,
    val clippedStartTimestamp: Long,
    val clippedEndTimestamp: Long,
    val x: Dp,
    val width: Dp
)

private data class TimelineRowUi(
    val source: FavoriteTimelineRow,
    val programs: List<TimelineProgramUi>
)

private data class TimelineMarkerUi(
    val label: String,
    val x: Dp
)

@Composable
fun FavoriteEpgTimeline(
    state: FavoriteTimelineState,
    onChannelSelected: (MediaSource) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialChannelId: Int? = null
) {
    BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF20A0A0A))
    ) {
        when (state.status) {
            FavoriteTimelineStatus.IDLE,
            FavoriteTimelineStatus.LOADING -> TimelineMessage(
                title = "Förbereder favoriternas TV-guide",
                message = "Hämtar programinformation...",
                showProgress = true
            )

            FavoriteTimelineStatus.EMPTY -> TimelineMessage(
                title = "Inga favoritkanaler ännu",
                message = "Här visas endast kanaler som du lagt till som favoriter.\n" +
                    "Lägg till en favorit genom att öppna infopanelen på en kanal och välja Favorit."
            )

            FavoriteTimelineStatus.ERROR -> TimelineMessage(
                title = "TV-guiden kunde inte visas",
                message = state.errorMessage ?: "Kunde inte läsa in programinformationen."
            )

            FavoriteTimelineStatus.READY -> {
                if (state.rows.isEmpty()) {
                    TimelineMessage(
                        title = "Inga favoritkanaler ännu",
                        message = "Här visas endast kanaler som du lagt till som favoriter.\n" +
                            "Lägg till en favorit genom att öppna infopanelen på en kanal och välja Favorit."
                    )
                } else if (state.windowEndTimestamp <= state.windowStartTimestamp) {
                    TimelineMessage(
                        title = "TV-guiden kunde inte visas",
                        message = "Tidsintervallet för programguiden är ogiltigt."
                    )
                } else {
                    key(state.cacheKey) {
                        FavoriteTimelineReady(
                            rows = state.rows,
                            windowStartTimestamp = state.windowStartTimestamp,
                            windowEndTimestamp = state.windowEndTimestamp,
                            initialChannelId = initialChannelId,
                            onChannelSelected = onChannelSelected,
                            onDismiss = onDismiss
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteTimelineReady(
    rows: List<FavoriteTimelineRow>,
    windowStartTimestamp: Long,
    windowEndTimestamp: Long,
    initialChannelId: Int?,
    onChannelSelected: (MediaSource) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val timelineRows = remember(rows, windowStartTimestamp, windowEndTimestamp) {
        prepareTimelineRows(rows, windowStartTimestamp, windowEndTimestamp)
    }
    val markers = remember(windowStartTimestamp, windowEndTimestamp) {
        prepareTimelineMarkers(windowStartTimestamp, windowEndTimestamp)
    }
    val timelineWidth = remember(windowStartTimestamp, windowEndTimestamp) {
        (((windowEndTimestamp - windowStartTimestamp) / 60f) * TimelineDpPerMinute).dp
    }

    var nowTimestamp by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTimestamp = System.currentTimeMillis() / 1000
        }
    }

    val initialChannelIndex = remember(rows, initialChannelId) {
        rows.indexOfFirst { it.channel.id == initialChannelId }.takeIf { it >= 0 } ?: 0
    }
    val initialAnchorTimestamp = remember(windowStartTimestamp, windowEndTimestamp) {
        nowTimestamp.coerceIn(windowStartTimestamp, windowEndTimestamp - 1)
    }
    var selectedChannelIndex by remember { mutableIntStateOf(initialChannelIndex) }
    var focusTimestamp by remember { mutableLongStateOf(initialAnchorTimestamp) }
    var selectedProgramIndex by remember {
        mutableIntStateOf(findProgramIndex(timelineRows[initialChannelIndex].programs, initialAnchorTimestamp))
    }
    var horizontalOffsetPx by remember { mutableIntStateOf(0) }
    var timelineViewportWidthPx by remember { mutableIntStateOf(0) }
    var initialOffsetApplied by remember { mutableStateOf(false) }

    fun maximumOffsetPx(): Int {
        val timelineWidthPx = with(density) { timelineWidth.roundToPx() }
        return (timelineWidthPx - timelineViewportWidthPx).coerceAtLeast(0)
    }

    fun keepProgramVisible(program: TimelineProgramUi?) {
        if (timelineViewportWidthPx <= 0) return
        val marginPx = with(density) { 24.dp.roundToPx() }
        val programStartPx = with(density) { program?.x?.roundToPx() }
            ?: (((focusTimestamp - windowStartTimestamp) / 60f) * TimelineDpPerMinute * density.density).toInt()
        val programEndPx = program?.let { item ->
            with(density) { (item.x + item.width).roundToPx() }
        } ?: programStartPx

        horizontalOffsetPx = when {
            programStartPx < horizontalOffsetPx + marginPx -> programStartPx - marginPx
            programEndPx > horizontalOffsetPx + timelineViewportWidthPx - marginPx ->
                programEndPx - timelineViewportWidthPx + marginPx
            else -> horizontalOffsetPx
        }.coerceIn(0, maximumOffsetPx())
    }

    fun keepChannelVisible(index: Int) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) {
            coroutineScope.launch { listState.scrollToItem(index) }
            return
        }
        val firstVisible = visibleItems.first().index
        val lastVisible = visibleItems.last().index
        when {
            index < firstVisible -> coroutineScope.launch { listState.scrollToItem(index) }
            index > lastVisible -> {
                val target = (index - visibleItems.size + 1).coerceAtLeast(0)
                coroutineScope.launch { listState.scrollToItem(target) }
            }
        }
    }

    fun moveChannel(delta: Int) {
        val newIndex = (selectedChannelIndex + delta).coerceIn(0, timelineRows.lastIndex)
        if (newIndex == selectedChannelIndex) return
        selectedChannelIndex = newIndex
        selectedProgramIndex = findProgramIndex(timelineRows[newIndex].programs, focusTimestamp)
        keepChannelVisible(newIndex)
        keepProgramVisible(timelineRows[newIndex].programs.getOrNull(selectedProgramIndex))
    }

    fun moveProgram(delta: Int) {
        val programs = timelineRows[selectedChannelIndex].programs
        if (programs.isEmpty()) return
        val currentIndex = selectedProgramIndex.takeIf { it in programs.indices }
            ?: findProgramIndex(programs, focusTimestamp).coerceAtLeast(0)
        val newIndex = (currentIndex + delta).coerceIn(0, programs.lastIndex)
        selectedProgramIndex = newIndex
        val program = programs[newIndex]
        focusTimestamp = program.clippedStartTimestamp +
            ((program.clippedEndTimestamp - program.clippedStartTimestamp) / 2)
        keepProgramVisible(program)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (initialChannelIndex > 0) listState.scrollToItem(initialChannelIndex)
    }

    LaunchedEffect(timelineViewportWidthPx) {
        if (timelineViewportWidthPx > 0 && !initialOffsetApplied) {
            initialOffsetApplied = true
            val targetProgram = timelineRows[selectedChannelIndex].programs.getOrNull(selectedProgramIndex)
            val targetPx = with(density) {
                targetProgram?.x?.roundToPx()
            } ?: (((focusTimestamp - windowStartTimestamp) / 60f) *
                TimelineDpPerMinute * density.density).toInt()
            horizontalOffsetPx = (targetPx - (timelineViewportWidthPx * 0.3f).toInt())
                .coerceIn(0, maximumOffsetPx())
            keepProgramVisible(targetProgram)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveChannel(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveChannel(1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveProgram(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveProgram(1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onChannelSelected(timelineRows[selectedChannelIndex].source.channel)
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TimelineHeader(
                markers = markers,
                timelineWidth = timelineWidth,
                horizontalOffsetPx = horizontalOffsetPx,
                onViewportWidthChanged = { timelineViewportWidthPx = it }
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = timelineRows,
                    key = { _, row -> row.source.channel.id },
                    contentType = { _, _ -> "favorite_timeline_channel" }
                ) { rowIndex, row ->
                    FavoriteTimelineChannelRow(
                        index = rowIndex,
                        row = row,
                        selectedChannelIndex = selectedChannelIndex,
                        selectedProgramIndex = selectedProgramIndex,
                        timelineWidth = timelineWidth,
                        horizontalOffsetPx = horizontalOffsetPx,
                        nowTimestamp = nowTimestamp
                    )
                }
            }
        }

        val nowX = (((nowTimestamp - windowStartTimestamp) / 60f) *
            TimelineDpPerMinute * density.density).toInt() - horizontalOffsetPx
        if (nowTimestamp in windowStartTimestamp until windowEndTimestamp &&
            nowX in 0..timelineViewportWidthPx
        ) {
            val channelWidthPx = with(density) { TimelineChannelWidth.roundToPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(channelWidthPx + nowX, 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(AccentColor)
            )
            Text(
                text = "NU",
                color = Color.Black,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .offset { IntOffset(channelWidthPx + nowX - with(density) { 12.dp.roundToPx() }, 2.dp.roundToPx()) }
                    .background(AccentColor, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun TimelineHeader(
    markers: List<TimelineMarkerUi>,
    timelineWidth: Dp,
    horizontalOffsetPx: Int,
    onViewportWidthChanged: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineHeaderHeight)
            .background(Color(0xFF111111))
    ) {
        Box(
            modifier = Modifier
                .width(TimelineChannelWidth)
                .fillMaxHeight()
                .background(Color(0xFF080808))
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column {
                Text(
                    text = "FAVORITER",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "TV-GUIDE",
                    color = AccentColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { onViewportWidthChanged(it.width) }
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(-horizontalOffsetPx, 0) }
                    .width(timelineWidth)
                    .fillMaxHeight()
            ) {
                markers.forEach { marker ->
                    Box(
                        modifier = Modifier
                            .offset(x = marker.x)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.12f))
                    )
                    Text(
                        text = marker.label,
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .offset(x = marker.x)
                            .padding(start = 10.dp, top = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteTimelineChannelRow(
    index: Int,
    row: TimelineRowUi,
    selectedChannelIndex: Int,
    selectedProgramIndex: Int,
    timelineWidth: Dp,
    horizontalOffsetPx: Int,
    nowTimestamp: Long
) {
    val isSelectedChannel = index == selectedChannelIndex
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineRowHeight)
            .border(0.5.dp, Color.White.copy(alpha = 0.05f))
    ) {
        TimelineChannelCell(
            channel = row.source.channel,
            selected = isSelectedChannel
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(if (isSelectedChannel) Color.White.copy(alpha = 0.025f) else Color.Transparent)
        ) {
            if (row.programs.isEmpty()) {
                Text(
                    text = "Ingen EPG-information",
                    color = if (isSelectedChannel) Color.White else Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 18.dp, top = 25.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(-horizontalOffsetPx, 0) }
                        .width(timelineWidth)
                        .fillMaxHeight()
                ) {
                    row.programs.forEachIndexed { programIndex, program ->
                        val selected = isSelectedChannel && programIndex == selectedProgramIndex
                        val current = nowTimestamp in
                            program.clippedStartTimestamp until program.clippedEndTimestamp
                        Box(
                            modifier = Modifier
                                .offset(x = program.x)
                                .width(program.width)
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        selected -> AccentColor.copy(alpha = 0.28f)
                                        current -> AccentColor.copy(alpha = 0.12f)
                                        else -> Color(0xFF202020)
                                    }
                                )
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = when {
                                        selected -> AccentColor
                                        current -> AccentColor.copy(alpha = 0.45f)
                                        else -> Color.White.copy(alpha = 0.08f)
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = program.listing.title?.takeIf { it.isNotBlank() }
                                    ?: "Programinformation saknas",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected || current) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineChannelCell(
    channel: MediaSource,
    selected: Boolean
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .width(TimelineChannelWidth)
            .fillMaxHeight()
            .padding(4.dp)
            .clip(shape)
            .background(if (selected) AccentColor.copy(alpha = 0.2f) else Color(0xFF0C0C0C))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) AccentColor else Color.Transparent,
                shape = shape
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            val picon = channel.resolvedIcon?.takeIf { it.isNotBlank() }
                ?: channel.icon?.takeIf { it.isNotBlank() }
            AsyncImage(
                model = picon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (picon == null) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = channel.title?.takeIf { it.isNotBlank() } ?: "Kanal",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TimelineMessage(
    title: String,
    message: String,
    showProgress: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = AccentColor,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(22.dp))
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

private fun prepareTimelineRows(
    rows: List<FavoriteTimelineRow>,
    windowStartTimestamp: Long,
    windowEndTimestamp: Long
): List<TimelineRowUi> = rows.map { row ->
    val programs = row.programs.mapNotNull { listing ->
        val start = listing.startTimestamp ?: return@mapNotNull null
        val end = listing.stopTimestamp ?: return@mapNotNull null
        val clippedStart = max(start, windowStartTimestamp)
        val clippedEnd = min(end, windowEndTimestamp)
        if (clippedEnd <= clippedStart) return@mapNotNull null

        val x = (((clippedStart - windowStartTimestamp) / 60f) * TimelineDpPerMinute).dp
        val width = (((clippedEnd - clippedStart) / 60f) * TimelineDpPerMinute).dp
        TimelineProgramUi(
            listing = listing,
            clippedStartTimestamp = clippedStart,
            clippedEndTimestamp = clippedEnd,
            x = x,
            width = width
        )
    }
    TimelineRowUi(source = row, programs = programs)
}

private fun prepareTimelineMarkers(
    windowStartTimestamp: Long,
    windowEndTimestamp: Long
): List<TimelineMarkerUi> {
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    return buildList {
        var timestamp = windowStartTimestamp
        while (timestamp < windowEndTimestamp) {
            add(
                TimelineMarkerUi(
                    label = formatter.format(Instant.ofEpochSecond(timestamp)),
                    x = (((timestamp - windowStartTimestamp) / 60f) * TimelineDpPerMinute).dp
                )
            )
            timestamp += TimelineMarkerSeconds
        }
    }
}

private fun findProgramIndex(
    programs: List<TimelineProgramUi>,
    timestamp: Long
): Int {
    if (programs.isEmpty()) return -1
    val containingIndex = programs.indexOfFirst {
        timestamp in it.clippedStartTimestamp until it.clippedEndTimestamp
    }
    if (containingIndex >= 0) return containingIndex

    return programs.indices.minByOrNull { index ->
        val program = programs[index]
        when {
            timestamp < program.clippedStartTimestamp -> program.clippedStartTimestamp - timestamp
            timestamp >= program.clippedEndTimestamp -> timestamp - program.clippedEndTimestamp
            else -> 0L
        }
    } ?: -1
}