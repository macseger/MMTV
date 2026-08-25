package com.example.mmtv.ui.components

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import com.example.mmtv.ui.MediaViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.delay

data class EpgUiItem(
    val id: String,
    val title: String,
    val description: String,
    val startText: String,
    val stopText: String,
    val isCurrent: Boolean,
    val startTimestamp: Long,
    val stopTimestamp: Long
)

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            // Use a ContextThemeWrapper to provide a non-translucent background theme
            // which MediaRouteButton needs for contrast calculations.
            val themedContext = android.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
            MediaRouteButton(themedContext).apply {
                CastButtonFactory.setUpMediaRouteButton(themedContext, this)
            }
        },
        modifier = modifier
    )
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
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { false },
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
            }
            .onKeyEvent(onKeyEvent),
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
    viewModel: MediaViewModel,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { false },
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    val piconUrl = remember(item.id) { viewModel.getIconForId(item.id, item.type, item.title) ?: item.icon }

    val imageRequest = remember(piconUrl) {
        ImageRequest.Builder(context)
            .data(piconUrl)
            .crossfade(200)
            .size(120, 120)
            .build()
    }
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(120.dp)
            .height(80.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { 
                isFocused = it.isFocused
                if (it.isFocused) onFocus() else onBlur()
            }
            .onKeyEvent(onKeyEvent),
        color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.5f),
        contentColor = if (isFocused) Color.Black else Color.White,
        shape = RoundedCornerShape(8.dp),
        border = if (isFocused) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (isFocused) 0.3f else 0.6f }
                    .padding(12.dp),
                contentScale = ContentScale.Fit
            )
            
            if (piconUrl == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (item.type == MediaType.LIVE) Icons.Default.Tv else Icons.Default.Movie,
                        contentDescription = null,
                        tint = if (isFocused) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            
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
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SubtitleOptionItem(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable { onClick() }, 
        color = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = null)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun CategoryListItem(title: String, isSelected: Boolean, viewModel: MediaViewModel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    
    val backgroundColor by animateColorAsState(
        targetValue = if (hasFocus) viewModel.currentThemeColor.copy(alpha = 0.7f) 
                    else if (isSelected) viewModel.currentThemeColor.copy(alpha = 0.2f) 
                    else Color.Transparent,
        animationSpec = tween(200),
        label = "catBg"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (hasFocus) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "catScale"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { onClick() }
            ), 
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .graphicsLayer {
                        clip = true
                        shape = RoundedCornerShape(2.dp)
                        alpha = if (isSelected || hasFocus) 1f else 0f
                    }
                    .background(viewModel.currentThemeColor)
            )
            Text(
                text = title, 
                modifier = Modifier.padding(horizontal = 20.dp), 
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected || hasFocus) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = if (hasFocus) 0.5.sp else 0.sp
                ), 
                color = if (hasFocus) Color.White else if (isSelected) Color.White.copy(alpha = 0.9f) else Color.LightGray, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ChannelListItem(item: MediaSource, isSelected: Boolean, viewModel: MediaViewModel, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hasFocus by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    val epg = remember(item.id, now) { viewModel.getEpgForId(item.id, item.type, item.title) }
    val piconUrl = remember(item.id) { viewModel.getIconForId(item.id, item.type, item.title) ?: item.icon }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            hasFocus -> viewModel.currentThemeColor.copy(alpha = 0.6f)
            isSelected -> viewModel.currentThemeColor.copy(alpha = 0.2f)
            else -> Color.Transparent
        }, 
        animationSpec = tween(150),
        label = "chBg"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (hasFocus) 1.03f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "chScale"
    )

    val imageRequest = remember(piconUrl) {
        ImageRequest.Builder(context)
            .data(piconUrl)
            .crossfade(100) 
            .size(120, 120)
            .build()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { onClick() }
            ),
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .graphicsLayer {
                        clip = true
                        shape = RoundedCornerShape(6.dp)
                    }
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                if (piconUrl == null && item.icon == null) {
                    Icon(Icons.Default.Tv, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title ?: "", 
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), 
                    color = Color.White, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                
                if (epg != null) {
                    Text(
                        text = epg.title ?: "", 
                        style = MaterialTheme.typography.bodyMedium, 
                        color = if (hasFocus) Color.White else Color.LightGray, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )

                    val start = epg.startTimestamp ?: 0L
                    val stop = epg.stopTimestamp ?: 0L
                    if (now in start..stop) {
                        val progress = (now - start).toFloat() / (stop - start).toFloat()
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = viewModel.currentThemeColor,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }
                } else {
                    Text(
                        text = "Ingen programinfo", 
                        style = MaterialTheme.typography.bodySmall, 
                        color = Color.Gray.copy(alpha = 0.5f), 
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun ModernProgramDetailBox(epg: EpgListing?, channel: MediaSource?, viewModel: MediaViewModel) {
    val context = LocalContext.current
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    
    val piconUrl = if (channel != null) viewModel.getIconForId(channel.id, channel.type, channel.title) else null

    val imageRequest = remember(piconUrl, epg?.icon) {
        ImageRequest.Builder(context)
            .data(piconUrl ?: epg?.icon)
            .crossfade(300)
            .size(160, 160)
            .build()
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(modifier = Modifier.padding(24.dp)) {
            if (channel != null) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    if (piconUrl == null && epg?.icon == null) {
                        Icon(Icons.Default.Tv, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                    }
                }
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
                        Text(text = "$start - $stop", color = viewModel.currentThemeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
fun MiniProgramGuideItem(epg: EpgListing, isCurrent: Boolean, themeColor: Color) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = start,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) themeColor else Color.Gray,
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

@Composable
fun QuickInfoOverlay(
    media: MediaSource,
    viewModel: MediaViewModel,
    categories: List<GroupedMedia>,
    tvGuideFocusRequester: FocusRequester,
    favoriteButtonFocusRequester: FocusRequester,
    recentChannelsFocusRequesters: MutableMap<Int, FocusRequester>,
    videoFormat: androidx.media3.common.Format?,
    audioFormat: androidx.media3.common.Format?,
    favorites: List<MediaSource>,
    onTvGuideClick: () -> Unit,
    onRecentChannelClick: (MediaSource) -> Unit,
    onCategoryRequest: () -> Unit,
    onCloseRequest: () -> Unit,
    onFocusAction: () -> Unit,
    onBlurAction: () -> Unit
) {
    // Ticker för att uppdatera klocka och framsteg
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10000) // Uppdatera var 10:e sekund
            currentTime = System.currentTimeMillis()
        }
    }

    val epg = viewModel.getEpgForId(media.id, media.type, media.title)
    val piconUrl = viewModel.getIconForId(media.id, media.type, media.title) ?: media.icon
    
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d MMM").withLocale(Locale("sv", "SE")).withZone(ZoneId.systemDefault()) }
    val history = remember(viewModel.uiState.history) { 
        viewModel.uiState.history.filter { it.type == MediaType.LIVE }.take(10)
    }

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
                text = "${dateFormatter.format(Instant.ofEpochMilli(currentTime))}  ${timeFormatter.format(Instant.ofEpochMilli(currentTime))}",
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
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(piconUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    if (piconUrl == null) {
                        Icon(Icons.Default.Tv, null, tint = Color.Gray)
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.title ?: "",
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
                        val now = currentTime / 1000
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
                                text = "${(remaining / 60).coerceAtLeast(0)} min kvar",
                                style = MaterialTheme.typography.bodyMedium,
                                color = viewModel.currentThemeColor
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
                                        .background(viewModel.currentThemeColor)
                                )
                            }
                        }
                    }
                }
                
                // Technical Tags
                val resolution = when {
                    videoFormat == null || videoFormat.height <= 0 -> ""
                    videoFormat.height >= 2160 -> "4K"
                    videoFormat.height >= 1080 -> "FHD"
                    videoFormat.height >= 720 -> "HD"
                    else -> "SD"
                }
                val fpsVal = videoFormat?.frameRate ?: 0f
                val fps = if (fpsVal > 0) "${fpsVal.toInt()}FPS" else ""
                val audio = if (audioFormat != null && audioFormat.channelCount > 0) {
                    if (audioFormat.channelCount >= 6) "5.1" else "2.0"
                } else ""

                Row(modifier = Modifier.padding(start = 16.dp)) {
                    if (resolution.isNotEmpty()) {
                        TechnicalTag(resolution)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (fps.isNotEmpty()) {
                        TechnicalTag(fps)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (audio.isNotEmpty()) {
                        TechnicalTag(audio)
                    }
                }
            }
        }

        // Action Row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .onKeyEvent { 
                    if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        onCloseRequest()
                        true
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item(contentType = "action") {
                ActionButton(
                    icon = Icons.Default.Menu,
                    label = "TV-guide",
                    focusRequester = tvGuideFocusRequester,
                    onFocus = onFocusAction,
                    onBlur = onBlurAction,
                    onKeyEvent = {
                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            onCategoryRequest()
                            true
                        } else false
                    },
                    onClick = onTvGuideClick
                )
            }
            
            item(contentType = "action") {
                val isFav = favorites.any { it.id == media.id }
                ActionButton(
                    icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = "Favorit",
                    focusRequester = favoriteButtonFocusRequester,
                    onFocus = onFocusAction,
                    onBlur = onBlurAction,
                    onClick = { viewModel.toggleFavorite(media) }
                )
            }

            itemsIndexed(
                items = history, 
                key = { _, item -> "history_${item.id}" },
                contentType = { _, _ -> "recent_channel" }
            ) { _, historyItem ->
                RecentChannelButton(
                    item = historyItem,
                    viewModel = viewModel,
                    focusRequester = recentChannelsFocusRequesters.getOrPut(historyItem.id) { FocusRequester() },
                    onFocus = onFocusAction,
                    onBlur = onBlurAction,
                    onClick = { onRecentChannelClick(historyItem) }
                )
            }
        }
    }
}

