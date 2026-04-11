package com.example.mmtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.Episode
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType

@Composable
fun DetailsScreen(
    media: MediaSource,
    onPlayMovie: (MediaSource, Boolean) -> Unit,
    onPlayEpisode: (Episode, Boolean) -> Unit,
    onToggleFavorite: (MediaSource) -> Unit,
    viewModel: MediaViewModel
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val isSeries = media.type == MediaType.SERIES
    val seriesInfo = viewModel.selectedSeriesInfo
    
    var showResumeDialog by remember { mutableStateOf<ResumeData?>(null) }
    
    // 2. Förbättrad säsongsindelning
    var selectedSeason by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(media.id) {
        if (isSeries) {
            viewModel.loadSeriesInfo(media.id)
        }
    }
    
    // Sätt första säsongen som vald när data laddats
    LaunchedEffect(seriesInfo) {
        if (selectedSeason == null && seriesInfo?.episodes?.isNotEmpty() == true) {
            // Sortera numeriskt för att hitta första säsongen korrekt
            selectedSeason = seriesInfo.episodes.keys.sortedBy { it.toIntOrNull() ?: 999 }.firstOrNull()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AsyncImage(
            model = media.icon,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(0.2f),
            contentScale = ContentScale.Crop
        )
        
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                startY = 0f
            )
        ))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(48.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Card(
                        modifier = Modifier.width(260.dp).aspectRatio(0.7f),
                        elevation = CardDefaults.cardElevation(16.dp)
                    ) {
                        AsyncImage(
                            model = media.icon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.title ?: "Okänd titel",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 42.sp
                            ),
                            color = Color.White
                        )
                        
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (!media.rating.isNullOrEmpty()) {
                                Surface(color = Color(0xFFFFD700), shape = MaterialTheme.shapes.small) {
                                    Text(
                                        text = " ★ ${media.rating} ",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.Black,
                                        modifier = Modifier.padding(4.dp)
                                    )
                                }
                            }
                            Text(text = media.genre ?: "VOD", color = MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = media.plot ?: "Ingen beskrivning tillgänglig.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 6
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (!isSeries) {
                                var playBtnFocus by remember { mutableStateOf(false) }
                                Button(
                                    onClick = {
                                        val pos = sessionManager.getPlaybackPosition(media.id.toString())
                                        if (pos > 10000) {
                                            showResumeDialog = ResumeData(media, null, pos)
                                        } else {
                                            onPlayMovie(media, false)
                                        }
                                    },
                                    modifier = Modifier.height(56.dp).width(200.dp).onFocusChanged { playBtnFocus = it.isFocused },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (playBtnFocus) Color.White else MaterialTheme.colorScheme.primary,
                                        contentColor = if (playBtnFocus) Color.Black else Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("SPELA FILM", style = MaterialTheme.typography.titleMedium)
                                }
                            }

                            // Favorit-knapp
                            var favBtnFocus by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { onToggleFavorite(media) },
                                modifier = Modifier.height(56.dp).onFocusChanged { favBtnFocus = it.isFocused },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (favBtnFocus) Color.White.copy(alpha = 0.1f) else Color.Transparent,
                                    contentColor = if (favBtnFocus) Color.White else Color.Gray
                                ),
                                border = if (favBtnFocus) ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(Color.White, Color.White))) else ButtonDefaults.outlinedButtonBorder
                            ) {
                                Icon(
                                    imageVector = if (media.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (media.isFavorite) Color.Red else if (favBtnFocus) Color.White else Color.Gray
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (media.isFavorite) "FAVORIT" else "LÄGG TILL", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }

            if (isSeries) {
                item { Spacer(modifier = Modifier.height(32.dp)) }
                
                if (viewModel.isDetailsLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (seriesInfo?.episodes != null) {
                    // Säsongsväljare (LazyRow)
                    item {
                        Text(
                            text = "SÄSONGER",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Vi försöker använda 'seasons' listan för ordning och namn, annars 'episodes' nycklar
                            val seasonList = if (seriesInfo.seasons != null && seriesInfo.seasons.isNotEmpty()) {
                                seriesInfo.seasons.sortedBy { it.seasonNumber }.map { it.seasonNumber.toString() to (it.name ?: "Säsong ${it.seasonNumber}") }
                            } else {
                                seriesInfo.episodes.keys.sortedBy { it.toIntOrNull() ?: 999 }.map { it to "Säsong $it" }
                            }

                            items(seasonList, key = { it.first }) { (seasonKey, seasonName) ->
                                SeasonTab(
                                    title = seasonName.uppercase(),
                                    isSelected = selectedSeason == seasonKey,
                                    onClick = { selectedSeason = seasonKey }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                    
                    // Avsnitt för vald säsong
                    val episodes = seriesInfo.episodes[selectedSeason] ?: emptyList()
                    items(episodes, key = { it.id ?: "" }) { episode ->
                        EpisodeItem(episode) {
                            val pos = sessionManager.getPlaybackPosition(episode.id ?: "0")
                            if (pos > 10000) {
                                showResumeDialog = ResumeData(null, episode, pos)
                            } else {
                                onPlayEpisode(episode, false)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResumeDialog != null) {
        val continueFocusRequester = remember { FocusRequester() }
        
        AlertDialog(
            onDismissRequest = { showResumeDialog = null },
            title = { Text("Fortsätt titta?") },
            text = { Text("Vill du fortsätta där du slutade eller börja från början?") },
            confirmButton = {
                var isFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        val data = showResumeDialog!!
                        if (data.movie != null) onPlayMovie(data.movie, true)
                        else if (data.episode != null) onPlayEpisode(data.episode, true)
                        showResumeDialog = null
                    },
                    modifier = Modifier
                        .focusRequester(continueFocusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                ) { Text("Fortsätt", color = Color.White) }
            },
            dismissButton = {
                var isFocused by remember { mutableStateOf(false) }
                TextButton(
                    onClick = {
                        val data = showResumeDialog!!
                        if (data.movie != null) onPlayMovie(data.movie, false)
                        else if (data.episode != null) onPlayEpisode(data.episode, false)
                        showResumeDialog = null
                    },
                    modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                ) { Text("Börja om") }
            }
        )
        
        LaunchedEffect(Unit) {
            continueFocusRequester.requestFocus()
        }
    }
}

@Composable
fun SeasonTab(title: String, isSelected: Boolean, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable { onClick() },
        color = if (hasFocus) Color.White 
                else if (isSelected) MaterialTheme.colorScheme.primary 
                else Color.DarkGray.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (hasFocus) Color.Black else Color.White
        )
    }
}

data class ResumeData(
    val movie: MediaSource?,
    val episode: Episode?,
    val position: Long
)

@Composable
fun EpisodeItem(episode: Episode, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable { onClick() },
        color = if (hasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
        shape = MaterialTheme.shapes.small
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (hasFocus) Color.White else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            
            // Avsnittsbild (om den finns)
            if (!episode.info?.icon.isNullOrEmpty()) {
                Card(
                    modifier = Modifier.size(width = 120.dp, height = 68.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    AsyncImage(
                        model = episode.info?.icon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title ?: "Avsnitt",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
                if (!episode.info?.plot.isNullOrEmpty()) {
                    Text(
                        text = episode.info?.plot ?: "",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            
            if (!episode.info?.duration.isNullOrEmpty()) {
                Text(
                    text = episode.info?.duration ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
    }
}
