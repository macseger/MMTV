package com.example.mmtv.ui

import com.example.mmtv.ui.theme.FocusBorderColor
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
    viewModel: MediaViewModel, // Lägg till ViewModel
    initialCategoryIndex: Int = 0,
    initialMediaId: Int? = null,
    isLive: Boolean = true, 
    isTvMode: Boolean = true,
    onCategoryChanged: (Int) -> Unit = {},
    onMediaSelected: (MediaSource) -> Unit,
    onToggleFavorite: (MediaSource) -> Unit = {},
    onItemFocused: (Int) -> Unit = {},
    backgroundColor: Color = Color.Black,
    onBackPressed: (() -> Unit)? = null,
    backNavigatesImmediately: Boolean = false,
    topBarFocusRequester: FocusRequester? = null,
    syntheticAllTitle: String? = null,
    initialVodCategoryKey: VodCategoryKey = VodCategoryKey.All,
    onVodCategorySelected: (VodCategoryKey) -> Unit = {},
    mediaType: MediaType? = null,
    resetToAllToken: Int = 0
) {
    val hasSyntheticAllCategory = !isLive && syntheticAllTitle != null
    val categoryIndexOffset = if (hasSyntheticAllCategory) 1 else 0
    val displayGroupedList = remember(groupedList, syntheticAllTitle, isLive) {
        if (hasSyntheticAllCategory) {
            val allItems = groupedList
                .asSequence()
                .filter { it.categoryId != "HISTORY" && it.categoryId != "FAVORITES" }
                .flatMap { it.items.asSequence() }
                .distinctBy { it.id }
                .sortedByDescending { it.addedDate }
                .toList()
            listOf(
                GroupedMedia(
                    title = syntheticAllTitle!!,
                    categoryId = "__ALL_VOD__",
                    items = allItems
                )
            ) + groupedList
        } else {
            groupedList
        }
    }
    val allVodItems = remember(groupedList, syntheticAllTitle, isLive) {
        if (!hasSyntheticAllCategory) emptyList() else groupedList
            .asSequence()
            .filter { it.categoryId != "HISTORY" && it.categoryId != "FAVORITES" }
            .flatMap { it.items.asSequence() }
            .distinctBy { it.id }
            .sortedByDescending { it.addedDate }
            .toList()
    }
    val vodMetadataIndex = mediaType?.takeIf { it != MediaType.LIVE }
        ?.let(viewModel::vodMetadataIndexFor)
        ?: VodMetadataIndex.Empty
    val vodCategories = remember(groupedList, allVodItems, mediaType, vodMetadataIndex) {
        mediaType?.takeIf { it != MediaType.LIVE }?.let { type ->
            buildVodCategoryDefinitions(type, groupedList, allVodItems.map { it.id }, vodMetadataIndex)
        }.orEmpty()
    }
    val vodItemsById = remember(groupedList, allVodItems) {
        (allVodItems + groupedList.flatMap { it.items }).associateBy { it.id }
    }
    val initialDisplayedCategoryIndex = if (hasSyntheticAllCategory && initialCategoryIndex < 0) {
        0
    } else {
        initialCategoryIndex + categoryIndexOffset
    }
    var selectedCategoryIndex by remember(initialDisplayedCategoryIndex) { mutableIntStateOf(initialDisplayedCategoryIndex) }
    var focusedCategoryIndex by remember(initialDisplayedCategoryIndex) { mutableIntStateOf(initialDisplayedCategoryIndex) }
    var selectedVodCategoryKey by remember(initialVodCategoryKey) { mutableStateOf(initialVodCategoryKey) }
    var focusedVodCategoryKey by remember(initialVodCategoryKey) { mutableStateOf(initialVodCategoryKey) }
    var vodEntryInitialized by remember { mutableStateOf(false) }
    var restoreInitialVodMedia by remember { mutableStateOf(true) }
    var appliedResetToken by rememberSaveable { mutableIntStateOf(0) }
    var debouncedCategoryIndex by remember(initialDisplayedCategoryIndex) { mutableIntStateOf(initialDisplayedCategoryIndex) }
    var lastGridCategoryIndex by remember { mutableIntStateOf(debouncedCategoryIndex) }
    
    val selectedCategory = displayGroupedList.getOrNull(debouncedCategoryIndex)
    val selectedVodCategory = vodCategories.firstOrNull { it.key == selectedVodCategoryKey }
        ?: vodCategories.firstOrNull()
    val selectedVodItems = selectedVodCategory?.mediaIds.orEmpty().mapNotNull(vodItemsById::get)
    // Removed heuristic to avoid issues with empty lists
    
    var focusedMedia by remember { mutableStateOf<MediaSource?>(null) }
    var mediaToShowMenu by remember { mutableStateOf<MediaSource?>(null) }

    val listState = rememberLazyListState()
    val vodCategoryListState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    
    val categoryFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val vodCategoryFocusRequesters = remember { mutableMapOf<VodCategoryKey, FocusRequester>() }
    val channelFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    // Debounce category change to avoid jank when scrolling fast
    LaunchedEffect(selectedCategoryIndex) {
        if (isLive && selectedCategoryIndex != debouncedCategoryIndex) {
            delay(200) // Keep the existing Live TV debounce behavior.
            debouncedCategoryIndex = selectedCategoryIndex
            onCategoryChanged(selectedCategoryIndex - categoryIndexOffset)
        }
    }

    fun FocusRequester.safeFocus() {
        runCatching { this.requestFocus() }
    }

    LaunchedEffect(isLive, initialCategoryIndex, initialMediaId, resetToAllToken) {
        if (!isLive) return@LaunchedEffect
        delay(100)
        val resetRequested = resetToAllToken > 0 && resetToAllToken != appliedResetToken
        if (resetRequested) appliedResetToken = resetToAllToken
        val targetCategoryIndex = initialDisplayedCategoryIndex
        selectedCategoryIndex = targetCategoryIndex
        focusedCategoryIndex = targetCategoryIndex
        debouncedCategoryIndex = targetCategoryIndex
        if (resetRequested) onCategoryChanged(0)

        if (isTvMode) {
            if (!resetRequested && initialMediaId != null) {
                val index = selectedCategory?.items?.indexOfFirst { it.id == initialMediaId } ?: -1
                if (index != -1) {
                    listState.scrollToItem(index)
                    delay(50)
                    channelFocusRequesters[initialMediaId]?.safeFocus()
                } else {
                    categoryFocusRequesters[selectedCategoryIndex]?.safeFocus()
                }
            } else {
                categoryFocusRequesters[selectedCategoryIndex]?.safeFocus()
            }
        }
    }

    LaunchedEffect(isLive, initialVodCategoryKey, initialMediaId, resetToAllToken) {
        if (isLive || vodCategories.isEmpty()) return@LaunchedEffect
        val resetRequested = resetToAllToken > 0 && resetToAllToken != appliedResetToken
        if (vodEntryInitialized && !resetRequested) return@LaunchedEffect
        vodEntryInitialized = true
        restoreInitialVodMedia = !resetRequested
        if (resetRequested) appliedResetToken = resetToAllToken
        val requestedKey = if (resetRequested) VodCategoryKey.All else initialVodCategoryKey
        val targetKey = restoreVodCategoryKey(requestedKey, vodCategories)
        selectedVodCategoryKey = targetKey
        focusedVodCategoryKey = targetKey
        onVodCategorySelected(targetKey)
        if (isTvMode && (resetRequested || initialMediaId == null)) {
            withFrameNanos { }
            vodCategoryFocusRequesters[targetKey]?.safeFocus()
        }
    }

    LaunchedEffect(isLive, selectedVodCategoryKey, initialMediaId, selectedVodItems) {
        if (isLive || !restoreInitialVodMedia || initialMediaId == null ||
            selectedVodItems.none { it.id == initialMediaId }
        ) {
            return@LaunchedEffect
        }
        val index = selectedVodItems.indexOfFirst { it.id == initialMediaId }
        if (index >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            gridState.scrollToItem(index)
        }
        withFrameNanos { }
        channelFocusRequesters[initialMediaId]?.safeFocus()
    }

    LaunchedEffect(debouncedCategoryIndex) {
        if (isLive) listState.scrollToItem(0)
    }

    LaunchedEffect(selectedVodCategoryKey) {
        if (!isLive && lastGridCategoryIndex != vodCategories.indexOfFirst { it.key == selectedVodCategoryKey }) {
            gridState.scrollToItem(0)
            lastGridCategoryIndex = vodCategories.indexOfFirst { it.key == selectedVodCategoryKey }
        }
    }

    var isSidebarFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(modifier = Modifier
        .fillMaxSize()
        .background(backgroundColor)
        .onKeyEvent { 
            if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && 
                it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                if (backNavigatesImmediately && onBackPressed != null) {
                    onBackPressed()
                    true
                } else if (!isSidebarFocused) {
                    if (isLive) categoryFocusRequesters[selectedCategoryIndex]?.safeFocus()
                    else vodCategoryFocusRequesters[selectedVodCategoryKey]?.safeFocus()
                    true
                } else {
                    if (topBarFocusRequester != null) {
                        topBarFocusRequester.safeFocus()
                        true
                    } else {
                        false // Let the system handle it (exit screen)
                    }
                }
            } else false
        }
    ) {
        // COLUMN 1: CATEGORIES (TiviMate Sidebar Style)
        if (isLive) Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(240.dp)
                .background(backgroundColor)
                .onFocusChanged { isSidebarFocused = it.hasFocus }
                .padding(vertical = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(displayGroupedList.size, key = { index -> displayGroupedList[index].categoryId ?: index }) { index ->
                    val category = displayGroupedList.getOrNull(index)
                    val title = categoryDisplayTitle(category?.title, category?.categoryId, isLive)
                    val icon = systemCategoryIcon(category?.categoryId, category?.title)
                    val requester = categoryFocusRequesters.getOrPut(index) { FocusRequester() }
                    
                    CategoryItem(
                        title = title,
                        icon = icon,
                        isSelected = selectedCategoryIndex == index,
                        modifier = Modifier
                            .focusRequester(requester)
                            .onFocusChanged { 
                                if (it.isFocused) {
                                    focusedCategoryIndex = index
                                    if (isLive) selectedCategoryIndex = index
                                }
                            }
                            .onKeyEvent {
                                if (!isLive &&
                                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) &&
                                    it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                                ) {
                                    selectedCategoryIndex = index
                                    true
                                } else if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    val firstChannelId = displayGroupedList.getOrNull(selectedCategoryIndex)?.items?.firstOrNull()?.id
                                    if (firstChannelId != null) {
                                        channelFocusRequesters[firstChannelId]?.safeFocus()
                                        true
                                    } else false
                                } else false
                            },
                        onClick = { 
                            selectedCategoryIndex = index
                            focusedCategoryIndex = index
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
                    val headingIcon = systemCategoryIcon(selectedCategory?.categoryId, selectedCategory?.title)
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (headingIcon != null) {
                            Icon(
                                imageVector = headingIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = categoryDisplayTitle(selectedCategory?.title, selectedCategory?.categoryId, isLive),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black
                        )
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(selectedCategory?.items ?: emptyList(), key = { it.id }) { media ->
                            val requester = channelFocusRequesters.getOrPut(media.id) { FocusRequester() }
                    TvChannelItem(
                        media = media,
                        viewModel = viewModel,
                        modifier = Modifier
                            .focusRequester(requester)
                            .onFocusChanged { if (it.isFocused) {
                                focusedMedia = media
                                onItemFocused(media.id)
                            } }
                            .onKeyEvent { 
                                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    categoryFocusRequesters[selectedCategoryIndex]?.safeFocus()
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
                        val currentEpg = viewModel.getEpgForId(media.id, media.type, media.title)
                        val nextEpg = viewModel.getNextEpgForId(media.id, media.type, media.title)
                        val displayIcon = viewModel.getIconForId(media.id, media.type, media.title) ?: media.icon
                        
                        LiveDetailPane(media = media, currentEpg = currentEpg, nextEpg = nextEpg, displayIcon = displayIcon)
                    }
                }
            }
        } else {
            // NETFLIX-STYLE VOD GRID
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                LazyRow(
                    state = vodCategoryListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isSidebarFocused = it.hasFocus },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    items(vodCategories, key = { it.key.stableValue }) { category ->
                        val requester = vodCategoryFocusRequesters.getOrPut(category.key) { FocusRequester() }
                        VodCategoryTab(
                            title = categoryDisplayTitle(
                                category.title,
                                when (val key = category.key) {
                                    VodCategoryKey.All -> "__ALL_VOD__"
                                    VodCategoryKey.History -> "HISTORY"
                                    VodCategoryKey.Favorites -> "FAVORITES"
                                    is VodCategoryKey.Provider -> key.categoryId
                                    else -> null
                                },
                                false
                            ),
                            isSelected = selectedVodCategoryKey == category.key,
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged {
                                    if (it.isFocused) focusedVodCategoryKey = category.key
                                }
                                .onKeyEvent { event ->
                                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                                        false
                                    } else when (event.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                            selectedVodCategoryKey = focusedVodCategoryKey
                                            onVodCategorySelected(focusedVodCategoryKey)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            val firstMediaId = selectedVodItems.firstOrNull()?.id
                                            if (firstMediaId == null) {
                                                true
                                            } else {
                                                scope.launch {
                                                    gridState.scrollToItem(0)
                                                    withFrameNanos { }
                                                    channelFocusRequesters[firstMediaId]?.safeFocus()
                                                }
                                                true
                                            }
                                        }
                                        else -> false
                                    }
                                },
                            onClick = {
                                selectedVodCategoryKey = category.key
                                focusedVodCategoryKey = category.key
                                onVodCategorySelected(category.key)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val loadingKey = when (val key = selectedVodCategoryKey) {
                    VodCategoryKey.All -> "__ALL_VOD__"
                    is VodCategoryKey.Provider -> key.categoryId
                    else -> key.stableValue
                }
                val isCategoryLoading = mediaType != null &&
                    viewModel.loadingCategory == (mediaType to loadingKey)
                Box(modifier = Modifier.fillMaxSize()) {
                    val hidePartialSyntheticAll = isCategoryLoading && loadingKey == "__ALL_VOD__"
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 124.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        val items = if (hidePartialSyntheticAll) emptyList() else selectedVodItems
                        itemsIndexed(items, key = { _, media -> media.id }) { itemIndex, media ->
                            val requester = channelFocusRequesters.getOrPut(media.id) { FocusRequester() }

                            MediaCard(
                                media = media,
                                viewModel = viewModel,
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .onFocusChanged { if (it.isFocused) focusedMedia = media }
                                    .onKeyEvent { event ->
                                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                                            gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == itemIndex }
                                                ?.row == 0 &&
                                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                                        ) {
                                            scope.launch {
                                                val categoryIndex = vodCategories.indexOfFirst {
                                                    it.key == selectedVodCategoryKey
                                                }.coerceAtLeast(0)
                                                vodCategoryListState.scrollToItem(categoryIndex)
                                                withFrameNanos { }
                                                vodCategoryFocusRequesters[selectedVodCategoryKey]?.safeFocus()
                                            }
                                            true
                                        } else false
                                    },
                                onClick = { onMediaSelected(media) },
                                onToggleFavorite = { mediaToShowMenu = media }
                            )
                        }
                    }
                    if (isCategoryLoading && (hidePartialSyntheticAll || selectedVodItems.isEmpty())) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                            Text("Laddar…", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                        }
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

private fun systemCategoryIcon(categoryId: String?, title: String?): ImageVector? {
    return when (categoryId) {
        "ALL_CHANNELS" -> Icons.Default.LiveTv
        "HISTORY" -> Icons.Default.History
        "FAVORITES" -> Icons.Default.StarBorder
        "__ALL_VOD__" -> if (title?.contains("SERIER", ignoreCase = true) == true) {
            Icons.Default.Tv
        } else {
            Icons.Default.Movie
        }
        else -> null
    }
}

private fun categoryDisplayTitle(title: String?, categoryId: String?, isLive: Boolean): String {
    val rawTitle = title ?: "Kategori"
    val withoutSystemEmoji = if (categoryId in setOf("ALL_CHANNELS", "HISTORY", "FAVORITES")) {
        rawTitle.replaceFirst(Regex("^\\s*(?:📺|🕒|⭐)\\s*"), "")
    } else {
        rawTitle
    }
    if (isLive) return withoutSystemEmoji

    // Display-only cleanup; the original title and category ID remain unchanged.
    return withoutSystemEmoji
        .replaceFirst(
            Regex("^\\s*(?:series|serier|movie|movies|film|filmer)\\s*:\\s*", RegexOption.IGNORE_CASE),
            ""
        )
        .ifBlank { rawTitle }
}

@Composable
fun CategoryItem(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
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
        border = if (hasFocus) androidx.compose.foundation.BorderStroke(3.dp, FocusBorderColor) else null,
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        val contentColor = if (isSelected) Color.Black else if (hasFocus) Color.White else Color.Gray
        if (icon == null) {
            Text(
                text = title,
                modifier = Modifier.padding(16.dp, 12.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected || hasFocus) FontWeight.Bold else FontWeight.Normal
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Row(
                modifier = Modifier.padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isSelected || hasFocus) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VodCategoryTab(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val contentColor = when {
        isSelected -> Color.Black
        hasFocus -> Color.White
        else -> Color.Gray
    }
    Surface(
        modifier = modifier
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        color = when {
            isSelected -> MaterialTheme.colorScheme.primary
            hasFocus -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            else -> Color(0xFF111111)
        },
        border = if (hasFocus) {
            androidx.compose.foundation.BorderStroke(2.dp, FocusBorderColor)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        },
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected || hasFocus) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TvChannelItem(
    media: MediaSource, 
    viewModel: MediaViewModel, // Ta in ViewModel direkt för effektivare datahämtning
    modifier: Modifier = Modifier, 
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    
    val displayIcon = viewModel.getIconForId(media.id, media.type, media.title) ?: media.icon
    val epg = viewModel.getEpgForId(media.id, media.type, media.title)

    // Skippa animateColorAsState för "instant" känsla på TV
    val backgroundColor = if (hasFocus) viewModel.currentThemeColor.copy(alpha = 0.7f) else Color.Transparent

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .graphicsLayer { 
                // Använd graphicsLayer för att undvika onödiga omritningar
                clip = true
                shape = RoundedCornerShape(4.dp)
            }
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
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier, 
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    
    val displayIcon = viewModel.getIconForId(media.id, media.type, media.title) ?: media.icon

    Column(
        modifier = modifier
            .width(110.dp)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .graphicsLayer {
                // Hårdvaruaccelererad skalning
                scaleX = if (hasFocus) 1.08f else 1.0f
                scaleY = if (hasFocus) 1.08f else 1.0f
            }
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
