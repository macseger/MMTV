package com.example.mmtv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import com.example.mmtv.ui.theme.FocusBorderColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mmtv.model.MediaType

@Composable
fun SyncCategoryDialog(viewModel: MediaViewModel) {
    var type by remember { mutableStateOf(MediaType.LIVE) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(MediaType.entries.associateWith { viewModel.selectedSyncCategories(it) }) }
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyGridState()
    LaunchedEffect(type, query) { listState.scrollToItem(0) }
    val options = remember(viewModel.syncCategoryOptions, type, query) {
        viewModel.syncCategoryOptions[type].orEmpty().filter {
            !it.categoryId.isNullOrBlank() && (query.isBlank() || it.title.orEmpty().contains(query, ignoreCase = true))
        }
    }
    val ready = !viewModel.isLoadingSyncCategories && viewModel.syncSelectionError == null
    Dialog(onDismissRequest = viewModel::dismissSyncSelection, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Välj vad som ska synkas", style = MaterialTheme.typography.titleLarge)
                    Text("${options.size} kategorier · ${selection.values.sumOf { it.size }} valda", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaType.entries.forEach { tab ->
                        var tabFocused by remember { mutableStateOf(false) }
                        FilterChip(
                            selected = tab == type,
                            onClick = { type = tab; query = "" },
                            modifier = (if (tab == MediaType.LIVE) Modifier.focusRequester(firstFocus) else Modifier)
                                .onFocusChanged { tabFocused = it.isFocused },
                            border = if (tabFocused) BorderStroke(3.dp, FocusBorderColor) else null,
                            label = { Text(when (tab) { MediaType.LIVE -> "TV"; MediaType.MOVIE -> "Filmer"; MediaType.SERIES -> "Serier" } + " (${selection[tab].orEmpty().size})") }
                        )
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Sök kategori") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (viewModel.isLoadingSyncCategories) LinearProgressIndicator(Modifier.fillMaxWidth())
                viewModel.syncSelectionError?.let { message ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::openSyncSelection) { Text("Försök igen") }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    if (ready && options.isEmpty()) item { Text("Inga kategorier matchar sökningen.") }
                    items(options, key = { it.categoryId!! }) { category ->
                        val id = category.categoryId!!
                        val checked = id in selection[type].orEmpty()
                        var focused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = {
                                val current = selection[type].orEmpty()
                                selection = selection + (type to if (checked) current - id else current + id)
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                .onFocusChanged { focused = it.isFocused },
                            shape = MaterialTheme.shapes.small,
                            border = if (focused) BorderStroke(3.dp, FocusBorderColor) else null,
                            color = if (focused) Color.White.copy(alpha = 0.15f) else Color.Transparent
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (checked) androidx.compose.material.icons.Icons.Default.CheckBox else androidx.compose.material.icons.Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = if (checked) "Vald" else "Inte vald"
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(category.title ?: id, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    var saveFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        enabled = ready && selection.values.any { it.isNotEmpty() },
                        onClick = { viewModel.saveSyncSelection(selection) },
                        modifier = Modifier.onFocusChanged { saveFocused = it.isFocused },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (saveFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                            contentColor = Color.White
                        ),
                        border = BorderStroke(if (saveFocused) 3.dp else 1.dp, if (saveFocused) FocusBorderColor else Color.White.copy(alpha = 0.3f))
                    ) { Text("Spara och synka") }
                    if (!viewModel.requiresSyncSelection) OutlinedButton(onClick = viewModel::dismissSyncSelection) { Text("Avbryt") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(enabled = ready, onClick = {
                        // Select the entire active section, including categories outside the search.
                        selection = selection + (type to viewModel.syncCategoryOptions[type].orEmpty()
                            .mapNotNull { it.categoryId }.toSet())
                    }) { Text("Markera alla") }
                    OutlinedButton(enabled = ready && options.isNotEmpty(), onClick = {
                        selection = selection + (type to (selection[type].orEmpty() + options.mapNotNull { it.categoryId }))
                    }) { Text("Markera visade") }
                    OutlinedButton(enabled = ready, onClick = {
                        selection = selection + (type to (selection[type].orEmpty() - options.mapNotNull { it.categoryId }.toSet()))
                    }) { Text("Avmarkera visade") }
                }
            }
        }
        LaunchedEffect(Unit) { if (viewModel.isTvMode) firstFocus.requestFocus() }
    }
}
