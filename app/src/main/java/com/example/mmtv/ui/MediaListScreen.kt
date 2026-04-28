package com.example.mmtv.ui

import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

@Composable
fun MediaListScreen(
    groupedList: List<GroupedMedia>,
    initialCategoryIndex: Int = 0,
    initialMediaId: Int? = null,
    isLive: Boolean = true, // Explicitly pass if it's Live TV or VOD
    onCategoryChanged: (Int) -> Unit = {},
    onMediaSelected: (MediaSource) -> Unit,
    onToggleFavorite: (MediaSource) -> Unit = {},
    epgProvider: suspend (Int, String?) -> EpgListing? = { _, _ -> null },
    nextEpgProvider: suspend (Int, String?) -> EpgListing? = { _, _ -> null },
    onGetIcon: suspend (Int, String?) -> String? = { _, _ -> null },
    onItemFocused: (Int) -> Unit = {},
    backgroundColor: Color = Color.Black,
    onBackPressed: (() -> Unit)? = null,
    topBarFocusRequester: FocusRequester? = null
) {
    var selectedCategoryIndex by remember(initialCategoryIndex) { mutableIntStateOf(initialCategoryIndex) }
    var debouncedCategoryIndex by remember(initialCategoryIndex) { mutableIntStateOf(initialCategoryIndex) }
    
    val selectedCategory = groupedList.getOrNull(debouncedCategoryIndex)
    // Removed heuristic to avoid issues with empty lists
    
    var focusedMedia by remember { mutableStateOf<MediaSource?>(null) }
    var mediaToShowMenu by remember { mutableStateOf<MediaSource?>(null) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    
    val categoryFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val channelFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    // Debounce category change to avoid jank when scrolling fast
    LaunchedEffect(selectedCategoryIndex) {
        if (selectedCategoryIndex != debouncedCategoryIndex) {
            delay(200) // Debounce time
            debouncedCategoryIndex = selectedCategoryIndex
            onCategoryChanged(selectedCategoryIndex)
        }
    }

    LaunchedEffect(initialCategoryIndex, initialMediaId) {
        delay(100)
        selectedCategoryIndex = initialCategoryIndex
        debouncedCategoryIndex = initialCategoryIndex

        if (initialMediaId != null && isLive) {
            val index = selectedCategory?.items?.indexOfFirst { it.id == initialMediaId } ?: -1
            if (index != -1) {
                listState.scrollToItem(index)
                channelFocusRequesters[initialMediaId]?.requestFocus()
            } else {
                categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
            }
        } else {
            categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
        }
    }

    LaunchedEffect(debouncedCategoryIndex) {
        if (isLive) listState.scrollToItem(0)
        else gridState.scrollToItem(0)
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var isSidebarFocused by remember { mutableStateOf(false) }

    Row(modifier = Modifier
        .fillMaxSize()
        .background(backgroundColor)
        .onKeyEvent { 
            if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && 
                it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                if (!isSidebarFocused) {
                    categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
                    true
                } else {
                    if (topBarFocusRequester != null) {
                        topBarFocusRequester.requestFocus()
                        true
                    } else {
                        false // Let the system handle it (exit screen)
                    }
                }
            } else false
        }
    ) {
        // COLUMN 1: CATEGORIES (TiviMate Sidebar Style)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(240.dp)
                .background(Color(0xFF121212))
                .onFocusChanged { isSidebarFocused = it.hasFocus }
                .padding(vertical = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(groupedList.size, key = { index -> groupedList[index].categoryId ?: index }) { index ->
                    val title = groupedList.getOrNull(index)?.title ?: "Kategori"
                    val requester = categoryFocusRequesters.getOrPut(index) { FocusRequester() }
                    
                    CategoryItem(
                        title = title,
                        isSelected = selectedCategoryIndex == index,
                        modifier = Modifier
                            .focusRequester(requester)
                            .onFocusChanged { 
                                if (it.isFocused) {
                                    selectedCategoryIndex = index
                                }
                            }
                            .onKeyEvent {
                                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    val firstChannelId = groupedList.getOrNull(index)?.items?.firstOrNull()?.id
                                    if (firstChannelId != null) {
                                        channelFocusRequesters[firstChannelId]?.requestFocus()
                                        true
                                    } else false
                                } else false
                            },
                        onClick = { 
                            selectedCategoryIndex = index
                        }
                    )
                }
            }
        }

        // COLUMN 2 & 3: CONTENT
        if (isLive) {
            // TIVIMATE 3-COLUMN STYLE: [Categories | Channels | EPG Details]
            Row(modifier = Modifier.fillMaxSize()) {
                // Column 2: Channel List
                Column(modifier = Modifier.width(420.dp).fillMaxHeight().background(Color(0xFF0A0A0A))) {
                    Text(
                        text = selectedCategory?.title ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(24.dp)
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(selectedCategory?.items ?: emptyList(), key = { it.id }) { media ->
                            val requester = channelFocusRequesters.getOrPut(media.id) { FocusRequester() }
                            TvChannelItem(
                                media = media,
                                epgProvider = epgProvider,
                                onGetIcon = onGetIcon,
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .onFocusChanged { if (it.isFocused) {
                                        focusedMedia = media
                                        onItemFocused(media.id)
                                    } }
                                    .onKeyEvent { 
                                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
                                            true
                                        } else false
                                    },
                                onClick = { onMediaSelected(media) },
                                onToggleFavorite = { mediaToShowMenu = media }
                            )
                        }
                    }
                }

                // Column 3: EPG Detail Pane
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black).padding(24.dp)) {
                    focusedMedia?.let { media ->
                        val currentEpg = produceState<EpgListing?>(initialValue = null, key1 = media.id) {
                            value = epgProvider(media.id, media.title)
                        }.value
                        val nextEpg = produceState<EpgListing?>(initialValue = null, key1 = media.id) {
                            value = nextEpgProvider(media.id, media.title)
                        }.value
                        
                        val displayIcon = produceState<String?>(initialValue = media.icon, key1 = media.id) {
                            value = onGetIcon(media.id, media.title)
                        }.value
                        
                        LiveDetailPane(media = media, currentEpg = currentEpg, nextEpg = nextEpg, displayIcon = displayIcon)
                    }
                }
            }
        } else {
            // NETFLIX-STYLE VOD GRID
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 124.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    val items = selectedCategory?.items ?: emptyList()
                    items(items, key = { it.id }) { media ->
                        val requester = channelFocusRequesters.getOrPut(media.id) { FocusRequester() }
                        
                        MediaCard(
                            media = media,
                            onGetIcon = onGetIcon,
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged { if (it.isFocused) focusedMedia = media }
                                .onKeyEvent {
                                    if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                        // Attempt to move focus left. If it fails, go to category sidebar.
                                        val moved = focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)
                                        if (!moved) {
                                            categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
                                            true
                                        } else true
                                    } else false
                                },
                            onClick = { onMediaSelected(media) },
                            onToggleFavorite = { mediaToShowMenu = media }
                        )
                    }
                }
            }
        }
    }

    // Dialogs remain the same...
    if (mediaToShowMenu != null) {
        val media = mediaToShowMenu!!
        AlertDialog(
            onDismissRequest = { mediaToShowMenu = null },
            title = { Text(media.title ?: "Alternativ", color = MaterialTheme.colorScheme.primary) },
            text = { Text(if (media.isFavorite) "Vill du ta bort från favoriter?" else "Vill du lägga till i favoriter?", color = Color.White) },
            containerColor = Color(0xFF121212),
            confirmButton = {
                Button(onClick = { onToggleFavorite(media); mediaToShowMenu = null }) {
                    Text(if (media.isFavorite) "Ta bort" else "Lägg till")
                }
            },
            dismissButton = {
                TextButton(onClick = { mediaToShowMenu = null }) { Text("Avbryt") }
            }
        )
    }
}

