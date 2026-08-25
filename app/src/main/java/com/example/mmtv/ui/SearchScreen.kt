package com.example.mmtv.ui

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
import androidx.compose.material.icons.filled.Favorite
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
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (viewModel.isTvMode) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        // Search Input Area
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 600.dp) // Gör sökfältet mindre
                .padding(bottom = 24.dp)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

            BasicTextField(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = {
                        keyboardController?.hide()
                    }
                ),
                cursorBrush = Brush.verticalGradient(listOf(Color.White, Color.White)),
                decorationBox = { innerTextField ->
                    if (viewModel.searchQuery.isEmpty()) {
                        Text(
                            "Skriv för att söka...",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                }
            )
        }

        // Search Results
        if (viewModel.searchQuery.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp), // Lite mindre kort
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "SÖKRESULTAT (${dbSearchResults.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(dbSearchResults) { media ->
                    SearchMediaCard(
                        media = media,
                        viewModel = viewModel,
                        onClick = { onMediaSelected(media) }
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Sök efter filmer, serier eller kanaler",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
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

    Column(
        modifier = Modifier
            .width(100.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .scale(if (hasFocus) 1.05f else 1.0f)
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
                .aspectRatio(if (isLive) 1.2f else 0.67f) // Picons är ofta bredare, covers högre
                .border(
                    width = if (hasFocus) 2.dp else 0.dp,
                    color = if (hasFocus) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(6.dp)
                ),
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = displayIcon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(if (isLive) 8.dp else 0.dp),
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
        
        Spacer(modifier = Modifier.height(6.dp))
        
        Text(
            text = media.title ?: "",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (hasFocus) FontWeight.Bold else FontWeight.Normal,
                fontSize = 10.sp
            ),
            color = if (hasFocus) Color.White else Color.LightGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        
        // Kategori - Smart lösning: visa i grått under titeln
        if (!media.categoryName.isNullOrBlank()) {
            Text(
                text = media.categoryName.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
