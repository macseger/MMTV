package com.example.mmtv.ui.components

import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.NavigateNext
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

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            MediaRouteButton(context).apply {
                CastButtonFactory.setUpMediaRouteButton(context, this)
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
    
    val piconUrl by produceState(initialValue = item.icon, key1 = item.id) {
        value = viewModel.getIconForChannel(item.id, item.title)
    }

    val imageRequest = remember(piconUrl) {
        ImageRequest.Builder(context)
            .data(piconUrl)
            .crossfade(200)
            .size(100, 100)
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
                    .alpha(if (isFocused) 0.3f else 0.6f)
                    .padding(12.dp),
                contentScale = ContentScale.Fit
            )
            // ... (rest same)
            
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
fun ChannelListItem(item: MediaSource, isSelected: Boolean, viewModel: MediaViewModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hasFocus by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // Hämta EPG direkt från ViewModel (den lyssnar på fullEpgData internt)
    val epg = viewModel.getEpgForId(item.id, item.title)

    val backgroundColor by animateColorAsState(
        targetValue = when {
            hasFocus -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else -> Color.Transparent
        }, label = "chBg"
    )

    // Optimering: Förbered picon-url i en produceState men med mindre omfång
    val piconUrl by produceState(initialValue = item.icon, key1 = item.id) {
        value = viewModel.getIconForChannel(item.id, item.title)
    }

    // Optimering: Cacha ImageRequest för att slippa skapa nya objekt under scroll
    val imageRequest = remember(piconUrl) {
        ImageRequest.Builder(context)
            .data(piconUrl)
            .crossfade(200) // Kortare crossfade för prestanda
            .size(120, 120) // Begränsa storleken direkt i laddningen
            .build()
    }

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
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.05f)).padding(4.dp),
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
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), 
                    color = Color.White, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                
                if (epg != null) {
                    Text(
                        text = epg.title ?: "", 
                        style = MaterialTheme.typography.bodySmall, 
                        color = if (hasFocus) Color.White else Color.LightGray, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )

                    val now = System.currentTimeMillis() / 1000
                    val start = epg.startTimestamp ?: 0L
                    val stop = epg.stopTimestamp ?: 0L
                    if (now in start..stop) {
                        val progress = (now - start).toFloat() / (stop - start).toFloat()
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
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
    
    val piconUrl by produceState(initialValue = channel?.icon, key1 = channel?.id) {
        if (channel != null) {
            value = viewModel.getIconForChannel(channel.id, channel.title)
        }
    }

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
    val epg = produceState(initialValue = null as EpgListing?, key1 = media.id) {
        value = viewModel.getEpgForId(media.id, media.title)
    }.value

    val piconUrl = produceState(initialValue = media.icon, key1 = media.id) {
        value = viewModel.getIconForChannel(media.id, media.title)
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
            item {
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
            
            item {
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

            itemsIndexed(history, key = { _, item -> "history_${item.id}" }) { _, historyItem ->
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
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(
            initialOffsetX = { -it },
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(400)),
        exit = slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(400)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
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
                        itemsIndexed(categories, key = { index, category -> category.categoryId ?: "cat_$index" }) { index, category ->
                            val isSelected = categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title == category.title
                            CategoryListItem(
                                title = category.title ?: "",
                                isSelected = isSelected,
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
                        .background(Color.Black.copy(alpha = 0.85f))
                ) {
                    val currentCategoryTitle = remember(categories, viewModel.lastLiveCategoryIndex) {
                        categories.getOrNull(viewModel.lastLiveCategoryIndex)?.title ?: ""
                    }
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
                        modifier = Modifier.fillMaxSize(),
                        // Optimering för Android TV: Spara plats för objekt som inte syns än
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        itemsIndexed(
                            items = playlist, 
                            key = { _, item -> item.id } // Använd stabilt ID som nyckel för bättre prestanda vid scroll
                        ) { _, item ->
                            ChannelListItem(
                                item = item,
                                isSelected = item.id == viewModel.selectedMedia?.id,
                                viewModel = viewModel,
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
                        val currentEpg = viewModel.getEpgForId(focusedChannel?.id ?: 0, focusedChannel?.title)
                        ModernProgramDetailBox(epg = currentEpg, channel = focusedChannel, viewModel = viewModel)

                        Spacer(modifier = Modifier.height(32.dp))

                        val fullEpg = produceState(initialValue = emptyList<EpgListing>(), key1 = focusedChannel?.id) {
                            focusedChannel?.let {
                                value = viewModel.getFullEpgForId(it.id, it.title)
                            }
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
                                itemsIndexed(upcomingEpg, key = { _, epg -> "${epg.id}_${epg.startTimestamp}" }) { idx, epg ->
                                    MiniProgramGuideItem(
                                        epg = epg,
                                        isCurrent = idx == 0 && (epg.startTimestamp ?: 0) <= now
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
                            val piconUrl = produceState(initialValue = channel.icon, key1 = channel.id) {
                                value = viewModel.getIconForChannel(channel.id, channel.title)
                            }.value

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
    val fullEpg = produceState(initialValue = emptyList<EpgListing>(), key1 = media.id) {
        value = viewModel.getFullEpgForId(media.id, media.title)
    }.value
    
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val piconUrl = produceState(initialValue = media.icon, key1 = media.id) {
        value = viewModel.getIconForChannel(media.id, media.title)
    }.value

    LaunchedEffect(fullEpg) {
        if (fullEpg.isNotEmpty()) {
            delay(100)
            runCatching { epgFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .onKeyEvent { 
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    onClose()
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
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(Color.White.copy(alpha = 0.1f)))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small),
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
                        Text(text = "Kommande program", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                    val now = System.currentTimeMillis() / 1000
                    val futureEpg = remember(fullEpg) { fullEpg.filter { (it.stopTimestamp ?: 0) > now } }
                    
                    LazyColumn(
                        state = epgListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(futureEpg, key = { _, epg -> "${epg.id}_${epg.startTimestamp}" }) { index, epg ->
                            var isItemFocused by remember { mutableStateOf(false) }
                            val start = timeFormatter.format(Instant.ofEpochSecond(epg.startTimestamp ?: 0))
                            val stop = timeFormatter.format(Instant.ofEpochSecond(epg.stopTimestamp ?: 0))
                            val isCurrent = (epg.startTimestamp ?: 0) <= now && (epg.stopTimestamp ?: 0) > now
                            
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(epgFocusRequester) else Modifier)
                                    .onFocusChanged { isItemFocused = it.isFocused }
                                    .clickable { /* Focusable */ }
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