@Composable
fun LiveDetailPane(media: MediaSource, currentEpg: EpgListing?, nextEpg: EpgListing?, displayIcon: String? = null) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .height(120.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = displayIcon ?: media.icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (displayIcon == null && media.icon == null) {
                ChannelPlaceholder(media.title ?: "?", Modifier.fillMaxSize())
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = media.title ?: "",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (currentEpg != null) {
            val start = formatter.format(Instant.ofEpochSecond(currentEpg.startTimestamp ?: 0))
            val stop = formatter.format(Instant.ofEpochSecond(currentEpg.stopTimestamp ?: 0))
            
            Text(
                text = currentEpg.title ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(text = "$start - $stop", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = currentEpg.description ?: "Ingen beskrivning tillgänglig.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
            
            if (nextEpg != null) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(text = "NÄSTA", style = MaterialTheme.typography.labelLarge, color = Color.Gray, fontWeight = FontWeight.Black)
                Text(
                    text = nextEpg.title ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = formatter.format(Instant.ofEpochSecond(nextEpg.startTimestamp ?: 0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        } else {
            Text("Ingen programinformation tillgänglig just nu.", color = Color.Gray)
        }
    }
}

@Composable
fun CategoryItem(title: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            hasFocus -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else -> Color.Transparent
        }, label = "catBg"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(16.dp, 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (isSelected || hasFocus) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (isSelected) Color.Black else if (hasFocus) Color.White else Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TvChannelItem(
    media: MediaSource, 
    modifier: Modifier = Modifier, 
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    epgProvider: (suspend (Int, String?) -> EpgListing?)? = null,
    onGetIcon: (suspend (Int, String?) -> String?)? = null
) {
    var hasFocus by remember { mutableStateOf(false) }
    
    val displayIcon by produceState<String?>(initialValue = media.icon, key1 = media.id) {
        if (onGetIcon != null) {
            val localIcon = onGetIcon(media.id, media.title)
            if (localIcon != null) {
                value = localIcon
            }
        }
    }

    val epg by produceState<EpgListing?>(initialValue = null, key1 = media.id) {
        if (epgProvider != null) {
            value = epgProvider(media.id, media.title)
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
        label = "chBg"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        color = backgroundColor
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = displayIcon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                if (displayIcon == null && media.icon == null) {
                    ChannelPlaceholder(media.title ?: "?", Modifier.fillMaxSize())
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.title ?: "",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1
                )
                val currentEpg = epg
                if (currentEpg != null) {
                    Text(
                        text = currentEpg.title ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    val now = System.currentTimeMillis() / 1000
                    val start = currentEpg.startTimestamp ?: 0L
                    val end = currentEpg.stopTimestamp ?: 0L
                    if (now in start..end) {
                        val progress = (now - start).toFloat() / (end - start).toFloat()
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }
                }
            }
            
            if (media.isFavorite) {
                Icon(Icons.Default.Favorite, null, tint = Color.Red, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun MediaCard(
    media: MediaSource, 
    modifier: Modifier = Modifier, 
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onGetIcon: (suspend (Int, String?) -> String?)? = null
) {
    var hasFocus by remember { mutableStateOf(false) }
    
    val displayIcon by produceState<String?>(initialValue = media.icon, key1 = media.icon) {
        if (value.isNullOrEmpty() && onGetIcon != null) {
            value = onGetIcon(media.id, media.title)
        }
    }

    Column(
        modifier = modifier
            .width(110.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.1f else 1.0f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
                .border(
                    width = if (hasFocus) 3.dp else 0.dp,
                    color = if (hasFocus) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val isMovie = media.type == MediaType.MOVIE || media.type == MediaType.SERIES
                AsyncImage(
                    model = displayIcon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (displayIcon == null && media.icon == null) {
                    ChannelPlaceholder(
                        media.title ?: "?", 
                        Modifier.fillMaxSize(), 
                        isMovie = isMovie
                    ) 
                }
                
                if (media.isFavorite) {
                    Icon(
                        Icons.Default.Favorite, null, 
                        tint = Color.Red, 
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp)
                    )
                }
            }
        }
        
        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = media.title ?: "",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (hasFocus) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp
                ),
                color = if (hasFocus) Color.White else Color.Gray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            
            if (hasFocus) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!media.rating.isNullOrBlank() && media.rating != "0.0") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(14.dp))
                            Text(text = media.rating, style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }
                    if (!media.genre.isNullOrBlank()) {
                        Text(
                            text = media.genre.split(",").firstOrNull() ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelPlaceholder(title: String, modifier: Modifier = Modifier, isMovie: Boolean = false) {
    val firstLetter = title.firstOrNull()?.uppercase() ?: "?"
    val colorStart = if (isMovie) Color(0xFF1a2a6c) else Color(0xFF232526)
    val colorEnd = if (isMovie) Color(0xFFb21f1f) else Color(0xFF414345)
    
    Box(
        modifier = modifier.background(Brush.verticalGradient(listOf(colorStart, colorEnd))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = firstLetter,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White.copy(alpha = 0.5f)
        )
        if (isMovie) {
            Icon(
                imageVector = Icons.Default.VisibilityOff,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp),
                tint = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}
