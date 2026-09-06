package com.example.mmtv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.MediaSource
import com.example.mmtv.ui.MediaViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EpgPreviewSection(
    focusedEpg: EpgListing?,
    focusedChannel: MediaSource?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .background(Color(0xFF0A0A0A))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Preview Window (Left)
        Surface(
            modifier = Modifier
                .aspectRatio(16f / 9f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
            color = Color.Black
        ) {
            // Flytta aldrig ExoPlayers videoyta till tidslinjen. Vissa TV-enheter kan
            // blockera huvudtråden flera sekunder när en aktiv decoder byter Surface.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = focusedChannel?.icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    contentScale = ContentScale.Fit
                )
                if (focusedChannel?.icon == null) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.size(40.dp))
                }
            }
        }

        // Details (Right)
        Column(modifier = Modifier.weight(1f)) {
            if (focusedEpg != null) {
                Text(
                    text = focusedEpg.title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
                val start = Instant.ofEpochSecond(focusedEpg.startTimestamp ?: 0).atZone(ZoneId.systemDefault()).toLocalTime()
                val stop = Instant.ofEpochSecond(focusedEpg.stopTimestamp ?: 0).atZone(ZoneId.systemDefault()).toLocalTime()
                val duration = ((focusedEpg.stopTimestamp ?: 0) - (focusedEpg.startTimestamp ?: 0)) / 60
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "${start.format(timeFormatter)} - ${stop.format(timeFormatter)}",
                        color = Color(0xFF64B5F6),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "$duration min", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    if (focusedChannel != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = focusedChannel.title ?: "", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = focusedEpg.description ?: "Ingen beskrivning tillgänglig.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = focusedChannel?.title ?: "Välj ett program",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
                Text("Ingen information tillgänglig", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun EpgGrid(
    channels: List<MediaSource>,
    viewModel: MediaViewModel,
    onChannelSelected: (MediaSource) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    
    val blueTheme = Color(0xFF1E88E5)
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        now = System.currentTimeMillis() / 1000
        while (true) {
            delay(10000)
            now = System.currentTimeMillis() / 1000
        }
    }
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    
    var focusedEpg by remember { mutableStateOf<EpgListing?>(null) }
    var focusedChannel by remember { mutableStateOf<MediaSource?>(null) }

    val horizontalScrollState = rememberScrollState()
    val listState = rememberLazyListState()
    
    // En TV måste kunna öppna guiden omedelbart. Att mäta 24 timmar med mycket
    // breda rader för varje synlig kanal låser vissa TV-processorer. Visa i stället
    // en kompakt, användbar tidsrymd från strax före nu till sex timmar framåt.
    val startTime = remember { ((now / 1800) * 1800) - 1800 }
    val timeStep = 30 * 60 // 30 minuters block
    val totalSteps = 14 // 7 timmar
    val pixelsPerMinute = 4f
    
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val initialScrollDp = 0.dp
        with(density) {
            horizontalScrollState.scrollTo(initialScrollDp.toPx().toInt())
        }
        
        val initialIdx = channels.indexOfFirst { it.id == viewModel.selectedMedia?.id }.coerceAtLeast(0)
        if (initialIdx > 0) {
            listState.scrollToItem(initialIdx)
        }
        
        delay(300)
        initialFocusRequester.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF050505))) {
        // 1. TOP SECTION: Preview & Info
        EpgPreviewSection(focusedEpg = focusedEpg, focusedChannel = focusedChannel)

        // 2. DATE & TIME BAR
        val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("sv", "SE")) }
        val dateText = remember(now) { Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).format(dateFormatter) }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A1A), Color.Black)))
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateText.uppercase(),
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).format(timeFormatter),
                color = blueTheme,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelLarge
            )
        }

        // 3. GRID HEADER: Timeline
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color(0xFF0F0F0F))
        ) {
            Box(
                modifier = Modifier.width(260.dp).fillMaxHeight().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("KANALER", color = Color.DarkGray, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            
            Row(modifier = Modifier.horizontalScroll(horizontalScrollState, enabled = false)) {
                repeat(totalSteps) { i ->
                    val timeTs = startTime + (i * timeStep)
                    val time = Instant.ofEpochSecond(timeTs).atZone(ZoneId.systemDefault()).toLocalTime()
                    val isNowBlock = now in timeTs until (timeTs + timeStep)
                    
                    Box(
                        modifier = Modifier
                            .width((30 * pixelsPerMinute).dp)
                            .fillMaxHeight()
                            .border(0.5.dp, Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = time.format(timeFormatter),
                            color = if (isNowBlock) blueTheme else Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = if (isNowBlock) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
        
        // 4. GRID BODY: Channels + Programs
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { index, channel ->
                    val isInitialTarget = channel.id == viewModel.selectedMedia?.id || (viewModel.selectedMedia == null && index == 0)
                    
                    EpgRow(
                        index = index + 1,
                        channel = channel,
                        viewModel = viewModel,
                        startTime = startTime,
                        themeColor = blueTheme,
                        pixelsPerMinute = pixelsPerMinute,
                        horizontalScrollState = horizontalScrollState,
                        initialFocusRequester = if (isInitialTarget) initialFocusRequester else null,
                        onChannelSelected = onChannelSelected,
                        onProgramFocused = { epg -> 
                            focusedEpg = epg
                            focusedChannel = channel
                            
                            val progStart = epg.startTimestamp ?: 0L
                            val progStop = epg.stopTimestamp ?: 0L
                            val startDp = ((progStart - startTime) / 60 * pixelsPerMinute).dp
                            val stopDp = ((progStop - startTime) / 60 * pixelsPerMinute).dp
                            
                            coroutineScope.launch {
                                try {
                                    val currentScrollDp = with(density) { horizontalScrollState.value.toDp() }
                                    val viewportWidthDp = 980.dp 
                                    
                                    // ENDAST bläddra horisontellt om programmet är HELT utanför vyn
                                    if (stopDp < currentScrollDp) {
                                        // Programmet slutar innan nuvarande vy -> Scrolla vänster
                                        horizontalScrollState.animateScrollTo(with(density) { (stopDp - viewportWidthDp + 60.dp).toPx().toInt() })
                                    } else if (startDp > (currentScrollDp + viewportWidthDp)) {
                                        // Programmet börjar efter nuvarande vy -> Scrolla höger
                                        horizontalScrollState.animateScrollTo(with(density) { (startDp - 20.dp).toPx().toInt() })
                                    }
                                } catch (e: Exception) {
                                    // Ignorera animeringsfel om vyn försvinner
                                }
                            }
                        }
                    )
                }
            }
            
            // "NU"-indikator
            Canvas(modifier = Modifier.fillMaxSize().padding(start = 260.dp)) {
                val nowX = (now - startTime).toFloat() / 60f * pixelsPerMinute
                val scrollOffset = horizontalScrollState.value.toFloat()
                val xPos = nowX * density.density - scrollOffset
                
                if (xPos >= 0 && xPos < size.width) {
                    drawLine(
                        color = blueTheme.copy(alpha = 0.4f),
                        start = androidx.compose.ui.geometry.Offset(xPos, 0f),
                        end = androidx.compose.ui.geometry.Offset(xPos, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
fun EpgRow(
    index: Int,
    channel: MediaSource,
    viewModel: MediaViewModel,
    startTime: Long,
    themeColor: Color,
    pixelsPerMinute: Float,
    horizontalScrollState: ScrollState,
    initialFocusRequester: FocusRequester?,
    onChannelSelected: (MediaSource) -> Unit,
    onProgramFocused: (EpgListing) -> Unit
) {
    // All data är förberedd per kategori. Raden gör endast snabba minnesläsningar.
    val epgList = viewModel.getCachedFullEpgForId(channel.id)
    val piconUrl = channel.icon
    val now = System.currentTimeMillis() / 1000

    // Raden har samma kompakta tidsrymd som rubriken ovan; skapa aldrig ett
    // dygns programblock i bakgrunden när guiden öppnas.
    val visibleEndTime = startTime + (7 * 3600)
    val visibleEpg = remember(epgList) {
        epgList.filter { (it.stopTimestamp ?: 0) > startTime && (it.startTimestamp ?: 0) < visibleEndTime }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp) // Ännu smalare radhöjd
            .border(0.5.dp, Color.White.copy(alpha = 0.03f))
    ) {
        var isChannelFocused by remember { mutableStateOf(false) }
        Surface(
            onClick = { onChannelSelected(channel) },
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
                .onFocusChanged { 
                    isChannelFocused = it.isFocused
                    if (it.isFocused) {
                        val liveEpg = epgList.find { e -> 
                            now in (e.startTimestamp ?: 0)..(e.stopTimestamp ?: 0) 
                        }
                        if (liveEpg != null) onProgramFocused(liveEpg)
                    }
                },
            color = if (isChannelFocused) themeColor.copy(alpha = 0.15f) else Color.Black
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text(
                    text = index.toString(),
                    color = if (isChannelFocused) Color.White else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp)
                )
                
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.03f))
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = piconUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    if (piconUrl == null) {
                        Icon(Icons.Default.Tv, null, tint = Color.DarkGray, modifier = Modifier.size(16.dp))
                    }
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                Text(
                    text = channel.title ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(horizontalScrollState, enabled = false)
                .background(Color(0xFF080808))
        ) {
            if (visibleEpg.isEmpty()) {
                Box(modifier = Modifier.width(2000.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Text("Ingen programinfo", color = Color.DarkGray, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
                }
            } else {
                visibleEpg.forEach { epg ->
                    val progStart = epg.startTimestamp ?: 0L
                    val progStop = epg.stopTimestamp ?: 0L
                    
                    if (progStop > startTime) {
                        val displayStart = if (progStart < startTime) startTime else progStart
                        val durationMin = (progStop - displayStart) / 60
                        val width = (durationMin * pixelsPerMinute).dp

                        if (width > 1.dp) {
                            val isLive = now in (epg.startTimestamp ?: 0)..(epg.stopTimestamp ?: 0)
                            ProgramBlock(
                                epg = epg,
                                width = width,
                                themeColor = themeColor,
                                modifier = Modifier,
                                onFocused = onProgramFocused,
                                onClick = { onChannelSelected(channel) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProgramBlock(
    epg: EpgListing, 
    width: Dp, 
    themeColor: Color,
    modifier: Modifier = Modifier,
    onFocused: (EpgListing) -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000
    val isLive = (epg.startTimestamp ?: 0) <= now && (epg.stopTimestamp ?: 0) > now
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .padding(0.5.dp)
            .onFocusChanged { 
                isFocused = it.isFocused
                if (it.isFocused) onFocused(epg)
            },
        color = when {
            isFocused -> themeColor.copy(alpha = 0.3f)
            isLive -> Color.White.copy(alpha = 0.04f)
            else -> Color.Transparent
        },
        shape = RoundedCornerShape(1.dp),
        border = if (isFocused) BorderStroke(1.5.dp, themeColor.copy(alpha = 0.7f)) else BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
    ) {
        // Optimering: Använd Box istället för Column om det inte behövs
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
            Text(
                text = epg.title ?: "Inget namn",
                color = if (isFocused) Color.White else if (isLive) themeColor else Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = if (isLive || isFocused) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart)
            )
            
            if (width > 80.dp) {
                val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
                val start = Instant.ofEpochSecond(epg.startTimestamp ?: 0).atZone(ZoneId.systemDefault()).toLocalTime()
                
                Text(
                    text = start.format(timeFormatter),
                    color = if (isFocused) Color.White.copy(alpha = 0.6f) else Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = if (isLive && width > 100.dp) 6.dp else 0.dp)
                )
            }
            
            if (isLive && width > 100.dp) {
                val progress = (now - (epg.startTimestamp ?: 0)).toFloat() / ((epg.stopTimestamp ?: 0) - (epg.startTimestamp ?: 0))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .align(Alignment.BottomStart)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(if (isFocused) Color.White else themeColor)
                    )
                }
            }
        }
    }
}
