package com.example.mmtv.ui

import com.example.mmtv.ui.theme.FocusBorderColor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.Episode
import com.example.mmtv.model.DetailMetadata
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.mergeDetailMetadata
import kotlinx.coroutines.launch

private const val TMDB_BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/w1280"
private const val SEASON_SELECTOR_ITEM_KEY = "details_season_selector"
private val SEASON_SELECTOR_TARGET_OFFSET = 64.dp

internal fun selectNavigableSeasonTarget(
    renderedSeasonKeys: List<String>,
    seasonsWithEpisodes: Set<String>,
    selectedSeason: String?
): String? = selectedSeason?.takeIf {
    it in renderedSeasonKeys && it in seasonsWithEpisodes
} ?: renderedSeasonKeys.firstOrNull { it in seasonsWithEpisodes }

internal fun shouldShowEpisodesShortcut(
    isSeries: Boolean,
    isDetailsLoading: Boolean,
    navigableSeasonTarget: String?
): Boolean = isSeries && !isDetailsLoading && navigableSeasonTarget != null

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(
    media: MediaSource,
    onPlayMovie: (MediaSource, Boolean) -> Unit,
    onPlayEpisode: (Episode, Boolean) -> Unit,
    onToggleFavorite: (MediaSource) -> Unit,
    viewModel: MediaViewModel
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sessionManager = remember { SessionManager(context) }
    val detailsListState = rememberLazyListState()
    var headerHasFocus by remember(media.id) { mutableStateOf(false) }
    LaunchedEffect(headerHasFocus) {
        // Returning from seasons/episodes must reveal the whole header, not just its buttons.
        if (headerHasFocus) detailsListState.animateScrollToItem(0)
    }
    val playFocusRequester = remember(media.id) { FocusRequester() }
    var detailsLoadStarted by remember(media.id) { mutableStateOf(false) }
    var initialPlayFocusRequested by remember(media.id) { mutableStateOf(false) }
    val isSeries = media.type == MediaType.SERIES
    val seriesInfo = viewModel.selectedSeriesInfo
    val movieInfo = viewModel.selectedMovieInfo
    val tmdbMetadata = viewModel.tmdbDetailMetadataFor(media.type, media.id)
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

    val xtreamMetadata = if (isSeries) {
        DetailMetadata(
            plot = seriesInfo?.info?.plot,
            genre = seriesInfo?.info?.genre,
            releaseDate = seriesInfo?.info?.releaseDate,
            rating = seriesInfo?.info?.rating,
            director = seriesInfo?.info?.director,
            cast = seriesInfo?.info?.cast
        )
    } else {
        DetailMetadata(
            plot = movieInfo?.info?.plot,
            genre = movieInfo?.info?.genre,
            releaseDate = movieInfo?.info?.releaseDate,
            rating = movieInfo?.info?.rating,
            director = movieInfo?.info?.director,
            cast = movieInfo?.info?.cast
        )
    }
    val currentMetadata = mergeDetailMetadata(
        tmdb = tmdbMetadata,
        xtream = xtreamMetadata,
        mediaSource = DetailMetadata(
            plot = media.plot,
            genre = media.genre,
            rating = media.rating,
            director = media.director,
            cast = media.cast
        )
    )
    val currentPlot = currentMetadata.plot
    val currentRating = currentMetadata.rating
    val currentGenre = currentMetadata.genre
    val currentDirector = currentMetadata.director
    val currentCast = currentMetadata.cast
    val currentReleaseYear = currentMetadata.releaseDate
        ?.take(4)
        ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    val detailBackdropUrl = currentMetadata.backdropPath?.takeIf { it.isNotBlank() }?.let {
        "$TMDB_BACKDROP_BASE_URL/${it.trimStart('/')}"
    }

    var showResumeDialog by remember { mutableStateOf<ResumeData?>(null) }

    // 2. Förbättrad säsongsindelning
    var selectedSeason by remember { mutableStateOf<String?>(null) }
    val seasonList = remember(seriesInfo) {
        if (seriesInfo?.seasons != null && seriesInfo.seasons.isNotEmpty()) {
            seriesInfo.seasons
                .sortedBy { it.seasonNumber }
                .map { it.seasonNumber.toString() to (it.name ?: "Säsong ${it.seasonNumber}") }
        } else {
            seriesInfo?.episodes?.keys
                ?.sortedBy { it.toIntOrNull() ?: 999 }
                ?.map { it to "Säsong $it" }
                .orEmpty()
        }
    }
    val navigableSeasonTarget = selectNavigableSeasonTarget(
        renderedSeasonKeys = seasonList.map { it.first },
        seasonsWithEpisodes = seriesInfo?.episodes.orEmpty()
            .filterValues { it.isNotEmpty() }
            .keys,
        selectedSeason = selectedSeason
    )
    val showEpisodesShortcut = shouldShowEpisodesShortcut(
        isSeries = isSeries,
        isDetailsLoading = viewModel.isDetailsLoading,
        navigableSeasonTarget = navigableSeasonTarget
    )
    val seasonListState = key(media.id) { rememberLazyListState() }
    val seasonFocusRequester = remember(media.id) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(media.type, media.id) {
        viewModel.beginTmdbDetailEnrichment(media)
        if (isSeries) {
            // Nollställ vald säsong och hämta ny info
            selectedSeason = null
            viewModel.loadSeriesInfo(media.id)
        } else if (media.type == MediaType.MOVIE) {
            viewModel.loadMovieInfo(media.id)
        }
        detailsLoadStarted = true
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

    // Keep the header visible when Play receives focus. The TV default moves
    // every focused item to 30% of the viewport, even when it is already visible.
    val detailsBringIntoViewSpec = remember { object : BringIntoViewSpec {} }
    CompositionLocalProvider(LocalBringIntoViewSpec provides detailsBringIntoViewSpec) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070A0E))
        ) {
            if (detailBackdropUrl != null) {
                AsyncImage(
                    model = detailBackdropUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.94f),
                            0.48f to Color.Black.copy(alpha = 0.76f),
                            0.78f to Color.Black.copy(alpha = 0.22f),
                            1f to Color.Black.copy(alpha = 0.08f)
                        )
                    )
                )
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.24f),
                            0.52f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
            )

            LazyColumn(
                state = detailsListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 72.dp, bottom = 72.dp, start = 64.dp, end = 48.dp)
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.58f)
                                .widthIn(max = 760.dp)
                                .heightIn(min = 440.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                            Text(
                                text = media.title ?: "Okänd titel",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 30.sp,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            val compactMetadata = listOfNotNull(
                                currentReleaseYear,
                                currentRating
                                    ?.takeIf { it.isNotBlank() && it != "0.0" }
                                    ?.let { "★ $it" },
                                currentGenre?.takeIf { it.isNotBlank() }
                            ).joinToString("  •  ")
                            if (compactMetadata.isNotEmpty()) {
                                Text(
                                    text = compactMetadata,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
                                    color = Color.White.copy(alpha = 0.68f),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Spacer(modifier = Modifier.height(18.dp))
                            }

                            Text(
                                text = if (viewModel.isDetailsLoading && currentPlot == null) "Laddar info..." else (currentPlot ?: "Ingen beskrivning tillgänglig."),
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                                color = Color.White.copy(alpha = 0.82f),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (!currentDirector.isNullOrBlank() || !currentCast.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                                ) {
                                    if (!currentDirector.isNullOrBlank()) {
                                        Column(modifier = Modifier.weight(0.35f)) {
                                            Text(
                                                text = "REGI",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    letterSpacing = 1.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                            )
                                            Text(
                                                text = currentDirector,
                                                modifier = Modifier.padding(top = 4.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White.copy(alpha = 0.82f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (!currentCast.isNullOrBlank()) {
                                        Column(modifier = Modifier.weight(0.65f)) {
                                            Text(
                                                text = "MEDVERKAN",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    letterSpacing = 1.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                            )
                                            Text(
                                                text = currentCast,
                                                modifier = Modifier.padding(top = 4.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White.copy(alpha = 0.82f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(32.dp))
                            }

                            Row(
                                modifier = Modifier.onFocusChanged { headerHasFocus = it.hasFocus },
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Request focus only once, after the play button has been composed.
                                if (detailsLoadStarted && (!isSeries || (!viewModel.isDetailsLoading && continueData != null))) {
                                    LaunchedEffect(media.id, viewModel.isTvMode) {
                                        if (viewModel.isTvMode && !initialPlayFocusRequested) {
                                            playFocusRequester.requestFocus()
                                            initialPlayFocusRequested = true
                                        }
                                    }
                                }
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
                                        modifier = Modifier.height(48.dp).focusRequester(playFocusRequester).onFocusChanged { playBtnFocus = it.isFocused },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            if (playBtnFocus) 2.dp else 1.dp,
                                            if (playBtnFocus) FocusBorderColor else Color.White.copy(alpha = 0.22f)
                                        ),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (playBtnFocus) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                                            contentColor = if (playBtnFocus) Color.White else Color.White.copy(alpha = 0.82f)
                                        )
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "SPELA FILM",
                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
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
                                        modifier = Modifier.height(48.dp).focusRequester(playFocusRequester).onFocusChanged { playBtnFocus = it.isFocused },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            if (playBtnFocus) 2.dp else 1.dp,
                                            if (playBtnFocus) FocusBorderColor else Color.White.copy(alpha = 0.22f)
                                        ),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (playBtnFocus) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                                            contentColor = if (playBtnFocus) Color.White else Color.White.copy(alpha = 0.82f)
                                        )
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            btnText,
                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (showEpisodesShortcut) {
                                    var episodesBtnFocus by remember { mutableStateOf(false) }
                                    OutlinedButton(
                                        onClick = {
                                            val targetSeason = navigableSeasonTarget
                                            val targetIndex = seasonList.indexOfFirst { it.first == targetSeason }
                                            if (targetIndex >= 0) {
                                                if (selectedSeason != targetSeason) {
                                                    selectedSeason = targetSeason
                                                }
                                                focusManager.moveFocus(FocusDirection.Down)
                                                coroutineScope.launch {
                                                    seasonListState.animateScrollToItem(targetIndex)
                                                    withFrameNanos { }
                                                    seasonFocusRequester.requestFocus()
                                                    withFrameNanos { }
                                                    val seasonItem = detailsListState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.key == SEASON_SELECTOR_ITEM_KEY }
                                                    if (seasonItem != null) {
                                                        val targetOffset = with(density) {
                                                            SEASON_SELECTOR_TARGET_OFFSET.roundToPx()
                                                        }
                                                        if (seasonItem.offset > targetOffset) {
                                                            detailsListState.animateScrollToItem(
                                                                index = seasonItem.index,
                                                                scrollOffset = targetOffset
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.height(48.dp).onFocusChanged { episodesBtnFocus = it.isFocused },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (episodesBtnFocus) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                                            contentColor = if (episodesBtnFocus) Color.White else Color.White.copy(alpha = 0.82f)
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(
                                            if (episodesBtnFocus) 2.dp else 1.dp,
                                            if (episodesBtnFocus) FocusBorderColor else Color.White.copy(alpha = 0.22f)
                                        )
                                    ) {
                                        Text(
                                            "AVSNITT",
                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Favorit-knapp
                                var favBtnFocus by remember { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { onToggleFavorite(media) },
                                    modifier = Modifier.height(48.dp).onFocusChanged { favBtnFocus = it.isFocused },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (favBtnFocus) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f),
                                        contentColor = if (favBtnFocus) Color.White else Color.White.copy(alpha = 0.82f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        if (favBtnFocus) 2.dp else 1.dp,
                                        if (favBtnFocus) FocusBorderColor else Color.White.copy(alpha = 0.22f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (media.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = if (media.isFavorite) Color.Red else if (favBtnFocus) Color.White else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (media.isFavorite) "FAVORIT" else "LÄGG TILL",
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
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
                        item(key = SEASON_SELECTOR_ITEM_KEY) {
                            Text(
                                text = "SÄSONGER",
                                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            LazyRow(
                                state = seasonListState,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 8.dp)
                            ) {
                                items(seasonList, key = { it.first + it.second }) { (seasonKey, seasonName) ->
                                    SeasonTab(
                                        title = seasonName.uppercase(),
                                        isSelected = selectedSeason == seasonKey,
                                        onClick = { selectedSeason = seasonKey },
                                        modifier = if (seasonKey == navigableSeasonTarget) {
                                            Modifier.focusRequester(seasonFocusRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // Avsnitt för vald säsong
                        val episodes = seriesInfo.episodes[selectedSeason] ?: emptyList()
                        itemsIndexed(episodes, key = { index, episode -> episode.id ?: "episode_$index" }) { index, episode ->
                            val episodeId = episode.id
                            val position = episodeId?.let(sessionManager::getPlaybackPosition) ?: 0L
                            val duration = episodeId?.let(sessionManager::getPlaybackDuration) ?: 0L
                            EpisodeItem(
                                episode = episode,
                                episodeNumber = episode.episodeNumber ?: (index + 1),
                                progress = episodeWatchProgress(position, duration, episode.info?.duration)
                            ) {
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
                    border = if (isFocused) androidx.compose.foundation.BorderStroke(3.dp, FocusBorderColor) else null,
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
                    border = if (isFocused) androidx.compose.foundation.BorderStroke(3.dp, FocusBorderColor) else null,
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
fun SeasonTab(title: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable { onClick() },
        border = if (hasFocus) androidx.compose.foundation.BorderStroke(3.dp, FocusBorderColor) else null,
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
fun EpisodeItem(episode: Episode, episodeNumber: Int, progress: Float? = null, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .clickable { onClick() },
        border = if (hasFocus) androidx.compose.foundation.BorderStroke(3.dp, FocusBorderColor) else null,
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
                    text = "Avsnitt $episodeNumber · ${episode.title.orEmpty()}",
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
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.2f)
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
