package com.example.mmtv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch

@Composable
fun EpgGrid(
    channels: List<MediaSource>,
    viewModel: MediaViewModel,
    onChannelSelected: (MediaSource) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    
    val themeColor = viewModel.currentThemeColor
    val now = remember { System.currentTimeMillis() / 1000 }
    
    // Synkroniserad horisontell scroll för hela rutnätet
    val horizontalScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    
    // Vi visar 24 timmar, startar för 2 timmar sedan för att se vad som precis slutat
    val startTime = remember { ((now / 1800) * 1800) - 7200 } 
    val timeStep = 30 * 60 // 30 minuters block
    val totalSteps = 48 // 24 timmar
    val pixelsPerMinute = 8f // Skala för bredd
    
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    
    // Scrolla till nuvarande tid vid start
    LaunchedEffect(Unit) {
        val initialScroll = (120 * pixelsPerMinute).toInt() // 120 minuter (2 timmar)
        horizontalScrollState.scrollTo(initialScroll)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.98f))) {
        Column {
            // HEADER: Tidslinje (Följer med i horisontell scroll)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(Color(0xFF1A1A1A))
                    .drawWithContent {
                        drawContent()
                        // Markör för "NU"
                        val nowX = (now - startTime) / 60 * pixelsPerMinute
                        drawLine(
                            color = themeColor,
                            start = androidx.compose.ui.geometry.Offset(nowX + 250f - horizontalScrollState.value, 0f),
                            end = androidx.compose.ui.geometry.Offset(nowX + 250f - horizontalScrollState.value, 10000f),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
            ) {
                // Hörnbox (ovanför kanallogotyperna)
                Box(
                    modifier = Modifier.width(250.dp).fillMaxHeight().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text("KANALER", color = themeColor, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 2.sp)
                }
                
                // Tidsskalan
                Row(modifier = Modifier.horizontalScroll(horizontalScrollState, enabled = false)) {
                    repeat(totalSteps) { i ->
                        val time = Instant.ofEpochSecond(startTime + (i * timeStep))
                            .atZone(ZoneId.systemDefault())
                            .toLocalTime()
                        
                        Box(
                            modifier = Modifier.width((30 * pixelsPerMinute).dp).fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = time.format(timeFormatter),
                                color = if (i == 4) themeColor else Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = if (i == 4) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            
            // GRID: Kanaler + Program
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(channels) { channel ->
                    EpgRow(
                        channel = channel,
                        viewModel = viewModel,
                        startTime = startTime,
                        themeColor = themeColor,
                        pixelsPerMinute = pixelsPerMinute,
                        horizontalScrollState = horizontalScrollState,
                        onChannelSelected = onChannelSelected
                    )
                }
            }
        }
    }
}

@Composable
fun EpgRow(
    channel: MediaSource,
    viewModel: MediaViewModel,
    startTime: Long,
    themeColor: Color,
    pixelsPerMinute: Float,
    horizontalScrollState: ScrollState,
    onChannelSelected: (MediaSource) -> Unit
) {
    val epgList = viewModel.getFullEpgForId(channel.id, channel.title)
    val piconUrl = viewModel.getIconForId(channel.id, channel.title) ?: channel.icon
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .border(0.5.dp, Color.White.copy(alpha = 0.05f))
    ) {
        // Kanal-info (Fast vänsterkolumn)
        Surface(
            onClick = { onChannelSelected(channel) },
            modifier = Modifier.width(250.dp).fillMaxHeight(),
            color = Color(0xFF121212)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                AsyncImage(
                    model = piconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.03f)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = channel.title ?: "",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Program-slots
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(horizontalScrollState, enabled = false)
                .background(Color.Black)
        ) {
            if (epgList.isEmpty()) {
                Box(modifier = Modifier.width(3000.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Text("Ingen programinfo", color = Color.DarkGray, modifier = Modifier.padding(24.dp))
                }
            } else {
                epgList.forEach { epg ->
                    val progStart = epg.startTimestamp ?: 0L
                    val progStop = epg.stopTimestamp ?: 0L
                    
                    if (progStop > startTime) {
                        val durationMin = (progStop - (if (progStart < startTime) startTime else progStart)) / 60
                        val width = (durationMin * pixelsPerMinute).dp

                        if (width > 1.dp) {
                            ProgramBlock(
                                epg = epg,
                                width = width,
                                themeColor = themeColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProgramBlock(epg: EpgListing, width: Dp, themeColor: Color) {
    var isFocused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000
    val isLive = (epg.startTimestamp ?: 0) <= now && (epg.stopTimestamp ?: 0) > now
    
    Surface(
        onClick = { /* Kan lägga till detaljer här */ },
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(1.dp)
            .onFocusChanged { isFocused = it.isFocused },
        color = when {
            isFocused -> themeColor.copy(alpha = 0.4f)
            isLive -> Color.White.copy(alpha = 0.08f)
            else -> Color(0xFF1A1A1A)
        },
        shape = RoundedCornerShape(2.dp),
        border = if (isFocused) BorderStroke(2.dp, Color.White) else null
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = epg.title ?: "Inget namn",
                color = if (isFocused) Color.White else if (isLive) themeColor else Color.LightGray,
                fontSize = 14.sp,
                fontWeight = if (isLive || isFocused) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
            val start = Instant.ofEpochSecond(epg.startTimestamp ?: 0).atZone(ZoneId.systemDefault()).toLocalTime()
            val stop = Instant.ofEpochSecond(epg.stopTimestamp ?: 0).atZone(ZoneId.systemDefault()).toLocalTime()
            
            Text(
                text = "${start.format(timeFormatter)} - ${stop.format(timeFormatter)}",
                color = if (isFocused) Color.White.copy(alpha = 0.7f) else Color.Gray,
                fontSize = 12.sp
            )
            
            if (isLive && width > 100.dp) {
                Spacer(modifier = Modifier.height(4.dp))
                val progress = (now - (epg.startTimestamp ?: 0)).toFloat() / ((epg.stopTimestamp ?: 0) - (epg.startTimestamp ?: 0))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                    color = themeColor,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
            }
        }
    }
}