@Composable
fun SideOverlay(
    isVisible: Boolean,
    overlayState: String, // "CHANNELS" or "CATEGORIES"
    categories: List<GroupedMedia>,
    playlist: List<MediaSource>,
    viewModel: MediaViewModel,
    focusedChannel: MediaSource?,
    categoryListState: LazyListState,
    channelListState: LazyListState,
    categoryFocusRequesters: MutableMap<Int, FocusRequester>,
    channelFocusRequesters: MutableMap<Int, FocusRequester>,
    onCategorySelected: (Int) -> Unit,
    onMediaSelected: (MediaSource) -> Unit,
    onFocusedChannelChanged: (MediaSource) -> Unit,
    onOverlayStateChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
        BackHandler {
            if (overlayState == "CHANNELS") {
                onOverlayStateChange("CATEGORIES")
            } else {
                onDismiss()
            }
        }
    }

    // Ticker för att uppdatera EPG-visning
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            while (true) {
                delay(30000)
                now = System.currentTimeMillis() / 1000
            }
        }
    }

    // Hantera fokus när vi byter till kanallistan
    LaunchedEffect(overlayState) {
        if (overlayState == "CHANNELS" && isVisible) {
            val targetId = if (playlist.any { it.id == viewModel.selectedMedia?.id }) {
                viewModel.selectedMedia?.id
            } else {
                playlist.firstOrNull()?.id
            }

            if (targetId != null) {
                // Vänta lite så att listan hinner rendera och FocusRequesters kopplas till UI:t
                delay(100)
                runCatching { 
                    channelFocusRequesters[targetId]?.requestFocus()
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(
            initialOffsetX = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        ) + fadeOut(animationSpec = tween(300)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.95f),
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent
                            )
                        )
                    )
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            Row(modifier = Modifier.fillMaxSize()) {
                val showCategories = overlayState == "CATEGORIES"
                
                AnimatedVisibility(
                    visible = showCategories,
                    enter = slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                    ) + fadeIn(),
                    exit = slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                    ) + fadeOut()
                ) {
                    LazyColumn(
                        state = categoryListState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(280.dp)
                            .background(Color.Black.copy(alpha = 0.95f))
                            .padding(vertical = 16.dp, horizontal = 8.dp)
                    ) {
                        itemsIndexed(
                            items = categories, 
                            key = { index, category -> category.categoryId ?: "cat_$index" },
                            contentType = { _, _ -> "category" }
                        ) { index, category ->
                            val isSelected = categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title == category.title
                            CategoryListItem(
                                title = category.title ?: "",
                                isSelected = isSelected,
                                viewModel = viewModel,
                                modifier = Modifier
                                    .focusRequester(categoryFocusRequesters.getOrPut(index) { FocusRequester() })
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            onCategorySelected(index)
                                        }
                                    }
                                    .onKeyEvent {
                                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            onOverlayStateChange("CHANNELS")
                                            true
                                        } else false
                                    },
                                onClick = { 
                                    onCategorySelected(index)
                                    onOverlayStateChange("CHANNELS") 
                                }
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(420.dp)
                        .background(
                            Brush.horizontalGradient(
                                0.0f to Color.Black.copy(alpha = 0.9f),
                                1.0f to Color.Black.copy(alpha = 0.7f)
                            )
                        )
                ) {
                    val currentCategoryTitle = remember(categories, viewModel.lastLiveCategoryIndex) {
                        categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title ?: ""
                    }
                    Text(
                        text = currentCategoryTitle.uppercase(),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = viewModel.currentThemeColor,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )

                    LazyColumn(
                        state = channelListState,
                        modifier = Modifier.fillMaxSize(),
                        // Optimering för Android TV: Spara plats för objekt som inte syns än
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        itemsIndexed(
                            items = playlist, 
                            key = { _, item -> item.id }, // Använd stabilt ID som nyckel för bättre prestanda vid scroll
                            contentType = { _, _ -> "channel" }
                        ) { _, item ->
                            ChannelListItem(
                                item = item,
                                isSelected = item.id == viewModel.selectedMedia?.id,
                                viewModel = viewModel,
                                now = now,
                                modifier = Modifier
                                    .focusRequester(channelFocusRequesters.getOrPut(item.id) { FocusRequester() })
                                    .onFocusChanged { if (it.isFocused) onFocusedChannelChanged(item) }
                                    .onKeyEvent {
                                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            onOverlayStateChange("CATEGORIES")
                                            true
                                        } else false
                                    },
                                onClick = { onMediaSelected(item) }
                            )
                        }
                    }
                }

                if (overlayState == "CHANNELS") {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(32.dp)
                    ) {
                        AnimatedContent(
                            targetState = focusedChannel,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(300)) togetherWith 
                                fadeOut(animationSpec = tween(300))
                            },
                            label = "detailAnim"
                        ) { targetChannel ->
                            val currentEpg = targetChannel?.let { viewModel.getEpgForId(it.id, it.type, it.title) }
                            ModernProgramDetailBox(epg = currentEpg, channel = targetChannel, viewModel = viewModel)
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        val fullEpg = focusedChannel?.let { viewModel.getFullEpgForId(it.id, it.type, it.title) } ?: emptyList()
                        
                        val upcomingEpg = remember(fullEpg, focusedChannel, now) { 
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
                                itemsIndexed(
                                    items = upcomingEpg, 
                                    key = { _, epg -> "${epg.id}_${epg.startTimestamp}" },
                                    contentType = { _, _ -> "mini_epg" }
                                ) { idx, epg ->
                                    MiniProgramGuideItem(
                                        epg = epg,
                                        isCurrent = idx == 0 && (epg.startTimestamp ?: 0) <= now,
                                        themeColor = viewModel.currentThemeColor
                                    )
                                }
                            }
                        }
                    }
                } else if (overlayState == "CATEGORIES") {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        focusedChannel?.let { channel ->
                            val piconUrl = viewModel.getIconForId(channel.id, channel.type, channel.title) ?: channel.icon

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AsyncImage(
                                    model = piconUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(200.dp)
                                        .alpha(0.5f),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = channel.title ?: "",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Bold
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
fun EpgModal(
    media: MediaSource,
    viewModel: MediaViewModel,
    epgListState: LazyListState,
    epgFocusRequester: FocusRequester,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    
    val fullEpg = viewModel.getFullEpgForId(media.id, media.type, media.title)
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val piconUrl = remember(media.id) { viewModel.getIconForId(media.id, media.type, media.title) ?: media.icon }

    // För-beräkna och formatera EPG-data för att undvika tungt jobb i listan
    val formattedEpg = remember(fullEpg) {
        val nowTs = System.currentTimeMillis() / 1000
        fullEpg.filter { (it.stopTimestamp ?: 0) > nowTs }.map { epg ->
            val startTs = epg.startTimestamp ?: 0L
            val stopTs = epg.stopTimestamp ?: 0L
            EpgUiItem(
                id = "${epg.id ?: ""}_$startTs",
                title = epg.title ?: "",
                description = epg.description ?: "",
                startText = timeFormatter.format(Instant.ofEpochSecond(startTs)),
                stopText = timeFormatter.format(Instant.ofEpochSecond(stopTs)),
                isCurrent = startTs <= nowTs && stopTs > nowTs,
                startTimestamp = startTs,
                stopTimestamp = stopTs
            )
        }
    }

    LaunchedEffect(fullEpg) {
        if (fullEpg.isNotEmpty()) {
            delay(100)
            runCatching { epgFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.85f)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            shape = MaterialTheme.shapes.large,
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(Color.White.copy(alpha = 0.1f)))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(50.dp).graphicsLayer { clip = true; shape = RoundedCornerShape(8.dp) },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(piconUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (piconUrl == null) {
                            Icon(Icons.Default.Tv, null, tint = Color.Gray)
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(text = media.title ?: "Programguide", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(text = "Kommande program", style = MaterialTheme.typography.bodyMedium, color = viewModel.currentThemeColor)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { 
                        Icon(Icons.Default.Close, null, tint = Color.White) 
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                if (fullEpg.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        state = epgListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        itemsIndexed(
                            items = formattedEpg, 
                            key = { _, item -> item.id },
                            contentType = { _, _ -> "epg_item" }
                        ) { index, epg ->
                            var isItemFocused by remember { mutableStateOf(false) }
                            
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(epgFocusRequester) else Modifier)
                                    .onFocusChanged { isItemFocused = it.isFocused }
                                    .clickable { /* Focusable */ }
                                    .padding(vertical = 4.dp),
                                color = if (isItemFocused) viewModel.currentThemeColor.copy(alpha = 0.25f)
                                        else if (epg.isCurrent) Color.White.copy(alpha = 0.05f)
                                        else Color.Transparent,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Row(modifier = Modifier.padding(16.dp)) {
                                    Column(modifier = Modifier.width(120.dp)) {
                                        Text(text = epg.startText, style = MaterialTheme.typography.titleMedium, color = if (epg.isCurrent) viewModel.currentThemeColor else Color.White, fontWeight = FontWeight.Bold)
                                        Text(text = epg.stopText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = epg.title, 
                                            style = MaterialTheme.typography.titleLarge, 
                                            fontWeight = if (epg.isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                            color = if (epg.isCurrent) viewModel.currentThemeColor else Color.White
                                        )
                                        if (epg.description.isNotBlank()) {
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
