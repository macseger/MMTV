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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mmtv.model.MediaType
import com.example.mmtv.ui.theme.FocusBorderColor

@Composable
fun SyncCategoryDialog(viewModel: MediaViewModel) {
    var type by remember { mutableStateOf(MediaType.LIVE) }
    var selection by remember { mutableStateOf(MediaType.entries.associateWith { viewModel.selectedSyncCategories(it) }) }

    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyGridState()

    LaunchedEffect(type) {
        listState.scrollToItem(0)
    }

    val options = remember(viewModel.syncCategoryOptions, type) {
        viewModel.syncCategoryOptions[type].orEmpty().filter {
            !it.categoryId.isNullOrBlank()
        }
    }

    val ready = !viewModel.isLoadingSyncCategories && viewModel.syncSelectionError == null

    Dialog(
        onDismissRequest = viewModel::dismissSyncSelection,
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
                // VÄNSTER PANEL: Sektioner, Kontroller & Huvudknappar
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
                            imageVector = Icons.Default.Checklist,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Välj kategorier",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }

                    Text(
                        text = "${options.size} kategorier · ${selection.values.sumOf { it.size }} valda totalt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )

                    // Flikar för medietyper (TV, Filmer, Serier)
                    Text(
                        text = "Välj sektion:",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MediaType.entries.forEach { tab ->
                            var tabFocused by remember { mutableStateOf(false) }
                            val isSelected = tab == type
                            val count = selection[tab].orEmpty().size

                            FilterChip(
                                selected = isSelected,
                                onClick = { type = tab },
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { tabFocused = it.isFocused },
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = if (tabFocused) FocusBorderColor else Color.Gray.copy(alpha = 0.4f),
                                    borderWidth = if (tabFocused) 3.dp else 1.dp
                                ),
                                label = {
                                    Text(
                                        text = when (tab) {
                                            MediaType.LIVE -> "TV"
                                            MediaType.MOVIE -> "Film"
                                            MediaType.SERIES -> "Serie"
                                        } + " ($count)",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Huvudknappar
                    var saveFocused by remember { mutableStateOf(false) }
                    Button(
                        enabled = ready && selection.values.any { it.isNotEmpty() },
                        onClick = { viewModel.saveSyncSelection(selection) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstFocus)
                            .onFocusChanged { saveFocused = it.isFocused },
                        shape = MaterialTheme.shapes.medium,
                        border = if (saveFocused) BorderStroke(3.dp, FocusBorderColor) else null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (saveFocused) Color.White.copy(alpha = 0.25f) else Color(0xFF2A2A2A),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF1E1E1E),
                            disabledContentColor = Color.Gray
                        )
                    ) {
                        Text("Spara och synka", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    if (!viewModel.requiresSyncSelection) {
                        var cancelFocused by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = viewModel::dismissSyncSelection,
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
                    }

                    HorizontalDivider(
                        color = Color.Gray.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Text(
                        text = "Snabbval för ${when(type) { MediaType.LIVE -> "TV"; MediaType.MOVIE -> "Film"; MediaType.SERIES -> "Serie" }}:",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )

                    var selectAllFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        enabled = ready,
                        onClick = {
                            val allTypeIds = viewModel.syncCategoryOptions[type].orEmpty().mapNotNull { it.categoryId }.toSet()
                            selection = selection + (type to allTypeIds)
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
                        Text("Markera alla i sektion")
                    }

                    var clearFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        enabled = ready,
                        onClick = {
                            selection = selection + (type to emptySet())
                        },
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
                        Text("Rensa sektion")
                    }
                }

                // HÖGER PANEL: Kategorinät
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Välj kategorier för ${when (type) { MediaType.LIVE -> "TV-Kanaler"; MediaType.MOVIE -> "Filmer"; MediaType.SERIES -> "Serier" }} (${options.size} finns):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )

                    if (viewModel.isLoadingSyncCategories) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Text("Hämtar kategorier från servern...", color = Color.Gray)
                            }
                        }
                    } else if (viewModel.syncSelectionError != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = viewModel.syncSelectionError ?: "Kunde inte hämta kategorier.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(onClick = viewModel::openSyncSelection) {
                                    Text("Försök igen")
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 300.dp),
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            if (ready && options.isEmpty()) {
                                item {
                                    Text(
                                        text = "Inga kategorier hittades.",
                                        color = Color.Gray,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }

                            items(options, key = { it.categoryId!! }) { category ->
                                val id = category.categoryId!!
                                val isChecked = id in selection[type].orEmpty()
                                var isFocused by remember { mutableStateOf(false) }

                                Surface(
                                    onClick = {
                                        val current = selection[type].orEmpty()
                                        selection = selection + (type to if (isChecked) current - id else current + id)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
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
                                            contentDescription = if (isChecked) "Vald" else "Inte vald",
                                            tint = if (isChecked) Color.White else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = category.title ?: id,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White,
                                            maxLines = 2,
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

        LaunchedEffect(Unit) {
            if (viewModel.isTvMode) {
                firstFocus.requestFocus()
            }
        }
    }
}
