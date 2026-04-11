package com.example.mmtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType

@Composable
fun HomeScreen(
    viewModel: MediaViewModel,
    onNavigate: (String) -> Unit,
    onMediaSelected: (MediaSource) -> Unit
) {
    val tvFocusRequester = remember { FocusRequester() }
    val dbSearchResults by viewModel.dbSearchResults.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val uiState = viewModel.uiState
    val history = uiState.history

    val favoriteTV = favorites.filter { it.type == MediaType.LIVE }.reversed()
    val favoriteMovies = favorites.filter { it.type == MediaType.MOVIE }
    val favoriteSeries = favorites.filter { it.type == MediaType.SERIES }

    // 1. Hantera back-knappen vid sökning
    BackHandler(enabled = viewModel.searchQuery.isNotEmpty()) {
        viewModel.searchQuery = ""
    }

    // Fokusera på rätt element när skärmen laddas
    LaunchedEffect(Unit) {
        if (viewModel.searchQuery.isEmpty()) {
            tvFocusRequester.requestFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Section
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "MULTIMEDIA TV",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraLight,
                            letterSpacing = 8.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "PREMIUM STREAMING",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 4.sp),
                        color = Color.Gray
                    )
                }

                // Small Search Bar in Header
                OutlinedTextField(
                    value = viewModel.searchQuery,
                    onValueChange = { viewModel.searchQuery = it },
                    placeholder = { Text("Sök...", fontSize = 14.sp) },
                    modifier = Modifier.width(300.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            }

            if (viewModel.searchQuery.isNotEmpty()) {
                // Sökresultat
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 48.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("SÖKRESULTAT", style = MaterialTheme.typography.titleSmall, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(dbSearchResults) { media ->
                        MediaCard(media, onClick = { onMediaSelected(media) }, onToggleFavorite = { viewModel.toggleFavorite(media) })
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 48.dp)
                ) {
                    // 1. Snabbnavigering (Huvudmenyn)
                    item { 
                        HomeCard("LIVE TV", Icons.Default.LiveTv, modifier = Modifier.focusRequester(tvFocusRequester)) { 
                            onNavigate("live") 
                        } 
                    }
                    item { HomeCard("FILMER", Icons.Default.Movie) { onNavigate("movies") } }
                    item { HomeCard("SERIER", Icons.Default.Tv) { onNavigate("series") } }
                    item { HomeCard("INSTÄLLNINGAR", Icons.Default.Settings) { onNavigate("settings") } }

                    // 1. Favoriter TV
                    if (favoriteTV.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            MediaRow("FAVORITER TV", favoriteTV, viewModel, isHorizontal = true) { onMediaSelected(it) }
                        }
                    }

                    // 2. Fortsätt titta (History)
                    if (history.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(modifier = Modifier.padding(top = 24.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("FORTSÄTT TITTA", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                                    Text("SENASTE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
                                ) {
                                    items(history.take(15)) { item ->
                                        HistoryCard(item) { onMediaSelected(item) }
                                    }
                                }
                            }
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
                    .padding(32.dp)
                    .animateContentSize(),
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(16.dp),
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
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color.Gray, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
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
            .width(200.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.05f else 1.0f)
            .onKeyEvent { keyEvent ->
                val isCenterKey = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                                 keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                
                if (isCenterKey) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (pressJob == null) {
                            pressJob = scope.launch {
                                delay(700)
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
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .border(
                    width = if (hasFocus) 3.dp else 0.dp,
                    color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (hasFocus) 12.dp else 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
                if (!media.icon.isNullOrEmpty()) {
                    AsyncImage(
                        model = media.icon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color(0xFF232526), Color(0xFF414345)))
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (media.type == MediaType.LIVE) Icons.Default.LiveTv else Icons.Default.Movie,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = media.title ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
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
    
    val backgroundColor = if (hasFocus) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.03f)
    val contentColor = if (hasFocus) Color.Black else Color.White
    val iconSize = if (hasFocus) 28.dp else 24.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.05f else 1.0f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (hasFocus) 0.dp else 1.dp,
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        color = backgroundColor,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(iconSize),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (hasFocus) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                    fontSize = 14.sp
                ),
                color = contentColor,
                textAlign = TextAlign.Center
            )
        }
    }
}
