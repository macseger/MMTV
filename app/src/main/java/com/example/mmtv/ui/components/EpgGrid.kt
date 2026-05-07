package com.example.mmtv.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.MediaSource
import com.example.mmtv.ui.MediaViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

@Composable
fun EpgGrid(
    channels: List<MediaSource>,
    viewModel: MediaViewModel,
    onChannelSelected: (MediaSource) -> Unit,
    onClose: () -> Unit
) {
    val themeColor = viewModel.currentThemeColor
    val now = remember { System.currentTimeMillis() / 1000 }
    
    // Vi visar 24 timmar, startar för 2 timmar sedan
    val startTime = remember { ((now / 1800) * 1800) - 7200 } 
    val timeStep = 30 * 60 // 30 minuters block
    val totalSteps = 48 // 24 timmar
    
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f))) {
        Column {
            // Header: Tidslinje
            Row(modifier = Modifier.fillMaxWidth().height(50.dp).background(Color(0xFF1A1A1A))) {
                Spacer(modifier = Modifier.width(200.dp)) // Plats för kanalnamn
                
                val scrollState = rememberScrollState()
                
                Row(modifier = Modifier.horizontalScroll(scrollState)) {
                    repeat(totalSteps) { i ->
                        val time = Instant.ofEpochSecond(startTime + (i * timeStep))
                            .atZone(ZoneId.systemDefault())
                            .toLocalTime()
                        
                        Box(
                            modifier = Modifier.width(200.dp).fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = time.format(timeFormatter),
                                color = Color.Gray,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            
            // Grid: Kanaler + Program
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(channels) { channel ->
                    EpgRow(
                        channel = channel,
                        viewModel = viewModel,
                        startTime = startTime,
                        themeColor = themeColor,
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
    onChannelSelected: (MediaSource) -> Unit
) {
    val epgList = viewModel.getFullEpgForId(channel.id, channel.title)
    val piconUrl = viewModel.getIconForId(channel.id, channel.title) ?: channel.icon
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .border(0.5.dp, Color.White.copy(alpha = 0.05f))
    ) {
        // Kanal-info (Fast vänsterkolumn)
        Surface(
            onClick = { onChannelSelected(channel) },
            modifier = Modifier.width(200.dp).fillMaxHeight(),
            color = Color(0xFF121212)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp)
            ) {
                AsyncImage(
                    model = piconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = channel.title ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Program-slots (Scrollbar horisontellt)
        // OBS: Detta är en förenklad grid. I en riktig TV-guide 
        // beräknas bredden baserat på (stopptid - starttid).
        val horizontalScrollState = rememberScrollState()
        
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(horizontalScrollState)
                .background(Color.Black)
        ) {
            if (epgList.isEmpty()) {
                Box(modifier = Modifier.width(2000.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Text("Ingen programinformation tillgänglig", color = Color.DarkGray, modifier = Modifier.padding(16.dp))
                }
            } else {
                // Filtrera och rita ut program som matchar vårt tidsfönster
                epgList.forEach { epg ->
                    val progStart = epg.startTimestamp ?: 0L
                    val progStop = epg.stopTimestamp ?: 0L
                    
                    if (progStop > startTime) {
                        val durationMin = (progStop - progStart) / 60
                        val width = (durationMin * 6.66).dp // Skala: 1 min = 6.66dp (200dp per 30 min)
                        
                        // Beräkna offset om programmet startade före vår starttid
                        val displayWidth = if (progStart < startTime) {
                            val overlapMin = (progStop - startTime) / 60
                            (overlapMin * 6.66).dp
                        } else width

                        ProgramBlock(
                            epg = epg,
                            width = displayWidth,
                            themeColor = themeColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProgramBlock(epg: EpgListing, width: androidx.compose.ui.unit.Dp, themeColor: Color) {
    var isFocused by remember { mutableStateOf(false) }
    
    Surface(
        onClick = { /* Visa info? */ },
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(1.dp),
        color = if (isFocused) themeColor.copy(alpha = 0.3f) else Color(0xFF222222),
        shape = RoundedCornerShape(2.dp),
        border = if (isFocused) BorderStroke(2.dp, themeColor) else null
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = epg.title ?: "Inget namn",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            val start = Instant.ofEpochSecond(epg.startTimestamp ?: 0).atZone(ZoneId.systemDefault()).toLocalTime()
            val stop = Instant.ofEpochSecond(epg.stopTimestamp ?: 0).atZone(ZoneId.systemDefault()).toLocalTime()
            
            Text(
                text = "${start.format(timeFormatter)} - ${stop.format(timeFormatter)}",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}
