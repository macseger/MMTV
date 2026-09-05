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
                            tint = Color.White,
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
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Huvudknappar
                    var saveFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { viewModel.saveLiveFavorites(selectedIds) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstFocus)
                            .onFocusChanged { saveFocused = it.isFocused },
                        shape = MaterialTheme.shapes.medium,
                        border = if (saveFocused) BorderStroke(3.dp, FocusBorderColor) else null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (saveFocused) Color.White.copy(alpha = 0.25f) else Color(0xFF2A2A2A),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Spara favoriter (${selectedIds.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    var cancelFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = viewModel::dismissTvFavoritesDialog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { cancelFocused = it.isFocused },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(if (cancelFocused) 3.dp else 1.dp, if (cancelFocused) FocusBorderColor else Color.Gray.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (cancelFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                            contentColor = Color.White
                        )
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

                    var selectAllFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = {
                            selectedIds = allChannels.map { it.id }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { selectAllFocused = it.isFocused },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(if (selectAllFocused) 3.dp else 1.dp, if (selectAllFocused) FocusBorderColor else Color.Gray.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selectAllFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                            contentColor = Color.LightGray
                        )
                    ) {
                        Text("Markera alla")
                    }

                    var clearFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { selectedIds = emptyList() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { clearFocused = it.isFocused },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(if (clearFocused) 3.dp else 1.dp, if (clearFocused) FocusBorderColor else Color.Red.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (clearFocused) Color.Red.copy(alpha = 0.2f) else Color.Transparent,
                            contentColor = Color.Red.copy(alpha = 0.9f)
                        )
                    ) {
                        Text("Avmarkera alla")
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
                        text = "Välj kanaler (${allChannels.size} kanaler):",
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
                            if (allChannels.isEmpty()) {
                                item {
                                    Text(
                                        text = "Inga kanaler hittades.",
                                        color = Color.Gray,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }

                            items(allChannels, key = { it.id }) { channel ->
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
                                    border = BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) FocusBorderColor else Color(0xFF333333)),
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
                                            tint = if (isChecked) Color.White else Color.Gray,
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
