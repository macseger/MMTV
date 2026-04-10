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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    val uiState = viewModel.uiState
    val history = uiState.history

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
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Section
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 20.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("SÖKRESULTAT", style = MaterialTheme.typography.titleSmall, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(dbSearchResults) { media ->
                        MediaCard(media) { onMediaSelected(media) }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // History Row (if exists)
                    if (history.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(modifier = Modifier.padding(top = 16.dp)) {
                                Text("FORTSÄTT TITTA", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                                Spacer(modifier = Modifier.height(12.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                                ) {
                                    items(history.take(10)) { item ->
                                        HistoryCard(item) { onMediaSelected(item) }
                                    }
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                            }
                        }
                    }

                    // Main Navigation
                    item { 
                        HomeCard("LIVE TV", Icons.Default.LiveTv, modifier = Modifier.focusRequester(tvFocusRequester)) { 
                            onNavigate("live") 
                        } 
                    }
                    item { HomeCard("FILMER", Icons.Default.Movie) { onNavigate("movies") } }
                    item { HomeCard("SERIER", Icons.Default.Tv) { onNavigate("series") } }
                    item { HomeCard("INSTÄLLNINGAR", Icons.Default.Settings) { onNavigate("settings") } }
                }
            }
            // Push everything up by adding a spacer at the bottom
            Spacer(modifier = Modifier.weight(1f))
        }

        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun HistoryCard(media: MediaSource, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(180.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.05f else 1.0f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .border(
                    width = if (hasFocus) 3.dp else 0.dp,
                    color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (hasFocus) 12.dp else 4.dp)
        ) {
            Box {
                AsyncImage(
                    model = media.icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (media.type == MediaType.LIVE) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(36.dp)
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
    
    // Minimalistisk design: Helt svart bakgrund när ej vald, temafärg när vald.
    val backgroundColor = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Black
    val contentColor = if (hasFocus) Color.Black else Color.White
    val iconSize = if (hasFocus) 56.dp else 48.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.02f else 1.0f) // Lite mindre skalning för att kännas mer subtilt
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = if (hasFocus) 0.dp else 1.dp,
                color = Color.White.copy(alpha = 0.1f), // Väldigt diskret ram när ej vald
                shape = RoundedCornerShape(24.dp)
            )
            .clickable { onClick() },
        color = backgroundColor,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.animateContentSize().size(iconSize),
                tint = contentColor
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (hasFocus) FontWeight.Black else FontWeight.Light,
                    letterSpacing = if (hasFocus) 3.sp else 2.sp
                ),
                color = contentColor,
                textAlign = TextAlign.Center
            )
        }
    }
}
