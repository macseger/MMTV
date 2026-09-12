package com.example.mmtv.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.*
import com.example.mmtv.repository.MediaRepository
import com.example.mmtv.util.StartupDiagnostics
import com.example.mmtv.util.OverlayDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

import com.example.mmtv.database.MediaDatabase
import com.example.mmtv.database.MediaEntity
import com.example.mmtv.player.MmtvPlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import com.example.mmtv.ui.theme.AccentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class MediaUiState(
    val liveCategories: List<GroupedMedia> = emptyList(),
    val movieCategories: List<GroupedMedia> = emptyList(),
    val seriesCategories: List<GroupedMedia> = emptyList(),
    val ppvCategories: List<GroupedMedia> = emptyList(),
    val isLoading: Boolean = true,
    val history: List<MediaSource> = emptyList()
) {
    val liveStreamsGrouped get() = liveCategories
    val movies get() = movieCategories
    val series get() = seriesCategories
}
enum class FavoriteTimelineStatus {
    IDLE,
    LOADING,
    READY,
    EMPTY,
    ERROR
}

data class FavoriteTimelineCacheKey(
    val orderedFavoriteChannelIds: List<Int>,
    val windowStartTimestamp: Long,
    val windowEndTimestamp: Long,
    val epgRevision: Long
)

data class FavoriteTimelineRow(
    val channel: MediaSource,
    val epgId: String,
    val programs: List<EpgListing>
)

data class FavoriteTimelineState(
    val status: FavoriteTimelineStatus = FavoriteTimelineStatus.IDLE,
    val rows: List<FavoriteTimelineRow> = emptyList(),
    val windowStartTimestamp: Long = 0L,
    val windowEndTimestamp: Long = 0L,
    val epgRevision: Long = 0L,
    val cacheKey: FavoriteTimelineCacheKey? = null,
    val errorMessage: String? = null
)

