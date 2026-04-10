package com.example.mmtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mmtv.model.MediaSource

@Composable
fun HomeScreen(
    viewModel: MediaViewModel,
    onNavigate: (String) -> Unit,
    onMediaSelected: (MediaSource) -> Unit
) {
    val tvFocusRequester = remember { FocusRequester() }
    val dbSearchResults by viewModel.dbSearchResults.collectAsState()
    val uiState = viewModel.uiState

    // 1. Hantera back-knappen vid sökning
    BackHandler(enabled = viewModel.searchQuery.isNotEmpty()) {
        viewModel.searchQuery = ""
    }

    // Fokusera på TV-ikonen när skärmen laddas
    LaunchedEffect(Unit) {
        if (viewModel.searchQuery.isEmpty()) {
            tvFocusRequester.requestFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Multimedia TV Rubrik
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
            ) {
                Text(
                    "MULTIMEDIA TV",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                        .padding(top = 4.dp)
                )
            }
            
            // Search Bar på startsidan
            OutlinedTextField(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.searchQuery = it },
                placeholder = { Text("Sök på film eller serier...") },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 16.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            if (viewModel.searchQuery.isNotEmpty()) {
                // Optimerad sökvy med databasresultat
                Column(modifier = Modifier.fillMaxWidth(0.9f).weight(1f)) {
                    Text(
                        "Sökresultat (${dbSearchResults.size}):",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        items(dbSearchResults) { media ->
                            MediaCard(media) { onMediaSelected(media) }
                        }
                    }
                }
            } else {
                // Visa vanliga menyn
                Spacer(modifier = Modifier.weight(0.1f))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    item { 
                        HomeCard("TV", Icons.Default.Tv, modifier = Modifier.focusRequester(tvFocusRequester)) { 
                            onNavigate("live") 
                        } 
                    }
                    item { HomeCard("FILM", Icons.Default.Movie) { onNavigate("movies") } }
                    item { HomeCard("SERIER", Icons.Default.LiveTv) { onNavigate("series") } }
                    item { HomeCard("INSTÄLLNINGAR", Icons.Default.Settings) { onNavigate("settings") } }
                }
                Spacer(modifier = Modifier.weight(0.3f))
            }
        }

        // Laddningsindikator
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 6.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Laddar spellista...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
fun HomeCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.05f else 1.0f)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (hasFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(54.dp),
                    tint = if (hasFocus) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (hasFocus) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
