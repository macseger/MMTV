package com.example.mmtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.Episode
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import kotlinx.coroutines.launch

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
    val movieInfo = viewModel.selectedMovieInfo
    val lastWatchedEpId = remember(media.id) { sessionManager.getLastEpisodeId(media.id) }

    val continueData = remember(seriesInfo, lastWatchedEpId) {
        if (!isSeries || seriesInfo?.episodes == null || seriesInfo.episodes.isEmpty()) return@remember null
        
        var foundEp: Episode? = null
        var foundSeason: String? = null
        var foundIndex: Int = -1
        
        if (lastWatchedEpId != null) {
            for ((sKey, episodes) in seriesInfo.episodes) {
                val idx = episodes.indexOfFirst { it.id == lastWatchedEpId }
                if (idx != -1) {
                    foundEp = episodes[idx]
                    foundSeason = sKey
                    foundIndex = idx + 1
                    break
                }
            }
        }
        
        if (foundEp == null) {
            val sortedSeasons = seriesInfo.episodes.keys.sortedBy { it.toIntOrNull() ?: 999 }
            val firstSeasonKey = sortedSeasons.firstOrNull()
            if (firstSeasonKey != null) {
                val firstEp = seriesInfo.episodes[firstSeasonKey]?.firstOrNull()
                if (firstEp != null) {
                    foundEp = firstEp
                    foundSeason = firstSeasonKey
                    foundIndex = 1
                }
            }
        }
        
        if (foundEp != null) {
            Triple(foundEp, foundSeason, foundIndex)
        } else null
    }

    val currentPlot = if (isSeries) (seriesInfo?.info?.plot ?: media.plot) else (movieInfo?.info?.plot ?: media.plot)
    val currentRating = if (isSeries) (seriesInfo?.info?.rating ?: media.rating) else (movieInfo?.info?.rating ?: media.rating)
    val currentGenre = if (isSeries) (seriesInfo?.info?.genre ?: media.genre) else (movieInfo?.info?.genre ?: media.genre)
    val currentDirector = if (isSeries) (seriesInfo?.info?.director ?: media.director) else (movieInfo?.info?.director ?: media.director)
    val currentCast = if (isSeries) (seriesInfo?.info?.cast ?: media.cast) else (movieInfo?.info?.cast ?: media.cast)
    
    var showResumeDialog by remember { mutableStateOf<ResumeData?>(null) }
    
    // 2. Förbättrad säsongsindelning
    var selectedSeason by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(media.id) {
        if (isSeries) {
            // Nollställ vald säsong och hämta ny info
            selectedSeason = null 
            viewModel.loadSeriesInfo(media.id)
        } else if (media.type == MediaType.MOVIE) {
            viewModel.loadMovieInfo(media.id)
        }
    }
    
    // Sätt första säsongen som vald när data laddats
    LaunchedEffect(seriesInfo) {
        if (selectedSeason == null && seriesInfo?.episodes != null && seriesInfo.episodes.isNotEmpty()) {
            val firstSeason = seriesInfo.episodes.keys.sortedBy { it.toIntOrNull() ?: 999 }.firstOrNull()
            if (firstSeason != null) {
                selectedSeason = firstSeason
            }
        }
    }
    
    // NYTT: Hantera bakåtnavigering från spelaren
    // Om vi kommer tillbaka och seriesInfo redan finns, se till att selectedSeason är satt
    LaunchedEffect(seriesInfo) {
        if (isSeries && seriesInfo != null && selectedSeason == null) {
            val keys = seriesInfo.episodes?.keys?.sortedBy { it.toIntOrNull() ?: 999 }
            if (!keys.isNullOrEmpty()) {
                selectedSeason = keys.first()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val bgIcon by produceState<String?>(initialValue = media.icon, key1 = media) {
            if (value.isNullOrEmpty()) {
                value = viewModel.getIconForChannel(media.id, media.type, media.title)
            }
        }
        
        AsyncImage(
            model = bgIcon,
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
            contentPadding = PaddingValues(top = 48.dp, bottom = 80.dp, start = 48.dp, end = 48.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    // Ytterligare minskning av omslaget för att ge mer plats åt info och listor
                    Card(
                        modifier = Modifier.width(180.dp).aspectRatio(0.67f),
                        elevation = CardDefaults.cardElevation(16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = bgIcon,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            if (bgIcon == null) {
                                ChannelPlaceholder(media.title ?: "?", Modifier.fillMaxSize(), isMovie = !isSeries)
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.title ?: "Okänd titel",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 36.sp,
                                letterSpacing = (-0.5).sp
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (!currentRating.isNullOrEmpty() && currentRating != "0.0") {
                                Surface(
                                    color = Color(0xFFFFD700), 
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = " ★ $currentRating ",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = Color.Black,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = currentGenre ?: "VOD", 
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
                            )
                        }

                        Text(
                            text = if (viewModel.isDetailsLoading && currentPlot == null) "Laddar info..." else (currentPlot ?: "Ingen beskrivning tillgänglig."),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!currentDirector.isNullOrEmpty() || !currentCast.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            if (!currentDirector.isNullOrEmpty()) {
                                Text(
                                    text = "Regissör: $currentDirector",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            if (!currentCast.isNullOrEmpty()) {
                                Text(
                                    text = "Skådespelare: $currentCast",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

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
                                    modifier = Modifier.height(52.dp).width(200.dp).onFocusChanged { playBtnFocus = it.isFocused },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (playBtnFocus) Color.White else MaterialTheme.colorScheme.primary,
                                        contentColor = if (playBtnFocus) Color.Black else Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("SPELA FILM", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            } else if (continueData != null) {
                                val (ep, sNum, eNum) = continueData
                                val btnText = if (lastWatchedEpId == null) "SPELA AVSNITT 1" 
                                              else "FORTSÄTT (S$sNum A$eNum)"
                                
                                var playBtnFocus by remember { mutableStateOf(false) }
                                Button(
                                    onClick = { 
                                        val pos = sessionManager.getPlaybackPosition(ep.id ?: "0")
                                        if (pos > 10000) {
                                            showResumeDialog = ResumeData(null, ep, pos)
                                        } else {
                                            onPlayEpisode(ep, false)
                                        }
                                    },
                                    modifier = Modifier.height(52.dp).onFocusChanged { playBtnFocus = it.isFocused },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (playBtnFocus) Color.White else MaterialTheme.colorScheme.primary,
                                        contentColor = if (playBtnFocus) Color.Black else Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(btnText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }

                            // Favorit-knapp
                            var favBtnFocus by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { onToggleFavorite(media) },
                                modifier = Modifier.height(52.dp).onFocusChanged { favBtnFocus = it.isFocused },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (favBtnFocus) Color.White.copy(alpha = 0.1f) else Color.Transparent,
                                    contentColor = if (favBtnFocus) Color.White else Color.Gray
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    2.dp, 
                                    if (favBtnFocus) Color.White else Color.White.copy(alpha = 0.2f)
                                )
                            ) {
                                Icon(
                                    imageVector = if (media.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (media.isFavorite) Color.Red else if (favBtnFocus) Color.White else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (media.isFavorite) "FAVORIT" else "LÄGG TILL", 
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }

            if (isSeries) {
                item { Spacer(modifier = Modifier.height(48.dp)) }
                
                if (viewModel.isDetailsLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (seriesInfo?.episodes != null) {
                    // Säsongsväljare (LazyRow)
                    item {
                        Text(
                            text = "SÄSONGER",
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            // Vi försöker använda 'seasons' listan för ordning och namn, annars 'episodes' nycklar
                            val seasonList = if (seriesInfo.seasons != null && seriesInfo.seasons.isNotEmpty()) {
                                seriesInfo.seasons
                                    .sortedBy { it.seasonNumber }
                                    .map { it.seasonNumber.toString() to (it.name ?: "Säsong ${it.seasonNumber}") }
                            } else {
                                seriesInfo.episodes.keys
                                    .sortedBy { it.toIntOrNull() ?: 999 }
                                    .map { it to "Säsong $it" }
                            }

                            items(seasonList, key = { it.first + it.second }) { (seasonKey, seasonName) ->
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
            if (viewModel.isTvMode) {
                continueFocusRequester.requestFocus()
            }
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
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = episode.info?.icon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
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
