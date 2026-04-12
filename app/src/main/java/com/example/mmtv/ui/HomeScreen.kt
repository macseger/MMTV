package com.example.mmtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import android.view.KeyEvent
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType

@Composable
fun HomeScreen(
    viewModel: MediaViewModel,
    onNavigate: (String) -> Unit,
    onMediaSelected: (MediaSource) -> Unit
) {
    val dbSearchResults by viewModel.dbSearchResults.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val uiState = viewModel.uiState
    val history = uiState.history

    val favoriteTV = favorites.filter { it.type == MediaType.LIVE }.reversed()
    val favoriteMovies = favorites.filter { it.type == MediaType.MOVIE }
    val favoriteSeries = favorites.filter { it.type == MediaType.SERIES }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 48.dp)
            ) {
                // Hero Section placeholder (Netflix style)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.DarkGray)
                    ) {
                        // In the future, this would be a featured movie/series
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(48.dp)
                        ) {
                            Text(
                                "Välkommen till MMTV",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Din ultimata TV-upplevelse",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // 1. Fortsätt titta (History)
                if (history.isNotEmpty()) {
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
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp)
                            ) {
                                items(history.take(15)) { item ->
                                    HistoryCard(item) { onMediaSelected(item) }
                                }
                            }
                        }
                    }
                }

                // 2. Favoriter TV
                if (favoriteTV.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaRow("FAVORITER TV", favoriteTV, viewModel, isHorizontal = true) { onMediaSelected(it) }
                    }
                }

                // 3. Nyligen tillagt
                if (recentlyAdded.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaRow("NYLIGEN TILLAGT", recentlyAdded, viewModel) { onMediaSelected(it) }
                    }
                }

                // 4. Favoriter Film
                if (favoriteMovies.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaRow("FAVORITER FILM", favoriteMovies, viewModel) { onMediaSelected(it) }
                    }
                }

                // 5. Favoriter Serier
                if (favoriteSeries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaRow("FAVORITER SERIER", favoriteSeries, viewModel) { onMediaSelected(it) }
                    }
                }
            }
            // Push everything up by adding a spacer at the bottom
            Spacer(modifier = Modifier.weight(1f))
        }

        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.primary)
        }

        // Overlay för uppdateringsstatus
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
    }
}

@Composable
fun MediaRow(
    title: String, 
    items: List<MediaSource>, 
    viewModel: MediaViewModel, 
    isHorizontal: Boolean = false,
    onMediaClick: (MediaSource) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp)
        ) {
            items(items) { item ->
                if (isHorizontal) {
                    HistoryCard(
                        media = item,
                        onToggleFavorite = { viewModel.toggleFavorite(item) },
                        onClick = { onMediaClick(item) }
                    )
                } else {
                    MediaCard(
                        media = item,
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
                width = 1.dp,
                color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
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
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Column(
        modifier = Modifier
            .width(160.dp)
            .onFocusChanged { hasFocus = it.isFocused }
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
                if (!media.icon.isNullOrEmpty()) {
                    SubcomposeAsyncImage(
                        model = media.icon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = {
                            ChannelPlaceholder(media.title ?: "?", Modifier.fillMaxSize(), isMovie = isMovie)
                        },
                        loading = {
                            Box(Modifier.fillMaxSize().background(Color.DarkGray.copy(alpha = 0.3f)))
                        }
                    )
                } else {
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