class MediaViewModel(
    private var _repository: MediaRepository, 
    private val sessionManager: SessionManager, 
    private val database: MediaDatabase,
    private val context: android.content.Context
) : ViewModel() {

    private companion object {
        const val SEARCH_RESULTS_PER_TYPE = 100
        const val FAVORITE_TIMELINE_BUCKET_SECONDS = 30 * 60L
        const val FAVORITE_TIMELINE_WINDOW_SECONDS = 4 * 60 * 60L
    }

    private val playerFactory = MmtvPlayer(context)
    var exoPlayer: ExoPlayer? = null
        private set
    private var playerUsesLiveProfile: Boolean? = null

    var isInPipMode by mutableStateOf(false)
    var isTvMode by mutableStateOf(sessionManager.getTvMode())

    fun toggleTvMode(enabled: Boolean) {
        isTvMode = enabled
        sessionManager.setTvMode(enabled)
    }

    fun getOrInitializePlayer(isLive: Boolean): ExoPlayer {
        if (exoPlayer == null || playerUsesLiveProfile != isLive) {
            // LoadControl kan inte ändras i efterhand. Byt därför bara spelare när
            // användaren går mellan live-TV och VOD, aldrig vid vanligt kanalbyte.
            exoPlayer?.release()
            exoPlayer = playerFactory.createPlayer(isLive).apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
            playerUsesLiveProfile = isLive
        }
        return exoPlayer!!
    }

    fun enableVodStereoDownmix(inputChannelCount: Int): Boolean {
        return playerFactory.enableVodStereoDownmix(inputChannelCount)
    }

    fun disableVodStereoDownmix() {
        playerFactory.disableVodStereoDownmix()
    }

    fun stopAndResetPlayer() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer?.release()
        exoPlayer = null
        playerUsesLiveProfile = null
    }

    val repository: MediaRepository get() = _repository

    var uiState by mutableStateOf(MediaUiState(history = sessionManager.getHistory().filter { it.categoryId in sessionManager.getSyncCategories(it.type) }))
        private set

    var syncCategoryOptions by mutableStateOf<Map<MediaType, List<GroupedMedia>>>(emptyMap())
        private set
    var showSyncSelection by mutableStateOf(false)
        private set
    var isLoadingSyncCategories by mutableStateOf(false)
        private set
    var showTvFavoritesDialog by mutableStateOf(false)
        private set

    fun openTvFavoritesDialog() {
        showTvFavoritesDialog = true
    }

    fun dismissTvFavoritesDialog() {
        showTvFavoritesDialog = false
    }

    suspend fun getAllLiveChannelsForFavorites(): List<MediaSource> = withContext(Dispatchers.IO) {
        mediaDao.getMediaByType(MediaType.LIVE)
            .filter { it.categoryId in sessionManager.getSyncCategories(MediaType.LIVE) }
            .map { it.toMediaSource() }
    }

    fun saveLiveFavorites(orderedIds: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            mediaDao.updateLiveFavorites(orderedIds)
            val updatedFavs = mediaDao.getFavorites()
                .filter { it.isFavorite || it.categoryId in sessionManager.getSyncCategories(it.type) }
                .map { it.toMediaSource() }

            withContext(Dispatchers.Main) {
                updateFavorites(updatedFavs)
                showTvFavoritesDialog = false

                val selectedSet = orderedIds.toSet()
                val favsForLive = updatedFavs.filter { it.type == MediaType.LIVE }.sortedBy { it.favoriteDate }
                favsForLive.forEach { item ->
                    val epgId = item.epgId?.takeIf { it.isNotBlank() } ?: "stream:${item.id}"
                    channelToEpgMap[item.id] = epgId
                }

                uiState = uiState.copy(
                    liveCategories = uiState.liveCategories.map { group ->
                        if (group.categoryId == "FAVORITES") {
                            group.copy(items = favsForLive)
                        } else {
                            group.copy(items = group.items.map { item ->
                                item.copy(isFavorite = item.id in selectedSet)
                            })
                        }
                    }
                )

                val favIndex = uiState.liveCategories.indexOfFirst { it.categoryId == "FAVORITES" }
                if (favIndex >= 0) {
                    preparedEpgCategoryIds.remove("FAVORITES")
                    prefetchEpgForCategory(favIndex)
                }

                showStatusMessage("Favoritlistan för TV har uppdaterats")
            }
        }
    }

    var startupError by mutableStateOf<String?>(null)
        private set
    var syncSelectionError by mutableStateOf<String?>(null)
        private set
    val requiresSyncSelection get() = !sessionManager.hasSyncSelection()
    fun selectedSyncCategories(type: MediaType) = sessionManager.getSyncCategories(type)

    fun openSyncSelection() {
        if (isFetching || isUpdatingBackground || isLoadingSyncCategories) return
        showSyncSelection = true
        isLoadingSyncCategories = true
        syncSelectionError = null
        viewModelScope.launch {
            try {
                val login = sessionManager.getLogin() ?: return@launch
                syncCategoryOptions = loadSyncCategoryOptions(login.second, login.third, true)
            } catch (e: Exception) {
                syncSelectionError = "Kunde inte hämta kategorier. Försök igen."
            } finally { isLoadingSyncCategories = false }
        }
    }

    private suspend fun loadSyncCategoryOptions(user: String, pass: String, forceRefresh: Boolean): Map<MediaType, List<GroupedMedia>> =
        loadCategoryCatalog { type -> _repository.getJustCategories(type, user, pass, forceRefresh, true) }

    fun dismissStartupError() { startupError = null }

    fun dismissSyncSelection() { if (!requiresSyncSelection) showSyncSelection = false }

    fun saveSyncSelection(selection: Map<MediaType, Set<String>>) {
        if (isLoadingSyncCategories || syncSelectionError != null || selection.values.all { it.isEmpty() }) return
        val availableSelection = MediaType.entries.associateWith { type ->
            selection[type].orEmpty().intersect(syncCategoryOptions[type].orEmpty().mapNotNull { it.categoryId }.toSet())
        }
        if (availableSelection.values.all { it.isEmpty() }) return
        sessionManager.saveSyncSelection(availableSelection)
        showSyncSelection = false
        updateFavorites(emptyList())
        _recentlyAdded.value = emptyList()
        _dbSearchResults.value = emptyList()
        lastLiveCategoryIndex = 0
        lastPpvCategoryIndex = 0
        lastMovieCategoryIndex = 0
        lastSeriesCategoryIndex = 0
        uiState = MediaUiState(history = sessionManager.getHistory().filter { it.categoryId in sessionManager.getSyncCategories(it.type) })
        refreshDataManually()
    }

    var loginError by mutableStateOf<String?>(null)
        private set

    private var isFetching = false
    private var lastLoadedSeriesId: Int? = null

    // Caches för Compose-reaktivitet utan suspending-overhead i UI-loopen
    val fullEpgData = mutableStateMapOf<String, List<EpgListing>>()
    private val fetchingEpgIds = ConcurrentHashMap.newKeySet<Int>()
    private val fetchingFullEpgIds = ConcurrentHashMap.newKeySet<String>()
    private val prefetchingCategoryIds = ConcurrentHashMap.newKeySet<String>()
    private val preparedEpgCategoryIds = ConcurrentHashMap.newKeySet<String>()
    private val loadedFullEpgIds = ConcurrentHashMap.newKeySet<String>()
    private val categoryLoadJobs = mutableMapOf<MediaType, Job>()
    
    // Cache för nuvarande program och ikoner
    private val currentEpgCache = mutableMapOf<String, EpgListing?>()
    private val piconCache = mutableMapOf<String, String?>()
    val channelToEpgMap = mutableStateMapOf<Int, String>()

    var lastLiveCategoryIndex by mutableIntStateOf(0)
    var lastPpvCategoryIndex by mutableIntStateOf(0)
    var lastMovieCategoryIndex by mutableIntStateOf(0)
    var lastSeriesCategoryIndex by mutableIntStateOf(0)

    var selectedMedia by mutableStateOf<MediaSource?>(null)
    var playingEpisode by mutableStateOf<Episode?>(null)
    var currentPlaylist by mutableStateOf<List<MediaSource>>(emptyList())

    var selectedSeriesInfo by mutableStateOf<SeriesInfoResponse?>(null)
    var selectedMovieInfo by mutableStateOf<MovieInfoResponse?>(null)
    var isDetailsLoading by mutableStateOf(false)

    var searchQuery by mutableStateOf("")

    var currentThemeColor by mutableStateOf(AccentColor)
        private set
    private var themeColorJob: kotlinx.coroutines.Job? = null

    fun updateThemeColorFromIcon(iconUrl: String?) {
        themeColorJob?.cancel()
        // Use a readable turquoise until a valid picon supplies its own color.
        currentThemeColor = AccentColor
        if (iconUrl.isNullOrBlank()) return

        themeColorJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val connection = URL(iconUrl).openConnection()
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val bitmap = connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                    ?: return@launch
                try {
                    val palette = Palette.from(bitmap).generate()
                    val colorInt = palette.getVibrantColor(palette.getDominantColor(AccentColor.toArgb()))
                    withContext(Dispatchers.Main) {
                        currentThemeColor = Color(colorInt)
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Missing/unreadable picons keep the turquoise fallback.
            }
        }
    }

    var updateStatus by mutableStateOf<String?>(null)
        private set

    private var updateStatusJob: kotlinx.coroutines.Job? = null

    fun showStatusMessage(message: String?, durationMs: Long = 5000L) {
        updateStatusJob?.cancel()
        updateStatus = message
        if (message != null && durationMs > 0) {
            updateStatusJob = viewModelScope.launch {
                delay(durationMs)
                if (updateStatus == message) {
                    updateStatus = null
                }
            }
        }
    }
    
    var isUpdatingBackground by mutableStateOf(false)
        private set

    private val mediaDao = database.mediaDao()
    private val _dbSearchResults = MutableStateFlow<List<MediaSource>>(emptyList())
    val dbSearchResults: StateFlow<List<MediaSource>> = _dbSearchResults.asStateFlow()

    private val _recentlyAdded = MutableStateFlow<List<MediaSource>>(emptyList())
    val recentlyAdded: StateFlow<List<MediaSource>> = _recentlyAdded.asStateFlow()

    private val _favorites = MutableStateFlow<List<MediaSource>>(emptyList())
    val favorites: StateFlow<List<MediaSource>> = _favorites.asStateFlow()
    private val favoriteTimelineLock = Any()
    private var favoriteTimelineGeneration = 0L
    private var favoriteTimelineEpgRevision = 0L
    private var favoriteTimelinePreparationJob: Job? = null
    private val _favoriteTimelineState = MutableStateFlow(FavoriteTimelineState())
    val favoriteTimelineState: StateFlow<FavoriteTimelineState> = _favoriteTimelineState.asStateFlow()

    private fun favoriteTimelineWindow(referenceTimestamp: Long): Pair<Long, Long> {
        val start = referenceTimestamp - (referenceTimestamp % FAVORITE_TIMELINE_BUCKET_SECONDS)
        return start to (start + FAVORITE_TIMELINE_WINDOW_SECONDS)
    }

    private fun orderedLiveFavoriteIds(items: List<MediaSource>): List<Int> =
        items.asSequence()
            .filter { it.type == MediaType.LIVE }
            .sortedBy { it.favoriteDate }
            .map { it.id }
            .toList()

    private fun updateFavorites(items: List<MediaSource>) {
        val referenceTimestamp = System.currentTimeMillis() / 1000
        val (windowStart, windowEnd) = favoriteTimelineWindow(referenceTimestamp)
        val newOrderedIds = orderedLiveFavoriteIds(items)

        synchronized(favoriteTimelineLock) {
            val oldOrderedIds = orderedLiveFavoriteIds(_favorites.value)
            if (oldOrderedIds != newOrderedIds) {
                favoriteTimelineGeneration++
                favoriteTimelinePreparationJob?.cancel()
                favoriteTimelinePreparationJob = null
                _favoriteTimelineState.value = FavoriteTimelineState(
                    epgRevision = favoriteTimelineEpgRevision
                )
            }
            _favorites.value = items

            if (newOrderedIds.isEmpty()) {
                favoriteTimelineGeneration++
                favoriteTimelinePreparationJob?.cancel()
                favoriteTimelinePreparationJob = null
                val key = FavoriteTimelineCacheKey(
                    orderedFavoriteChannelIds = emptyList(),
                    windowStartTimestamp = windowStart,
                    windowEndTimestamp = windowEnd,
                    epgRevision = favoriteTimelineEpgRevision
                )
                _favoriteTimelineState.value = FavoriteTimelineState(
                    status = FavoriteTimelineStatus.EMPTY,
                    windowStartTimestamp = windowStart,
                    windowEndTimestamp = windowEnd,
                    epgRevision = favoriteTimelineEpgRevision,
                    cacheKey = key
                )
            }
        }
    }

    private fun invalidateFavoriteTimeline(epgChanged: Boolean = false) {
        synchronized(favoriteTimelineLock) {
            favoriteTimelineGeneration++
            if (epgChanged) favoriteTimelineEpgRevision++
            favoriteTimelinePreparationJob?.cancel()
            favoriteTimelinePreparationJob = null
            _favoriteTimelineState.value = FavoriteTimelineState(
                epgRevision = favoriteTimelineEpgRevision
            )
        }
    }

    private fun publishFavoriteTimelineIfCurrent(
        generation: Long,
        state: FavoriteTimelineState
    ): Boolean = synchronized(favoriteTimelineLock) {
        if (generation != favoriteTimelineGeneration) {
            false
        } else {
            _favoriteTimelineState.value = state
            true
        }
    }

    fun prepareFavoriteTimeline(referenceTimestamp: Long = System.currentTimeMillis() / 1000) {
        val (windowStart, windowEnd) = favoriteTimelineWindow(referenceTimestamp)
        val favoritesSnapshot: List<MediaSource>
        val generation: Long
        val epgRevision: Long

        synchronized(favoriteTimelineLock) {
            favoriteTimelineGeneration++
            generation = favoriteTimelineGeneration
            epgRevision = favoriteTimelineEpgRevision
            favoriteTimelinePreparationJob?.cancel()
            favoritesSnapshot = _favorites.value
        }

        val preparationJob = viewModelScope.launch(Dispatchers.Default) {
            var cacheKey: FavoriteTimelineCacheKey? = null
            try {
                val orderedFavorites = favoritesSnapshot
                    .filter { it.type == MediaType.LIVE }
                    .sortedBy { it.favoriteDate }
                cacheKey = FavoriteTimelineCacheKey(
                    orderedFavoriteChannelIds = orderedFavorites.map { it.id },
                    windowStartTimestamp = windowStart,
                    windowEndTimestamp = windowEnd,
                    epgRevision = epgRevision
                )
                val hasWarmSnapshot = synchronized(favoriteTimelineLock) {
                    generation == favoriteTimelineGeneration &&
                        _favoriteTimelineState.value.cacheKey == cacheKey &&
                        (_favoriteTimelineState.value.status == FavoriteTimelineStatus.READY ||
                            _favoriteTimelineState.value.status == FavoriteTimelineStatus.EMPTY)
                }
                if (hasWarmSnapshot) return@launch

                if (!publishFavoriteTimelineIfCurrent(
                        generation,
                        FavoriteTimelineState(
                            status = FavoriteTimelineStatus.LOADING,
                            windowStartTimestamp = windowStart,
                            windowEndTimestamp = windowEnd,
                            epgRevision = epgRevision,
                            cacheKey = cacheKey
                        )
                    )
                ) return@launch

                if (orderedFavorites.isEmpty()) {
                    publishFavoriteTimelineIfCurrent(
                        generation,
                        FavoriteTimelineState(
                            status = FavoriteTimelineStatus.EMPTY,
                            windowStartTimestamp = windowStart,
                            windowEndTimestamp = windowEnd,
                            epgRevision = epgRevision,
                            cacheKey = cacheKey
                        )
                    )
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                val epgIdsByChannel = orderedFavorites.associate { channel ->
                    channel.id to (
                        channelToEpgMap[channel.id]?.takeIf { it.isNotBlank() }
                            ?: channel.epgId?.takeIf { it.isNotBlank() }
                            ?: "stream:${channel.id}"
                    )
                }
                val epgById = _repository.getEpgForChannels(
                    epgIds = epgIdsByChannel.values,
                    windowStartTimestamp = windowStart,
                    windowEndTimestamp = windowEnd
                )
                currentCoroutineContext().ensureActive()

                val rows = orderedFavorites.map { channel ->
                    currentCoroutineContext().ensureActive()
                    val epgId = epgIdsByChannel.getValue(channel.id)
                    FavoriteTimelineRow(
                        channel = channel,
                        epgId = epgId,
                        programs = epgById[epgId].orEmpty().toList()
                    )
                }.toList()

                currentCoroutineContext().ensureActive()
                publishFavoriteTimelineIfCurrent(
                    generation,
                    FavoriteTimelineState(
                        status = FavoriteTimelineStatus.READY,
                        rows = rows,
                        windowStartTimestamp = windowStart,
                        windowEndTimestamp = windowEnd,
                        epgRevision = epgRevision,
                        cacheKey = cacheKey
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                publishFavoriteTimelineIfCurrent(
                    generation,
                    FavoriteTimelineState(
                        status = FavoriteTimelineStatus.ERROR,
                        windowStartTimestamp = windowStart,
                        windowEndTimestamp = windowEnd,
                        epgRevision = epgRevision,
                        cacheKey = cacheKey,
                        errorMessage = e.message ?: "Kunde inte läsa in favoriternas TV-tablå"
                    )
                )
            }
        }

        synchronized(favoriteTimelineLock) {
            if (generation == favoriteTimelineGeneration) {
                favoriteTimelinePreparationJob = preparationJob
            } else {
                preparationJob.cancel()
            }
        }
    }

    init {
        viewModelScope.launch {
            snapshotFlow { searchQuery }.collectLatest { query ->
                if (query.length < 2) {
                    _dbSearchResults.value = emptyList()
                    return@collectLatest
                }

                try {
                    delay(250)
                    val queryPattern = "%$query%"
                    val entities = withContext(Dispatchers.IO) {
                        val selectedCategories = MediaType.entries.associateWith { type ->
                            sessionManager.getSyncCategories(type).toList()
                        }
                        buildList {
                            for (type in MediaType.entries) {
                                currentCoroutineContext().ensureActive()
                                val categoryIds = selectedCategories.getValue(type)
                                if (categoryIds.isNotEmpty()) {
                                    addAll(
                                        mediaDao.searchMediaByType(
                                            query = queryPattern,
                                            type = type,
                                            categoryIds = categoryIds,
                                            limit = SEARCH_RESULTS_PER_TYPE
                                        )
                                    )
                                }
                            }
                        }
                    }
                    val results = withContext(Dispatchers.Default) {
                        val searchContext = currentCoroutineContext()
                        entities.map { entity ->
                            searchContext.ensureActive()
                            entity.toMediaSource()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    _dbSearchResults.value = results
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _dbSearchResults.value = emptyList()
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val favs = mediaDao.getFavorites()
            updateFavorites(favs.filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() })
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val recent = mediaDao.getRecentlyAdded()
            _recentlyAdded.value = recent.filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            StartupDiagnostics.timed("delete_old_epg", "trigger=viewmodel_init") {
                mediaDao.deleteOldEpg(now)
            }
        }
    }

    private fun MediaEntity.toMediaSource() = MediaSource(
        id = id,
        title = title,
        icon = resolvedIcon ?: icon,
        resolvedIcon = resolvedIcon,
        type = type,
        categoryId = categoryId,
        categoryName = categoryName,
        extension = extension,
        plot = plot,
        rating = rating,
        director = director,
        genre = genre,
        cast = cast,
        epgId = epgId,
        isFavorite = isFavorite,
        favoriteDate = favoriteDate,
        addedDate = addedDate
    )

    fun updateRepository(newRepository: MediaRepository) {
        this._repository = newRepository
    }

    suspend fun login(h: String, u: String, p: String): Boolean {
        loginError = null
        val host = h.trim()
        val user = u.trim()
        val pass = p.trim()
        return try {
            val response = _repository.api.login(user, pass)
            val userInfo = response.userInfo
            val statusStr = userInfo?.status?.trim()
            val authStr = userInfo?.auth?.toString()?.trim()

            val isAuthOk = authStr == "1" || authStr == "1.0" || authStr?.equals("true", ignoreCase = true) == true
            val isStatusActive = statusStr?.equals("Active", ignoreCase = true) == true || statusStr == "1"
            val isNotBlocked = statusStr?.equals("Banned", ignoreCase = true) != true 
                    && statusStr?.equals("Disabled", ignoreCase = true) != true
                    && statusStr?.equals("Expired", ignoreCase = true) != true

            val success = (isAuthOk || isStatusActive || userInfo != null) && isNotBlocked
            if (success) {
                sessionManager.saveLogin(host, user, pass)
            } else {
                loginError = if (statusStr?.equals("Expired", ignoreCase = true) == true) {
                    "Konto har gått ut"
                } else if (statusStr?.equals("Banned", ignoreCase = true) == true || statusStr?.equals("Disabled", ignoreCase = true) == true) {
                    "Konto är avstängt"
                } else {
                    "Ogiltiga inloggningsuppgifter eller konto inte aktivt"
                }
            }
            success
        } catch (e: Exception) {
            loginError = "Anslutningsfel: ${e.message ?: "Kunde inte ansluta till servern"}"
            false
        }
    }

    fun logout() {
        sessionManager.logout()
        startupError = null
        showSyncSelection = false
        syncCategoryOptions = emptyMap()
        uiState = MediaUiState()
        invalidateFavoriteTimeline(epgChanged = true)
        updateFavorites(emptyList())
        fullEpgData.clear()
        channelToEpgMap.clear()
        preparedEpgCategoryIds.clear()
        loadedFullEpgIds.clear()
        _dbSearchResults.value = emptyList()
    }

    fun refreshLists() {
        val creds = sessionManager.getLogin()
        if (creds != null) {
            fetchData(creds.second, creds.third, forceRefresh = true)
        }
    }

    fun setLiveCategoryByMediaId(mediaId: Int) {
        uiState.liveCategories.indexOfFirst { category ->
            category.items.any { it.id == mediaId }
        }.takeIf { it >= 0 }?.let { index ->
            lastLiveCategoryIndex = index
        }
    }

    private suspend fun loadSelectedRoomCatalog(): Map<MediaType, List<GroupedMedia>> =
        withContext(Dispatchers.IO) {
            MediaType.entries.associateWith { type ->
                val selected = sessionManager.getSyncCategories(type)
                StartupDiagnostics.timed("room_categories", "type=$type") {
                    mediaDao.getCategoriesByType(type)
                }.filter { it.categoryId in selected }
                    .distinctBy { it.categoryId }
                    .map { GroupedMedia(it.categoryName, emptyList(), it.categoryId) }
            }
        }

    private suspend fun publishStartupCatalog(catalog: Map<MediaType, List<GroupedMedia>>) {
        val selection = MediaType.entries.associateWith(sessionManager::getSyncCategories)
        val allFavs = withContext(Dispatchers.IO) { mediaDao.getFavorites() }
        val history = sessionManager.getHistory()

        fun groups(type: MediaType, previous: List<GroupedMedia>): List<GroupedMedia> {
            val categories = catalog[type].orEmpty().filter { it.categoryId in selection.getValue(type) }
            val retained = mergeStartupCategories(
                previous.filter { it.categoryId in selection.getValue(type) },
                categories
            )
            val favorites = allFavs.filter {
                it.type == type && (it.isFavorite || it.categoryId in selection.getValue(type))
            }.map { it.toMediaSource() }.let { items ->
                if (type == MediaType.LIVE) items.sortedBy { it.favoriteDate } else items
            }
            val first = if (type == MediaType.LIVE) {
                GroupedMedia("📺 ALLA KANALER",
                    previous.firstOrNull { it.categoryId == "ALL_CHANNELS" }?.items.orEmpty(), "ALL_CHANNELS")
            } else {
                GroupedMedia("🕒 HISTORIK",
                    history.filter { it.type == type && (it.isFavorite || it.categoryId in selection.getValue(type)) },
                    "HISTORY")
            }
            return listOf(first, GroupedMedia("⭐ FAVORITER", favorites, "FAVORITES")) + retained
        }

        val oldState = uiState
        val live = groups(MediaType.LIVE, oldState.liveCategories)
        val movies = groups(MediaType.MOVIE, oldState.movieCategories)
        val series = groups(MediaType.SERIES, oldState.seriesCategories)
        val ppv = live.filter { category ->
            listOf("TV4 Play", "Viaplay", "Svensk Hockey.tv", "Telia play").any {
                category.title?.contains(it, ignoreCase = true) == true
            }
        }
        fun restoredIndex(old: List<GroupedMedia>, index: Int, updated: List<GroupedMedia>, fallback: Int = 0): Int {
            val id = old.getOrNull(index)?.categoryId
            return updated.indexOfFirst { id != null && it.categoryId == id }
                .takeIf { it >= 0 } ?: fallback.coerceIn(0, updated.lastIndex.coerceAtLeast(0))
        }
        val liveDefault = live.indexOfFirst { it.categoryId == "FAVORITES" && it.items.isNotEmpty() }
            .takeIf { it >= 0 } ?: if (live.size > 2) 2 else 0
        uiState = oldState.copy(
            liveCategories = live, movieCategories = movies, seriesCategories = series,
            ppvCategories = ppv
        )
        lastLiveCategoryIndex = restoredIndex(oldState.liveCategories, lastLiveCategoryIndex, live, liveDefault)
        lastMovieCategoryIndex = restoredIndex(oldState.movieCategories, lastMovieCategoryIndex, movies)
        lastSeriesCategoryIndex = restoredIndex(oldState.seriesCategories, lastSeriesCategoryIndex, series)
        lastPpvCategoryIndex = restoredIndex(oldState.ppvCategories, lastPpvCategoryIndex, ppv)
        updateFavorites(allFavs.filter { it.categoryId in selection.getValue(it.type) }.map { it.toMediaSource() })
    }

    private suspend fun loadStartupItems(reload: Boolean = false) {
        StartupDiagnostics.timed("startup_items", "reload=$reload") {
        val initialRequests = listOf(
            MediaType.LIVE to uiState.liveCategories.getOrNull(lastLiveCategoryIndex),
            MediaType.LIVE to uiState.liveCategories.getOrNull(2),
            MediaType.LIVE to uiState.ppvCategories.getOrNull(lastPpvCategoryIndex),
            MediaType.MOVIE to uiState.movieCategories.getOrNull(lastMovieCategoryIndex),
            MediaType.MOVIE to uiState.movieCategories.getOrNull(2),
            MediaType.SERIES to uiState.seriesCategories.getOrNull(lastSeriesCategoryIndex),
            MediaType.SERIES to uiState.seriesCategories.getOrNull(2)
        )
        val loadedRequests = if (reload) {
            listOf(
                MediaType.LIVE to (uiState.liveCategories + uiState.ppvCategories),
                MediaType.MOVIE to uiState.movieCategories,
                MediaType.SERIES to uiState.seriesCategories
            ).flatMap { (type, groups) -> groups.filter { it.items.isNotEmpty() }.map { type to it } }
        } else emptyList()
        val requests = (initialRequests + loadedRequests).distinctBy { (type, group) -> type to group?.categoryId }
        for ((type, group) in requests) {
            if (group != null && (reload || group.items.isEmpty())) {
                loadCategoryItems(type, group.categoryId, prefetchEpg = false)
            }
        }
        }
    }

    fun fetchData(user: String, pass: String, forceRefresh: Boolean = false, onComplete: ((Boolean) -> Unit)? = null, diagnosticTrigger: String = "fetchData") {
        if (isFetching) {
            StartupDiagnostics.event("startup_skip", "reason=already_fetching trigger=$diagnosticTrigger force=$forceRefresh")
            return
        }
        isFetching = true
        startupError = null
        isUpdatingBackground = true

        viewModelScope.launch(StartupDiagnostics.Trigger("foreground:$diagnosticTrigger")) {
            val diagnosticSpan = StartupDiagnostics.start("foreground_startup", "force=$forceRefresh pending=${sessionManager.isSyncSelectionPending()}")
            var diagnosticOutcome = "returned"
            var contentAvailable = false
            var completionReported = false
            fun reportReady(success: Boolean) {
                if (!completionReported) {
                    completionReported = true
                    StartupDiagnostics.event("startup_ready", "span=${diagnosticSpan.id} success=$success elapsed_ms=${android.os.SystemClock.elapsedRealtime() - diagnosticSpan.started}")
                    onComplete?.invoke(success)
                }
            }
            uiState = uiState.copy(isLoading = true)
            try {
                StartupDiagnostics.rows(mediaDao, "startup_before")
                // Returning users can browse Room content before any catalog/network work.
                if (sessionManager.hasSyncSelection()) {
                    val localCatalog = loadSelectedRoomCatalog()
                    if (localCatalog.values.any { it.isNotEmpty() } &&
                        sessionManager.isCatalogOwnedByCurrentAccount()
                    ) {
                        publishStartupCatalog(localCatalog)
                        loadStartupItems()
                        contentAvailable = true
                        if (!forceRefresh) {
                            // Cached returning users are ready once the Room-backed catalog and
                            // initial visible items are published. Network and maintenance work
                            // below remains owned by this ViewModel coroutine.
                            uiState = uiState.copy(isLoading = false)
                            StartupDiagnostics.event("startup_ready_cached", "span=${diagnosticSpan.id}")
                            reportReady(true)
                        }
                    }
                }

                showStatusMessage("Hämtar kategorier...")
                syncCategoryOptions = StartupDiagnostics.timed("category_catalog", "force=$forceRefresh") {
                    loadSyncCategoryOptions(user, pass, forceRefresh)
                }
                if (syncCategoryOptions.values.all { it.isEmpty() } && !contentAvailable) {
                    startupError = "Kunde inte hämta några kategorier från servern. Kontrollera server-URL och anslutningen."
                    showStatusMessage(null)
                    reportReady(false)
                    return@launch
                }
                if (!sessionManager.hasSyncSelection()) {
                    showSyncSelection = true
                    showStatusMessage(null)
                    reportReady(true)
                    return@launch
                }

                // Empty/failed sections retain their local categories and loaded items.
                var displayCatalog = syncCategoryOptions
                publishStartupCatalog(displayCatalog)
                loadStartupItems()
                contentAvailable = true

                val needsLibrarySync = forceRefresh || sessionManager.isSyncSelectionPending()
                StartupDiagnostics.event("library_decision", "sync=$needsLibrarySync force=$forceRefresh pending=${sessionManager.isSyncSelectionPending()}")
                if (needsLibrarySync) {
                    showStatusMessage("Synkar valda kategorier...")
                    _repository.syncLibrary(user, pass)
                    // Room is authoritative after the import. Publish its category
                    // snapshot before loading items so first-login UI sees every group.
                    displayCatalog = loadSelectedRoomCatalog()
                    publishStartupCatalog(displayCatalog)
                    // Newly synchronized channels become usable before picons and EPG finish.
                    loadStartupItems(reload = true)
                }

                val iconsChanged = _repository.extractPiconsIfNeeded(rematchExisting = false)
                invalidateFavoriteTimeline()
                _repository.fetchAndStoreEpg(user, pass, forceRefresh)
                invalidateFavoriteTimeline(epgChanged = true)
                if (needsLibrarySync) _repository.resolveLiveIcons()

                fullEpgData.clear()
                preparedEpgCategoryIds.clear()
                loadedFullEpgIds.clear()
                currentEpgCache.clear()
                if (needsLibrarySync || iconsChanged) {
                    // Refresh displayed data after icon changes without discarding other loaded lists.
                    val loadedLiveIds = (uiState.liveCategories + uiState.ppvCategories)
                        .filter { it.items.isNotEmpty() }.mapNotNull { it.categoryId }.distinct()
                    for (id in loadedLiveIds) {
                        loadCategoryItems(MediaType.LIVE, id, prefetchEpg = false)
                    }
                    publishStartupCatalog(displayCatalog)
                    val selected = MediaType.entries.associateWith(sessionManager::getSyncCategories)
                    _recentlyAdded.value = withContext(Dispatchers.IO) {
                        mediaDao.getRecentlyAdded().filter { it.categoryId in selected.getValue(it.type) }
                            .map { it.toMediaSource() }
                    }
                }
                prefetchEpgForCategory(lastLiveCategoryIndex)
                showStatusMessage(if (syncCategoryOptions.values.all { it.isEmpty() }) {
                    "Visar sparade kategorier. Kategorierna kunde inte uppdateras."
                } else "Innehållet är uppdaterat")
                reportReady(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                diagnosticOutcome = "cancelled"
                throw e
            } catch (e: Exception) {
                diagnosticOutcome = "caught_${e.javaClass.simpleName}"
                if (!contentAvailable) {
                    startupError = "Servern svarade inte i tid eller kategorierna kunde inte läsas. Inga automatiska nya försök görs."
                    showStatusMessage(null)
                } else {
                    showStatusMessage("Synkningen misslyckades. Försök igen i Inställningar.")
                }
                reportReady(false)
            } finally {
                uiState = uiState.copy(isLoading = false)
                isUpdatingBackground = false
                isFetching = false
                try {
                    StartupDiagnostics.rows(mediaDao, "startup_after")
                    StartupDiagnostics.event("startup_state_end", "pending=${sessionManager.isSyncSelectionPending()}")
                } finally {
                    StartupDiagnostics.end(diagnosticSpan, diagnosticOutcome)
                }
            }
        }
    }

    fun loadItemsForCategory(type: MediaType, categoryId: String?) {
        categoryLoadJobs[type]?.cancel()
        categoryLoadJobs[type] = viewModelScope.launch {
            loadCategoryItems(type, categoryId)
        }
    }

    private suspend fun loadCategoryItems(
        type: MediaType,
        categoryId: String?,
        prefetchEpg: Boolean = true
    ) {
        if (categoryId == null) return

        // Returnera tidigt för specialkategorier (Favoriter/Historik) som inte hämtas från API/DB-kategorier
        if (categoryId == "FAVORITES" || categoryId == "HISTORY") return

        val items = StartupDiagnostics.timed("room_category_items", "type=$type all_channels=${categoryId == "ALL_CHANNELS"}") {
        if (categoryId == "ALL_CHANNELS") {
            withContext(Dispatchers.IO) {
                val selected = sessionManager.getSyncCategories(MediaType.LIVE)
                mediaDao.getMediaByType(MediaType.LIVE).filter { it.categoryId in selected }.map { it.toMediaSource() }
            }
        } else {
            _repository.getMediaForCategory(type, categoryId)
        }
        }
        StartupDiagnostics.event("category_items_loaded", "type=$type count=${items.size}")
        currentCoroutineContext().ensureActive()

        // Mappa kanal-ID till EPG-ID direkt (Optimering: använd batch-uppdatering)
        if (type == MediaType.LIVE) {
            preparedEpgCategoryIds.remove(categoryId)
            val newMappings = withContext(Dispatchers.Default) {
                HashMap<Int, String>(items.size).apply {
                    items.forEach { item ->
                        item.epgId?.let { epgId -> put(item.id, epgId) }
                    }
                }
            }
            if (newMappings.isNotEmpty()) {
                channelToEpgMap.putAll(newMappings)
            }

            // Om detta är den aktuella spellistan i PlayerScreen, uppdatera den
            if (uiState.liveCategories.getOrNull(lastLiveCategoryIndex)?.categoryId == categoryId) {
                currentPlaylist = items
            }
        }

        uiState = when (type) {
            MediaType.LIVE -> uiState.copy(
                liveCategories = uiState.liveCategories.map {
                    if (it.categoryId == categoryId) it.copy(items = items) else it
                },
                ppvCategories = uiState.ppvCategories.map {
                    if (it.categoryId == categoryId) it.copy(items = items) else it
                }
            )
            MediaType.MOVIE -> uiState.copy(movieCategories = uiState.movieCategories.map {
                if (it.categoryId == categoryId) it.copy(items = items) else it
            })
            MediaType.SERIES -> uiState.copy(seriesCategories = uiState.seriesCategories.map {
                if (it.categoryId == categoryId) it.copy(items = items) else it
            })
        }

        // Kategorin måste finnas i uiState innan batchcachen kan byggas.
        if (prefetchEpg && type == MediaType.LIVE &&
            uiState.liveCategories.getOrNull(lastLiveCategoryIndex)?.categoryId == categoryId
        ) {
            prefetchEpgForCategory(lastLiveCategoryIndex)
        }
    }

    fun toggleFavorite(media: MediaSource) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = mediaDao.getMediaById(media.id, media.type) ?: return@launch
            val newFavStatus = !entity.isFavorite
            mediaDao.updateFavoriteWithDate(media.id, media.type, newFavStatus, if (newFavStatus) System.currentTimeMillis() else 0)
            
            withContext(Dispatchers.Main) {
                // Uppdatera selectedMedia om det är det vi tittar på
                if (selectedMedia?.id == media.id) {
                    selectedMedia = selectedMedia?.copy(isFavorite = newFavStatus)
                }

                // Uppdatera uiState-kategorier för att reflektera ändringen i listorna
                uiState = uiState.copy(
                    liveCategories = uiState.liveCategories.updateFavoriteInCategory(media.id, newFavStatus, MediaType.LIVE),
                    movieCategories = uiState.movieCategories.updateFavoriteInCategory(media.id, newFavStatus, MediaType.MOVIE),
                    seriesCategories = uiState.seriesCategories.updateFavoriteInCategory(media.id, newFavStatus, MediaType.SERIES)
                )
                if (media.type == MediaType.LIVE) {
                    preparedEpgCategoryIds.remove("FAVORITES")
                }

                // Uppdatera favorites Flow för att trigga andra lyssnare (t.ex. sökning)
                val updatedFavs = mediaDao.getFavorites().filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
                updateFavorites(updatedFavs)
            }
        }
    }

    private fun List<GroupedMedia>.updateFavoriteInCategory(id: Int, isFav: Boolean, type: MediaType): List<GroupedMedia> {
        val updatedList = this.map { group ->
            group.copy(items = group.items.map { if (it.id == id) it.copy(isFavorite = isFav) else it })
        }

        // Uppdatera den dedikerade "FAVORITER"-kategorin om den finns
        return updatedList.map { group ->
            if (group.categoryId == "FAVORITES") {
                val currentItems = group.items.toMutableList()
                if (isFav) {
                    // Om det inte redan finns i Favoriter (och matchar typen), lägg till det
                    val itemToAdd = updatedList.flatMap { it.items }.find { it.id == id && it.type == type }
                    if (itemToAdd != null && currentItems.none { it.id == id }) {
                        if (type == MediaType.LIVE) currentItems.add(itemToAdd)
                        else currentItems.add(0, itemToAdd)
                    }
                } else {
                    currentItems.removeAll { it.id == id }
                }
                group.copy(items = currentItems)
            } else {
                group
            }
        }
    }

    fun prefetchEpgForCategory(categoryIndex: Int) {
        val category = uiState.liveStreamsGrouped.getOrNull(categoryIndex) ?: return
        val categoryKey = category.categoryId ?: "category_$categoryIndex"
        val itemsToPrefetch = category.items

        if (itemsToPrefetch.isEmpty() || preparedEpgCategoryIds.contains(categoryKey)) return
        if (!prefetchingCategoryIds.add(categoryKey)) return

        viewModelScope.launch(Dispatchers.Default) {
            val mappings = HashMap<Int, String>(itemsToPrefetch.size)
            val epgIds = LinkedHashSet<String>(itemsToPrefetch.size)
            itemsToPrefetch.forEach { item ->
                val epgId = item.epgId?.takeIf { it.isNotBlank() } ?: "stream:${item.id}"
                mappings[item.id] = epgId
                epgIds.add(epgId)
            }
            val missingEpgIds = epgIds.filterNot { loadedFullEpgIds.contains(it) }
            val diagnosticStart = OverlayDiagnostics.prefetchStart(
                categoryIndex,
                category.categoryId,
                itemsToPrefetch.size,
                mappings.size
            )
            var putAllEntries = 0

            try {
                withContext(Dispatchers.Main) {
                    channelToEpgMap.putAll(mappings)
                }

                if (missingEpgIds.isEmpty()) {
                    preparedEpgCategoryIds.add(categoryKey)
                    return@launch
                }

                val epgByChannel = _repository.getEpgForChannels(missingEpgIds)
                putAllEntries = epgByChannel.size
                withContext(Dispatchers.Main) {
                    fullEpgData.putAll(epgByChannel)
                    loadedFullEpgIds.addAll(missingEpgIds)
                }
                preparedEpgCategoryIds.add(categoryKey)
            } finally {
                OverlayDiagnostics.prefetchEnd(
                    categoryIndex,
                    diagnosticStart,
                    missingEpgIds.size,
                    putAllEntries
                )
                prefetchingCategoryIds.remove(categoryKey)
            }
        }
    }
    /** Läsning utan sidoeffekter; säker att anropa från en Composable. */
    fun getCachedFullEpgForId(id: Int): List<EpgListing> {
        val epgId = channelToEpgMap[id] ?: return emptyList()
        return fullEpgData[epgId].orEmpty()
    }

    /** Ren cacheläsning för overlayen; startar aldrig databas eller nätverk. */
    fun getCachedCurrentEpgForId(id: Int, currentTime: Long = System.currentTimeMillis() / 1000): EpgListing? {
        return getCachedFullEpgForId(id).find {
            (it.startTimestamp ?: 0) <= currentTime && (it.stopTimestamp ?: 0) > currentTime
        }
    }

    /** Läsning utan sidoeffekter; säker att anropa från en Composable. */
    fun getCachedIconForId(id: Int, type: MediaType): String? =
        piconCache["${type}_$id"]

    /**
     * Bakåtkompatibel ingång för äldre Composables. Kanaldata förbereds per kategori
     * och får inte starta enskilda databas- eller nätverksuppslag medan en rad ritas.
     */
    fun loadChannelAssets(id: Int, type: MediaType, name: String? = null) {
        // Avsiktligt tom. EPG fylls med prefetchEpgForCategory och ikoner sparas vid
        // uppdatering av kanallistan. Parametrarna behålls tills alla gamla anrop är borta.
    }

    /**
     * Hämtar nuvarande program för en kanal. 
     * Icke-suspending för att kunna anropas direkt från Compose.
     * Om data saknas triggas en bakgrundshämtning.
     */
    fun getEpgForId(id: Int, type: MediaType, name: String? = null): EpgListing? {
        val now = System.currentTimeMillis() / 1000
        val cacheKey = "${type}_$id"
        
        // 1. Snabb-cache för nuvarande program (i minnet)
        currentEpgCache[cacheKey]?.let { cached ->
            if (now >= (cached.startTimestamp ?: 0L) && now < (cached.stopTimestamp ?: 0L)) return cached
        }

        // 2. Försök hitta EPG-ID och data i SnapshotStateMap
        var epgId = channelToEpgMap[id]
        if (epgId != null) {
            fullEpgData[epgId]?.let { listings ->
                val current = listings.find { (it.startTimestamp ?: 0) <= now && (it.stopTimestamp ?: 0) > now }
                if (current != null) {
                    currentEpgCache[cacheKey] = current
                    return current
                }
            }
            if (loadedFullEpgIds.contains(epgId)) return null
        }

        // 3. Om vi saknar data, hämta i bakgrunden
        if (!fetchingEpgIds.contains(id)) {
            fetchingEpgIds.add(id)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val media = mediaDao.getMediaById(id, type)
                    val finalEpgId = epgId ?: media?.epgId?.takeIf { it.isNotBlank() } ?: "stream:$id"
                    val channelName = name ?: media?.title
                    
                    withContext(Dispatchers.Main) { channelToEpgMap[id] = finalEpgId }
                    
                    val epg = _repository.getEpgForChannel(finalEpgId, channelName)
                    if (epg.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            fullEpgData[finalEpgId] = epg
                            loadedFullEpgIds.add(finalEpgId)
                            val current = epg.find { (it.startTimestamp ?: 0) <= now && (it.stopTimestamp ?: 0) > now }
                            if (current != null) {
                                currentEpgCache[cacheKey] = current
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) { loadedFullEpgIds.add(finalEpgId) }
                    }
                } catch (e: Exception) {
                } finally {
                    withContext(Dispatchers.Main) { fetchingEpgIds.remove(id) }
                }
            }
        }
        return null
    }

    fun getNextEpgForId(id: Int, type: MediaType, name: String? = null): EpgListing? {
        val current = getEpgForId(id, type, name) ?: return null
        
        val epgId = channelToEpgMap[id] ?: return null
        val listings = fullEpgData[epgId] ?: return null
        
        return listings.find { it.startTimestamp == current.stopTimestamp }
    }

    /**
     * Hämtar hela tablån för en kanal (för EPG Grid).
     * Icke-suspending. Triggar bakgrundshämtning om saknas.
     */
    fun getFullEpgForId(id: Int, type: MediaType, name: String? = null): List<EpgListing> {
        val epgId = channelToEpgMap[id]
        
        if (epgId != null) {
            fullEpgData[epgId]?.let { return it }
            if (loadedFullEpgIds.contains(epgId)) return emptyList()
        }
        
        val key = epgId ?: "unknown_$id"
        if (!fetchingFullEpgIds.contains(key)) {
            fetchingFullEpgIds.add(key)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val finalEpgId = epgId ?: run {
                        val media = mediaDao.getMediaById(id, type)
                        media?.epgId?.also {
                            withContext(Dispatchers.Main) { channelToEpgMap[id] = it }
                        }
                    }
                    if (finalEpgId != null) {
                        val epg = _repository.getEpgForChannel(finalEpgId, name)
                        if (epg.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                fullEpgData[finalEpgId] = epg
                                loadedFullEpgIds.add(finalEpgId)
                            }
                        } else {
                            withContext(Dispatchers.Main) { loadedFullEpgIds.add(finalEpgId) }
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    withContext(Dispatchers.Main) { fetchingFullEpgIds.remove(key) }
                }
            }
        }
        return emptyList()
    }

    fun getIconForId(id: Int, type: MediaType, name: String? = null): String? {
        // Ikoner är färdigmatchade och sparade när användaren uppdaterar kanallistan.
        // UI:t använder alltid MediaSource.icon och gör aldrig namn-/databasuppslag här.
        return null
    }

    suspend fun getIconForChannel(id: Int, type: MediaType, name: String? = null): String? {
        return null
    }

    fun refreshDataManually() {
        val creds = sessionManager.getLogin() ?: return
        fetchData(creds.second, creds.third, forceRefresh = true)
    }

    fun refreshTvChannels() {
        if (isUpdatingBackground) return
        if (requiresSyncSelection) { openSyncSelection(); return }
        viewModelScope.launch {
            try {
                val creds = sessionManager.getLogin() ?: return@launch
                isUpdatingBackground = true
                showStatusMessage("Uppdaterar TV-kanaler...")

                withContext(Dispatchers.IO) {
                    _repository.syncLiveChannels(creds.second, creds.third)
                    invalidateFavoriteTimeline()
                    _repository.fetchAndStoreEpg(creds.second, creds.third)
                    invalidateFavoriteTimeline(epgChanged = true)
                    _repository.resolveLiveIcons()
                    withContext(Dispatchers.Main) {
                        fullEpgData.clear()
                        preparedEpgCategoryIds.clear()
                        loadedFullEpgIds.clear()
                        currentEpgCache.clear()
                    }
                    val liveData = _repository.getJustCategories(MediaType.LIVE, creds.second, creds.third, forceRefresh = true)
                    val allFavs = mediaDao.getFavorites()

                    withContext(Dispatchers.Main) {
                        val favsForType = allFavs.filter { it.type == MediaType.LIVE && it.categoryId in sessionManager.getSyncCategories(MediaType.LIVE) }.map { it.toMediaSource() }.sortedBy { it.favoriteDate }
                        uiState = uiState.copy(
                            liveCategories = listOf(
                                GroupedMedia(title = "📺 ALLA KANALER", categoryId = "ALL_CHANNELS", items = emptyList()),
                                GroupedMedia(title = "⭐ FAVORITER", categoryId = "FAVORITES", items = favsForType)
                            ) + liveData
                        )
                        loadItemsForCategory(MediaType.LIVE, liveData.firstOrNull()?.categoryId)
                    }
                }

                showStatusMessage("Spellista för TV-Kanaler uppdaterades")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatusMessage("Synkningen misslyckades. Försök igen.")
            } finally {
                isUpdatingBackground = false
            }
        }
    }

    fun refreshVodLibrary() {
        if (isUpdatingBackground) return
        if (requiresSyncSelection) { openSyncSelection(); return }
        viewModelScope.launch {
            try {
                val creds = sessionManager.getLogin() ?: return@launch
                isUpdatingBackground = true
                showStatusMessage("Uppdaterar film & serier...")

                withContext(Dispatchers.IO) {
                    _repository.syncVodLibrary(creds.second, creds.third)
                    val movieData = _repository.getJustCategories(MediaType.MOVIE, creds.second, creds.third, forceRefresh = true)
                    val seriesData = _repository.getJustCategories(MediaType.SERIES, creds.second, creds.third, forceRefresh = true)
                    val allFavs = mediaDao.getFavorites()
                    val history = sessionManager.getHistory()

                    withContext(Dispatchers.Main) {
                        fun List<GroupedMedia>.withExtras(type: MediaType): List<GroupedMedia> {
                            val favsForType = allFavs.filter { it.type == type && it.categoryId in sessionManager.getSyncCategories(type) }.map { it.toMediaSource() }.let { if (type == MediaType.LIVE) it.sortedBy { item -> item.favoriteDate } else it }
                            val historyForType = history.filter { it.type == type && it.categoryId in sessionManager.getSyncCategories(type) }
                            return listOf(
                                GroupedMedia(title = "🕒 HISTORIK", categoryId = "HISTORY", items = historyForType),
                                GroupedMedia(title = "⭐ FAVORITER", categoryId = "FAVORITES", items = favsForType)
                            ) + this
                        }

                        uiState = uiState.copy(
                            movieCategories = movieData.withExtras(MediaType.MOVIE),
                            seriesCategories = seriesData.withExtras(MediaType.SERIES)
                        )

                        loadItemsForCategory(MediaType.MOVIE, movieData.firstOrNull()?.categoryId)
                        loadItemsForCategory(MediaType.SERIES, seriesData.firstOrNull()?.categoryId)

                        _recentlyAdded.value = mediaDao.getRecentlyAdded().filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
                    }
                }

                showStatusMessage("Spellista för Film/Serier uppdaterades")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatusMessage("Synkningen misslyckades. Försök igen.")
            } finally {
                isUpdatingBackground = false
            }
        }
    }

    fun refreshEpgOnly() {
        if (isUpdatingBackground) return
        if (requiresSyncSelection) { openSyncSelection(); return }
        viewModelScope.launch {
            try {
                val creds = sessionManager.getLogin() ?: return@launch
                isUpdatingBackground = true
                showStatusMessage("Uppdaterar tablåer...")
                invalidateFavoriteTimeline()
                _repository.fetchAndStoreEpg(creds.second, creds.third, forceRefresh = true)
                invalidateFavoriteTimeline(epgChanged = true)
                fullEpgData.clear()
                preparedEpgCategoryIds.clear()
                loadedFullEpgIds.clear()
                showStatusMessage("TV-Tablån är uppdaterad")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatusMessage("Synkningen misslyckades. Försök igen.")
            } finally {
                isUpdatingBackground = false
            }
        }
    }

    fun performOptimization() {
        viewModelScope.launch(Dispatchers.IO) {
            isUpdatingBackground = true
            showStatusMessage("Optimerar databas...")
            database.openHelper.writableDatabase.execSQL("VACUUM")
            val now = System.currentTimeMillis() / 1000
            mediaDao.deleteOldEpg(now)
            withContext(Dispatchers.Main) {
                showStatusMessage("Databasen har optimerats")
                isUpdatingBackground = false
            }
        }
    }

    fun extractPicons() {
        viewModelScope.launch(Dispatchers.IO) {
            isUpdatingBackground = true
            showStatusMessage("Extraherar lokala ikoner...")
            _repository.extractPiconsIfNeeded()
            withContext(Dispatchers.Main) {
                showStatusMessage("Lokala ikoner extraherades")
                isUpdatingBackground = false
            }
        }
    }

    fun deleteFavorites() {
        viewModelScope.launch(Dispatchers.IO) {
            mediaDao.clearAllFavorites()
            withContext(Dispatchers.Main) {
                uiState = uiState.copy(
                    liveCategories = uiState.liveCategories.map { group ->
                        if (group.categoryId == "FAVORITES") group.copy(items = emptyList())
                        else group.copy(items = group.items.map { it.copy(isFavorite = false) })
                    },
                    movieCategories = uiState.movieCategories.map { group ->
                        if (group.categoryId == "FAVORITES") group.copy(items = emptyList())
                        else group.copy(items = group.items.map { it.copy(isFavorite = false) })
                    },
                    seriesCategories = uiState.seriesCategories.map { group ->
                        if (group.categoryId == "FAVORITES") group.copy(items = emptyList())
                        else group.copy(items = group.items.map { it.copy(isFavorite = false) })
                    }
                )
                updateFavorites(emptyList())
                // Uppdatera även selectedMedia om det är en favorit
                selectedMedia?.let {
                    if (it.isFavorite) {
                        selectedMedia = it.copy(isFavorite = false)
                    }
                }
            }
        }
    }

    fun fetchSeriesDetails(seriesId: Int) {
        if (lastLoadedSeriesId == seriesId) return
        selectedSeriesInfo = null
        isDetailsLoading = true
        viewModelScope.launch {
            try {
                val creds = sessionManager.getLogin() ?: return@launch
                val info = _repository.api.getSeriesInfo(creds.second, creds.third, seriesId)
                selectedSeriesInfo = info
                lastLoadedSeriesId = seriesId
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDetailsLoading = false
            }
        }
    }

    fun clearHistory() {
        sessionManager.clearHistory()
        uiState = uiState.copy(
            history = emptyList(),
            movieCategories = uiState.movieCategories.updateHistoryInCategory(emptyList(), MediaType.MOVIE),
            seriesCategories = uiState.seriesCategories.updateHistoryInCategory(emptyList(), MediaType.SERIES)
        )
    }

    fun clearAllFavorites() {
        deleteFavorites()
    }

    fun loadSeriesInfo(seriesId: Int) {
        fetchSeriesDetails(seriesId)
    }

    fun loadMovieInfo(movieId: Int) {
        selectedMovieInfo = null
        isDetailsLoading = true
        viewModelScope.launch {
            try {
                val creds = sessionManager.getLogin() ?: return@launch
                val info = _repository.api.getMovieInfo(creds.second, creds.third, movieId)
                selectedMovieInfo = info
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDetailsLoading = false
            }
        }
    }

    fun addToHistory(media: MediaSource, episode: Episode? = null) {
        sessionManager.addToHistory(media, episode)
        val newHistory = sessionManager.getHistory()
        uiState = uiState.copy(
            history = newHistory.filter { it.categoryId in sessionManager.getSyncCategories(it.type) },
            movieCategories = uiState.movieCategories.updateHistoryInCategory(newHistory, MediaType.MOVIE),
            seriesCategories = uiState.seriesCategories.updateHistoryInCategory(newHistory, MediaType.SERIES)
        )
    }

    private fun List<GroupedMedia>.updateHistoryInCategory(history: List<MediaSource>, type: MediaType): List<GroupedMedia> {
        return this.map { group ->
            if (group.categoryId == "HISTORY") {
                group.copy(items = history.filter { it.type == type && it.categoryId in sessionManager.getSyncCategories(type) })
            } else {
                group
            }
        }
    }

    // Nya fält för app-uppdatering
    var isCheckingForAppUpdate by mutableStateOf(false)
    var appUpdateInfo by mutableStateOf<com.example.mmtv.util.UpdateInfo?>(null)
    var isAppUpToDate by mutableStateOf(false)

    fun checkForAppUpdate(context: android.content.Context) {
        viewModelScope.launch {
            isCheckingForAppUpdate = true
            isAppUpToDate = false
            val updateManager = com.example.mmtv.util.UpdateManager(context)
            // Uppdaterings-URL för macseger
            val info = updateManager.checkForUpdates("https://raw.githubusercontent.com/macseger/MMTV-Update/main/update.json")
            
            val currentVersionCode = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (e: Exception) { 0 }

            if (info != null && info.versionCode > currentVersionCode) {
                appUpdateInfo = info
            } else if (info != null) {
                isAppUpToDate = true
            }
            isCheckingForAppUpdate = false
        }
    }

    fun startAppUpdate(context: android.content.Context) {
        appUpdateInfo?.let { info ->
            com.example.mmtv.util.UpdateManager(context).downloadAndInstall(info.apkUrl)
            appUpdateInfo = null
        }
    }
}
