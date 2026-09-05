package com.example.mmtv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.mmtv.model.MediaSource
import com.example.mmtv.ui.theme.FocusBorderColor

@Composable
fun TvFavoritesDialog(viewModel: MediaViewModel) {
    var allChannels by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        isLoading = true
        val channels = viewModel.getAllLiveChannelsForFavorites()
        allChannels = channels
        selectedIds = channels.filter { it.isFavorite }.map { it.id }
        isLoading = false
    }

    LaunchedEffect(searchQuery) {
        listState.scrollToItem(0)
    }

    val filteredChannels = remember(allChannels, searchQuery) {
        if (searchQuery.isBlank()) {
            allChannels
        } else {
            allChannels.filter { channel ->
                channel.title.orEmpty().contains(searchQuery, ignoreCase = true) ||
                        channel.categoryName.orEmpty().contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = viewModel::dismissTvFavoritesDialog,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF141414)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // VÄNSTER PANEL: Kontroller & Knappar
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Rubrik
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "TV Favoritlista",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }

                    Text(
                        text = "${allChannels.size} kanaler totalt\n${selectedIds.size} valda favoriter",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Sökfält
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Sök kanal (t.ex. SVT, TV4)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Huvudknappar
                    var saveFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { viewModel.saveLiveFavorites(selectedIds) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstFocus)
                            .onFocusChanged { saveFocused = it.isFocused },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (saveFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    ) {
                        Text("Spara favoriter (${selectedIds.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    OutlinedButton(
                        onClick = viewModel::dismissTvFavoritesDialog,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Avbryt")
                    }

                    HorizontalDivider(
                        color = Color.Gray.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Text(
                        text = "Snabbval:",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )

                    OutlinedButton(
                        onClick = {
                            val shownIds = filteredChannels.map { it.id }
                            selectedIds = (selectedIds + shownIds).distinct()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
                    ) {
                        Text("Markera visade")
                    }

                    OutlinedButton(
                        onClick = {
                            val shownSet = filteredChannels.map { it.id }.toSet()
                            selectedIds = selectedIds.filterNot { it in shownSet }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
                    ) {
                        Text("Avmarkera visade")
                    }

                    OutlinedButton(
                        onClick = { selectedIds = emptyList() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red.copy(alpha = 0.8f))
                    ) {
                        Text("Rensa alla")
                    }
                }

                // HÖGER PANEL: Kanalnät
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Välj kanaler (${filteredChannels.size} visas):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 280.dp),
                            modifier = Modifier
                                .fillMaxSize(),
                            state = listState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            if (filteredChannels.isEmpty()) {
                                item {
                                    Text(
                                        text = "Inga kanaler matchar sökningen.",
                                        color = Color.Gray,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }

                            items(filteredChannels, key = { it.id }) { channel ->
                                val isChecked = channel.id in selectedIds
                                var isFocused by remember { mutableStateOf(false) }

                                Surface(
                                    onClick = {
                                        selectedIds = if (isChecked) {
                                            selectedIds.filterNot { it == channel.id }
                                        } else {
                                            selectedIds + channel.id
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .onFocusChanged { isFocused = it.isFocused },
                                    shape = MaterialTheme.shapes.medium,
                                    border = if (isFocused) BorderStroke(3.dp, FocusBorderColor) else null,
                                    color = if (isFocused) Color.White.copy(alpha = 0.15f) else Color(0xFF222222)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                            contentDescription = if (isChecked) "Favorit" else "Ej favorit",
                                            tint = if (isChecked) Color(0xFFFFD700) else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))

                                        if (!channel.icon.isNullOrBlank()) {
                                            AsyncImage(
                                                model = channel.icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(36.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = channel.title ?: "Okänd kanal",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                ),
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!channel.categoryName.isNullOrBlank()) {
                                                Text(
                                                    text = channel.categoryName,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                    color = Color.Gray,
                                                    maxLines = 1,
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

        LaunchedEffect(Unit) {
            if (viewModel.isTvMode) {
                firstFocus.requestFocus()
            }
        }
    }
}
