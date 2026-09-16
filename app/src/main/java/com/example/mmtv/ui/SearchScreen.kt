package com.example.mmtv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun SearchScreen(
    viewModel: MediaViewModel,
    onMediaSelected: (MediaSource) -> Unit
) {
    val dbSearchResults by viewModel.dbSearchResults.collectAsState()
    val searchControlFocusRequester = remember { FocusRequester() }
    val searchFieldFocusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var isSearchExpanded by remember { mutableStateOf(true) }
    var searchControlHasFocus by remember { mutableStateOf(false) }
    val fieldWidth by animateDpAsState(
        targetValue = (180 + (viewModel.searchQuery.length - 14).coerceAtLeast(0) * 8)
            .coerceAtMost(520).dp,
        animationSpec = tween(durationMillis = 140),
        label = "searchFieldWidth"
    )

    LaunchedEffect(Unit) {
        searchFieldFocusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            val searchControlShape = RoundedCornerShape(8.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .focusRequester(searchControlFocusRequester)
                    .onFocusChanged { searchControlHasFocus = it.isFocused }
                    .background(
                        color = if (searchControlHasFocus) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            Color.White.copy(alpha = 0.06f)
                        },
                        shape = searchControlShape
                    )
                    .border(
                        width = 1.dp,
                        color = if (searchControlHasFocus) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        },
                        shape = searchControlShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (isSearchExpanded) {
                            searchFieldFocusRequester.requestFocus()
                        } else {
                            isSearchExpanded = true
                        }
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Aktivera sökning",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(23.dp)
                )
            }

            AnimatedVisibility(
                visible = isSearchExpanded,
                enter = expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = tween(durationMillis = 160)
                ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(durationMillis = 140)
                ) + fadeOut(animationSpec = tween(durationMillis = 100))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = viewModel.searchQuery,
                        onValueChange = { viewModel.searchQuery = it },
                        modifier = Modifier
                            .width(fieldWidth)
                            .focusRequester(searchFieldFocusRequester)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { keyboardController?.hide() }
                        ),
                        cursorBrush = Brush.verticalGradient(listOf(Color.White, Color.White)),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (viewModel.searchQuery.isEmpty()) {
                                    Text(
                                        "Skriv för att söka...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White.copy(alpha = 0.45f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }

        if (viewModel.searchQuery.length >= 2) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "SÖKRESULTAT (${dbSearchResults.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                    )
                }

                items(dbSearchResults, key = { media -> "${media.type}:${media.id}" }) { media ->
                    SearchMediaCard(
                        media = media,
                        viewModel = viewModel,
                        onClick = { onMediaSelected(media) }
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    if (viewModel.searchQuery.isEmpty()) {
                        "Sök efter filmer, serier eller kanaler"
                    } else {
                        "Skriv minst två tecken för att söka"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun SearchMediaCard(
    media: MediaSource,
    viewModel: MediaViewModel,
    onClick: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val displayIcon = viewModel.getIconForId(media.id, media.type, media.title) ?: media.icon
    val isLive = media.type == MediaType.LIVE
    val cardShape = RoundedCornerShape(8.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.02f else 1.0f)
            .background(
                color = if (hasFocus) Color(0xFF1B2225) else Color(0xFF141414),
                shape = cardShape
            )
            .border(
                width = 1.dp,
                color = if (hasFocus) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.08f)
                },
                shape = cardShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            modifier = Modifier
                .width(76.dp)
                .height(76.dp),
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = displayIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isLive) 7.dp else 0.dp),
                    contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop
                )

                if (displayIcon == null && media.icon == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            media.title?.take(1) ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.DarkGray
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = media.title ?: "",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (hasFocus) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 16.sp
                ),
                color = if (hasFocus) Color.White else Color.LightGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )

            if (!media.categoryName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = media.categoryName.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.45.sp
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
