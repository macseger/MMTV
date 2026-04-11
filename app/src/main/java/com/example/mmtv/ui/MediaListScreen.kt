package com.example.mmtv.ui

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    onCategoryChanged: (Int) -> Unit = {},
    onMediaSelected: (MediaSource) -> Unit,
    onToggleFavorite: (MediaSource) -> Unit = {},
    onHideCategory: (String) -> Unit = {},
    epgProvider: (Int) -> EpgListing? = { null },
    nextEpgProvider: (Int) -> EpgListing? = { null },
    onItemFocused: (Int) -> Unit = {},
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    onBackPressed: (() -> Unit)? = null
) {
    var selectedCategoryIndex by remember { mutableIntStateOf(initialCategoryIndex) }
    val selectedCategory = groupedList.getOrNull(selectedCategoryIndex)
    val isLive = selectedCategory?.items?.firstOrNull()?.type == MediaType.LIVE
    
    var categoryToShowMenu by remember { mutableStateOf<String?>(null) }
    var mediaToShowMenu by remember { mutableStateOf<MediaSource?>(null) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    
    val categoryFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val channelFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    // Fokusera på vald kanal eller kategori när skärmen laddas
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
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

    // Scrolla till toppen när kategori ändras för att säkerställa att första item är redo för fokus
    LaunchedEffect(selectedCategoryIndex) {
        if (isLive) listState.scrollToItem(0)
        else gridState.scrollToItem(0)
    }

    Row(modifier = Modifier
        .fillMaxSize()
        .background(backgroundColor)
        .onKeyEvent { 
            if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && 
                it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                onBackPressed != null) {
                onBackPressed()
                true
            } else false
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(240.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .padding(vertical = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(groupedList.size, key = { index -> groupedList[index].title ?: index }) { index ->
                    val title = groupedList.getOrNull(index)?.title ?: "Kategori"
                    val requester = categoryFocusRequesters.getOrPut(index) { FocusRequester() }
                    CategoryItem(
                        title = title,
                        isSelected = selectedCategoryIndex == index,
                        modifier = Modifier
                            .focusRequester(requester)
                            .onFocusChanged { 
                                if (it.isFocused && selectedCategoryIndex != index) {
                                    selectedCategoryIndex = index
                                    onCategoryChanged(index)
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
                            onCategoryChanged(index)
                        },
                        onLongClick = {
                            categoryToShowMenu = title
                        }
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = selectedCategory?.title ?: "",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (selectedCategory != null) {
                if (isLive) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        items(selectedCategory.items, key = { it.id }) { media ->
                            val requester = channelFocusRequesters.getOrPut(media.id) { FocusRequester() }
                            TvChannelItem(
                                media = media,
                                epg = epgProvider(media.id),
                                nextEpg = nextEpgProvider(media.id),
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .onKeyEvent { 
                                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
                                            true
                                        } else false
                                    },
                                onFocused = { onItemFocused(media.id) },
                                onClick = { onMediaSelected(media) },
                                onToggleFavorite = { mediaToShowMenu = media }
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(selectedCategory.items, key = { it.id }) { media ->
                            val requester = channelFocusRequesters.getOrPut(media.id) { FocusRequester() }
                            MediaCard(
                                media = media,
                                modifier = Modifier
                                    .focusRequester(requester),
                                onClick = { onMediaSelected(media) },
                                onToggleFavorite = { mediaToShowMenu = media }
                            )
                        }
                    }
                }
            }
        }
    }

    if (mediaToShowMenu != null) {
        val media = mediaToShowMenu!!
        AlertDialog(
            onDismissRequest = { mediaToShowMenu = null },
            title = { 
                Text(
                    media.title ?: "Alternativ",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                ) 
            },
            text = { 
                Text(
                    if (media.isFavorite) "Vill du ta bort från favoriter?" else "Vill du lägga till i favoriter?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            },
            containerColor = Color(0xFF121212),
            shape = RoundedCornerShape(16.dp),
            confirmButton = {
                val focusRequester = remember { FocusRequester() }
                var isFocused by remember { mutableStateOf(false) }
                
                Button(
                    onClick = {
                        onToggleFavorite(media)
                        mediaToShowMenu = null
                    },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused }
                        .padding(horizontal = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                        contentColor = if (isFocused) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(if (media.isFavorite) Icons.Default.FavoriteBorder else Icons.Default.Favorite, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (media.isFavorite) "Ta bort" else "Lägg till", fontWeight = FontWeight.Bold)
                }
                
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            },
            dismissButton = {
                var isFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = { mediaToShowMenu = null },
                    modifier = Modifier
                        .onFocusChanged { isFocused = it.isFocused }
                        .padding(horizontal = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                        contentColor = if (isFocused) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Avbryt", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (categoryToShowMenu != null) {
        val isSpecial = categoryToShowMenu?.let { 
            val lower = it.lowercase()
            lower.contains("historik") || lower.contains("senast sedda") || 
            lower.contains("history") || lower.contains("favorit") || 
            lower.contains("★") 
        } ?: false
        
        AlertDialog(
            onDismissRequest = { categoryToShowMenu = null },
            title = { 
                Text(
                    if (isSpecial) "Systemkategori" else "Kategorinställningar",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                ) 
            },
            text = { 
                Text(
                    if (isSpecial) 
                        "Kategorin \"$categoryToShowMenu\" är viktig för appens funktion och kan inte döljas."
                    else 
                        "Vill du dölja kategorin \"$categoryToShowMenu\"? Du kan visa den igen under Inställningar.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            },
            containerColor = Color(0xFF121212),
            shape = RoundedCornerShape(16.dp),
            confirmButton = {
                val focusRequester = remember { FocusRequester() }
                var isFocused by remember { mutableStateOf(false) }
                
                Button(
                    onClick = {
                        if (!isSpecial) onHideCategory(categoryToShowMenu!!)
                        categoryToShowMenu = null
                    },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused }
                        .padding(horizontal = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                        contentColor = if (isFocused) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (!isSpecial) {
                        Icon(Icons.Default.VisibilityOff, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Dölj kategori", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Okej", fontWeight = FontWeight.Bold)
                    }
                }
                
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            },
            dismissButton = {
                if (!isSpecial) {
                    var isFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { categoryToShowMenu = null },
                        modifier = Modifier
                            .onFocusChanged { isFocused = it.isFocused }
                            .padding(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                            contentColor = if (isFocused) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Avbryt", fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }
}

@Composable
fun CategoryItem(title: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            hasFocus -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        label = "bgColor"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .onKeyEvent { keyEvent ->
                val isCenterKey = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                                 keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                
                if (isCenterKey && keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime < 500) { // 500ms fönster för dubbelklick
                        onLongClick()
                        lastClickTime = 0L // Återställ efter dubbelklick
                    } else {
                        onClick()
                        lastClickTime = currentTime
                    }
                    return@onKeyEvent true
                }
                false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < 500) {
                    onLongClick()
                    lastClickTime = 0L
                } else {
                    onClick()
                    lastClickTime = currentTime
                }
            },
        color = backgroundColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(12.dp, 10.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (isSelected || hasFocus) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp
            ),
            color = if (isSelected) Color.Black else if (hasFocus) Color.White else Color.LightGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TvChannelItem(
    media: MediaSource, 
    epg: EpgListing?, 
    nextEpg: EpgListing?, 
    modifier: Modifier = Modifier, 
    onFocused: () -> Unit, 
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    
    LaunchedEffect(hasFocus) {
        if (hasFocus) onFocused()
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        label = "bgColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.02f else 1.0f)
            .onKeyEvent { keyEvent ->
                val isCenterKey = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                                 keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                
                if (isCenterKey) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (pressJob == null) {
                            pressJob = scope.launch {
                                delay(700)
                                onToggleFavorite()
                                pressJob = null
                            }
                        }
                        return@onKeyEvent true
                    } else if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                        val isLongPress = pressJob == null
                        pressJob?.cancel()
                        pressJob = null
                        if (!isLongPress) {
                            onClick()
                        }
                        return@onKeyEvent true
                    }
                }
                false
            }
            .clickable { onClick() }
            .border(
                width = if (hasFocus) 2.dp else 1.dp,
                color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.05f),
                shape = MaterialTheme.shapes.medium
            ),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.TopStart) {
                AsyncImage(
                    model = media.icon,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Fit
                )
                if (media.isFavorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier.size(16.dp).offset(x = (-4).dp, y = (-4).dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Kolumn 1: Nuvarande program
            Column(modifier = Modifier.weight(0.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = media.title ?: "Okänd kanal",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (epg != null && epg.startTimestamp != null && epg.stopTimestamp != null) {
                        val start = formatter.format(Instant.ofEpochSecond(epg.startTimestamp))
                        val stop = formatter.format(Instant.ofEpochSecond(epg.stopTimestamp))
                        Text(
                            text = "$start - $stop",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                if (epg != null) {
                    Text(
                        text = epg.title ?: "Inget program-info",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    val now = System.currentTimeMillis() / 1000
                    val start = epg.startTimestamp ?: 0L
                    val end = epg.stopTimestamp ?: 0L
                    
                    if (start > 0 && end > start) {
                        val progress = (now - start).toFloat() / (end - start).toFloat()
                        if (progress in 0f..1f) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(MaterialTheme.shapes.extraSmall),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Ingen information tillgänglig",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Separator-linje
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
            Spacer(modifier = Modifier.width(16.dp))

            // Kolumn 2: Nästa program
            Column(modifier = Modifier.weight(0.4f)) {
                if (nextEpg != null) {
                    val nextStart = formatter.format(Instant.ofEpochSecond(nextEpg.startTimestamp ?: 0))
                    Text(
                        text = "NÄSTA ($nextStart)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = nextEpg.title ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "NÄSTA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
fun MediaCard(
    media: MediaSource, 
    modifier: Modifier = Modifier, 
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Column(
        modifier = modifier
            .width(130.dp)
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
                                onToggleFavorite()
                                pressJob = null
                            }
                        }
                        return@onKeyEvent true
                    } else if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                        val isLongPress = pressJob == null
                        pressJob?.cancel()
                        pressJob = null
                        if (!isLongPress) {
                            onClick()
                        }
                        return@onKeyEvent true
                    }
                }
                false
            }
            .clickable(onClick = { onClick() }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .border(
                    width = if (hasFocus) 3.dp else 0.dp,
                    color = if (hasFocus) MaterialTheme.colorScheme.secondary else Color.Transparent,
                    shape = MaterialTheme.shapes.medium
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (hasFocus) 8.dp else 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = media.icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (media.isFavorite) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp).padding(4.dp)
                        )
                    }
                }
            }
        }
        Text(
            text = media.title ?: "Okänd",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            maxLines = 2,
            minLines = 2,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = if (hasFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Ellipsis
        )
    }
}
