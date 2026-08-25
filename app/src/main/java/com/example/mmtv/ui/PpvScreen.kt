package com.example.mmtv.ui

import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource

@Composable
fun PpvScreen(
    groupedList: List<GroupedMedia>,
    initialCategoryIndex: Int = 0,
    isTvMode: Boolean = true,
    onCategoryChanged: (Int) -> Unit = {},
    onMediaSelected: (MediaSource) -> Unit,
    onGetIcon: suspend (Int, com.example.mmtv.model.MediaType, String?) -> String? = { _, _, _ -> null },
    topBarFocusRequester: FocusRequester? = null
) {
    var selectedCategoryIndex by remember(initialCategoryIndex) { mutableIntStateOf(initialCategoryIndex) }
    var debouncedCategoryIndex by remember(initialCategoryIndex) { mutableIntStateOf(initialCategoryIndex) }
    
    val selectedCategory = groupedList.getOrNull(debouncedCategoryIndex)
    
    val listState = rememberLazyListState()
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

    LaunchedEffect(initialCategoryIndex) {
        delay(100)
        selectedCategoryIndex = initialCategoryIndex
        debouncedCategoryIndex = initialCategoryIndex
        if (isTvMode) {
            categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
        }
    }

    LaunchedEffect(debouncedCategoryIndex) {
        listState.scrollToItem(0)
    }

    var isSidebarFocused by remember { mutableStateOf(false) }

    Row(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .onKeyEvent { 
            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && 
                it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                if (!isSidebarFocused) {
                    categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
                    true
                } else {
                    if (topBarFocusRequester != null) {
                        topBarFocusRequester.requestFocus()
                        true
                    } else {
                        false // Let the system handle it
                    }
                }
            } else false
        }
    ) {
        // COLUMN 1: CATEGORIES
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(260.dp)
                .background(Color(0xFF0A0A0A))
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

        // COLUMN 2: CONTENT
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
            Text(
                text = selectedCategory?.title ?: "",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF00D1FF) // Turkos färg som i bilden
                ),
                modifier = Modifier.padding(start = 32.dp, top = 32.dp, bottom = 16.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().onKeyEvent {
                    if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
                        true
                    } else false
                },
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(selectedCategory?.items ?: emptyList(), key = { it.id }) { media ->
                    val requester = channelFocusRequesters.getOrPut(media.id) { FocusRequester() }
                    PpvItem(
                        media = media,
                        onGetIcon = { id, name -> onGetIcon(id, media.type, name) },
                        modifier = Modifier
                            .focusRequester(requester)
                            .onKeyEvent { 
                                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    categoryFocusRequesters[selectedCategoryIndex]?.requestFocus()
                                    true
                                } else false
                            },
                        onClick = { onMediaSelected(media) }
                    )
                }
            }
        }
    }
}

@Composable
fun PpvItem(
    media: MediaSource, 
    modifier: Modifier = Modifier, 
    onClick: () -> Unit,
    onGetIcon: suspend (Int, String?) -> String?
) {
    var hasFocus by remember { mutableStateOf(false) }
    
    val displayIcon by produceState<String?>(initialValue = media.icon, key1 = media) {
        val localIcon = onGetIcon(media.id, media.title)
        if (localIcon != null) {
            value = localIcon
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (hasFocus) Color(0xFF00D1FF).copy(alpha = 0.2f) else Color.Transparent,
        label = "ppvItemBg"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Square Icon
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = displayIcon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                if (displayIcon == null && media.icon == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = media.title?.firstOrNull()?.toString() ?: "?",
                            color = Color.Gray
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(24.dp))
            
            Text(
                text = media.title ?: "",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (hasFocus) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 20.sp
                ),
                color = if (hasFocus) Color.White else Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
