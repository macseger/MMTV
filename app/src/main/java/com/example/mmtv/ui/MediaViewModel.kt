package com.example.mmtv.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mmtv.api.SessionManager
import com.example.mmtv.model.*
import com.example.mmtv.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

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

class MediaViewModel(
    private var _repository: MediaRepository, 
    private val sessionManager: SessionManager, 
    private val database: MediaDatabase,
    private val context: android.content.Context
) : ViewModel() {

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
                .filter { it.categoryId in sessionManager.getSyncCategories(it.type) }
                .map { it.toMediaSource() }

            withContext(Dispatchers.Main) {
                _favorites.value = updatedFavs
                showTvFavoritesDialog = false

                val selectedSet = orderedIds.toSet()
                val favsForLive = updatedFavs.filter { it.type == MediaType.LIVE }.sortedBy { it.favoriteDate }
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
        _favorites.value = emptyList()
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
    private val loadedFullEpgIds = ConcurrentHashMap.newKeySet<String>()
    
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

    init {
        viewModelScope.launch {
            snapshotFlow { searchQuery }.collectLatest { query ->
                if (query.length >= 2) {
                    try {
                        val entities = mediaDao.searchMedia("%$query%")
                        _dbSearchResults.value = entities.filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
                    } catch (e: Exception) {
                        _dbSearchResults.value = emptyList()
                    }
                } else {
                    _dbSearchResults.value = emptyList()
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val favs = mediaDao.getFavorites()
            _favorites.value = favs.filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val recent = mediaDao.getRecentlyAdded()
            _recentlyAdded.value = recent.filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            mediaDao.deleteOldEpg(now)
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
        fullEpgData.clear()
        channelToEpgMap.clear()
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

    fun fetchData(user: String, pass: String, forceRefresh: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        if (isFetching) return
        isFetching = true
        startupError = null
        isUpdatingBackground = true
        
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true)
            try {
                // 1. Hämta kategorier först (mycket snabbt)
                showStatusMessage("Hämtar kategorier...")
                syncCategoryOptions = loadSyncCategoryOptions(user, pass, forceRefresh)
                if (syncCategoryOptions.values.all { it.isEmpty() }) {
                    uiState = uiState.copy(isLoading = false)
                    isUpdatingBackground = false
                    startupError = "Kunde inte hämta några kategorier från servern. Kontrollera server-URL och anslutningen."
                    showStatusMessage(null)
                    onComplete?.invoke(false)
                    return@launch
                }
                if (!sessionManager.hasSyncSelection()) {
                    showSyncSelection = true
                    uiState = uiState.copy(isLoading = false)
                    isUpdatingBackground = false
                    showStatusMessage(null)
                    onComplete?.invoke(true)
                    return@launch
                }
                val liveData = syncCategoryOptions[MediaType.LIVE].orEmpty().filter { it.categoryId in sessionManager.getSyncCategories(MediaType.LIVE) }
                val movieData = syncCategoryOptions[MediaType.MOVIE].orEmpty().filter { it.categoryId in sessionManager.getSyncCategories(MediaType.MOVIE) }
                val seriesData = syncCategoryOptions[MediaType.SERIES].orEmpty().filter { it.categoryId in sessionManager.getSyncCategories(MediaType.SERIES) }
                
                val ppvKeywords = listOf("TV4 Play", "Viaplay", "Svensk Hockey.tv", "Telia play")
                val ppvData = liveData.filter { cat -> 
                    ppvKeywords.any { keyword -> cat.title?.contains(keyword, ignoreCase = true) == true }
                }

                val allFavs = withContext(Dispatchers.IO) { mediaDao.getFavorites() }
                
                fun List<GroupedMedia>.withFavoritesAndHistory(type: MediaType): List<GroupedMedia> {
                    val favsForType = allFavs.filter { it.type == type && it.categoryId in sessionManager.getSyncCategories(type) }.map { it.toMediaSource() }.let { if (type == MediaType.LIVE) it.sortedBy { item -> item.favoriteDate } else it }
                    val historyForType = sessionManager.getHistory().filter { it.type == type && it.categoryId in sessionManager.getSyncCategories(type) }
                    var result = this

                    if (type == MediaType.LIVE) {
                        result = listOf(
                            GroupedMedia(title = "📺 ALLA KANALER", categoryId = "ALL_CHANNELS", items = emptyList()),
                            GroupedMedia(title = "⭐ FAVORITER", categoryId = "FAVORITES", items = favsForType)
                        ) + result
                    } else {
                        result = listOf(
                            GroupedMedia(title = "🕒 HISTORIK", categoryId = "HISTORY", items = historyForType),
                            GroupedMedia(title = "⭐ FAVORITER", categoryId = "FAVORITES", items = favsForType)
                        ) + result
                    }
                    return result
                }

                // Uppdatera UI direkt med kategorierna
                uiState = uiState.copy(
                    liveCategories = liveData.withFavoritesAndHistory(MediaType.LIVE),
                    movieCategories = movieData.withFavoritesAndHistory(MediaType.MOVIE),
                    seriesCategories = seriesData.withFavoritesAndHistory(MediaType.SERIES),
                    ppvCategories = ppvData,
                    isLoading = false
                )

                // De två första posterna är specialkategorierna Alla kanaler och
                // Favoriter. Vid en ny appstart ska den första riktiga kategorin
                // laddas så att dess lokala EPG-cache kan återställas direkt.
                if (lastLiveCategoryIndex <= 1) {
                    lastLiveCategoryIndex = uiState.liveCategories.indexOfFirst {
                        it.categoryId == liveData.firstOrNull()?.categoryId
                    }.takeIf { it >= 0 } ?: 0
                }

                // SLÄPP IN ANVÄNDAREN NU!
                onComplete?.invoke(true)
                
                // 2. Fortsätt med resten i bakgrunden efter en kort delay för att prioritera UI-rendering
                withContext(Dispatchers.IO) {
                    delay(1500) // Ge UI:t tid att rita upp sig själv först
                    
                    // Lokala ikoner i bakgrunden
                    _repository.extractPiconsIfNeeded()
                    
                    val isDbEmpty = sessionManager.isSyncSelectionPending()
                    if (forceRefresh || isDbEmpty) {
                        showStatusMessage("Synkar valda kategorier...")
                        _repository.syncLibrary(user, pass)

                        _repository.fetchAndStoreEpg(user, pass, forceRefresh)
                        _repository.resolveLiveIcons()
                        withContext(Dispatchers.Main) {
                            fullEpgData.clear()
                            loadedFullEpgIds.clear()
                            currentEpgCache.clear()
                        }
                        
                        // Ladda in items för de första kategorierna när biblioteket är redo
                        withContext(Dispatchers.Main) {
                            loadItemsForCategory(MediaType.LIVE, liveData.firstOrNull()?.categoryId)

                            if (ppvData.isNotEmpty()) {
                                loadItemsForCategory(MediaType.LIVE, ppvData.firstOrNull()?.categoryId)
                            }
                            
                            // Om vi bara synkade live, kan vi ändå försöka ladda VOD om de fanns i DB sen innan
                            loadItemsForCategory(MediaType.MOVIE, movieData.firstOrNull()?.categoryId)
                            loadItemsForCategory(MediaType.SERIES, seriesData.firstOrNull()?.categoryId)
                            
                            // Uppdatera Flow-data
                            _recentlyAdded.value = mediaDao.getRecentlyAdded().filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
                            _favorites.value = mediaDao.getFavorites().filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
                        }
                    } else {
                        // DB inte tom, ladda in items direkt
                        withContext(Dispatchers.Main) {
                            loadItemsForCategory(MediaType.LIVE, liveData.firstOrNull()?.categoryId)

                            if (ppvData.isNotEmpty()) {
                                loadItemsForCategory(MediaType.LIVE, ppvData.firstOrNull()?.categoryId)
                            }
                            loadItemsForCategory(MediaType.MOVIE, movieData.firstOrNull()?.categoryId)
                            loadItemsForCategory(MediaType.SERIES, seriesData.firstOrNull()?.categoryId)
                        }
                        
                        // Uppdatera EPG asynkront
                        _repository.fetchAndStoreEpg(user, pass, forceRefresh)
                        withContext(Dispatchers.Main) {
                            fullEpgData.clear()
                            loadedFullEpgIds.clear()
                            currentEpgCache.clear()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        showStatusMessage("Innehållet är uppdaterat")
                        isUpdatingBackground = false
                    }
                }

            } catch (e: Exception) {
                uiState = uiState.copy(isLoading = false)
                isUpdatingBackground = false
                if (uiState.liveCategories.isEmpty()) {
                    startupError = "Servern svarade inte i tid eller kategorierna kunde inte läsas. Inga automatiska nya försök görs."
                    showStatusMessage(null)
                } else {
                    showStatusMessage("Synkningen misslyckades. Försök igen i Inställningar.")
                }
                onComplete?.invoke(false)
            } finally {
                isFetching = false
            }
        }
    }

    fun loadItemsForCategory(type: MediaType, categoryId: String?) {
        if (categoryId == null) return
        
        // Returnera tidigt för specialkategorier (Favoriter/Historik) som inte hämtas från API/DB-kategorier
        if (categoryId == "FAVORITES" || categoryId == "HISTORY") return

        viewModelScope.launch {
            val items = if (categoryId == "ALL_CHANNELS") {
                mediaDao.getMediaByType(MediaType.LIVE).filter { it.categoryId in sessionManager.getSyncCategories(MediaType.LIVE) }.map { it.toMediaSource() }
            } else {
                _repository.getMediaForCategory(type, categoryId)
            }
            
            // Mappa kanal-ID till EPG-ID direkt (Optimering: använd batch-uppdatering)
            if (type == MediaType.LIVE) {
                val newMappings = mutableMapOf<Int, String>()
                items.forEach { item ->
                    item.epgId?.let { epgId -> newMappings[item.id] = epgId }
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
            if (type == MediaType.LIVE &&
                uiState.liveCategories.getOrNull(lastLiveCategoryIndex)?.categoryId == categoryId
            ) {
                prefetchEpgForCategory(lastLiveCategoryIndex)
            }
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

                // Uppdatera favorites Flow för att trigga andra lyssnare (t.ex. sökning)
                val updatedFavs = mediaDao.getFavorites().filter { it.categoryId in sessionManager.getSyncCategories(it.type) }.map { it.toMediaSource() }
                _favorites.value = updatedFavs
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
        if (!prefetchingCategoryIds.add(categoryKey)) return

        // Förbered hela kategorin som en batch innan overlayen visas. Detta ersätter
        // per-rad-sökningar när användaren bläddrar med fjärrkontrollen.
        val itemsToPrefetch = category.items
        val epgIds = itemsToPrefetch.mapNotNull { it.epgId }.distinct()
        val missingEpgIds = epgIds.filterNot { loadedFullEpgIds.contains(it) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
            if (missingEpgIds.isNotEmpty()) {
                val epgByChannel = _repository.getEpgForChannels(missingEpgIds)
                withContext(Dispatchers.Main) {
                    fullEpgData.putAll(epgByChannel)
                    loadedFullEpgIds.addAll(missingEpgIds)
                }
            }
            } finally {
                withContext(Dispatchers.Main) {
                    prefetchingCategoryIds.remove(categoryKey)
                }
            }
        }
    }

    /** Läsning utan sidoeffekter; säker att anropa från en Composable. */
    fun getCachedFullEpgForId(id: Int): List<EpgListing> {
        val epgId = channelToEpgMap[id] ?: return emptyList()
        return fullEpgData[epgId].orEmpty()
    }

    /** Ren cacheläsning för overlayen; startar aldrig databas eller nätverk. */
    fun getCachedCurrentEpgForId(id: Int): EpgListing? {
        val now = System.currentTimeMillis() / 1000
        return getCachedFullEpgForId(id).find {
            (it.startTimestamp ?: 0) <= now && (it.stopTimestamp ?: 0) > now
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
            if (now < (cached.stopTimestamp ?: 0L)) return cached
        }

        // 2. Försök hitta EPG-ID och data i SnapshotStateMap
        val epgId = channelToEpgMap[id]
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
                            }
                        }
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
                    _repository.fetchAndStoreEpg(creds.second, creds.third)
                    _repository.resolveLiveIcons()
                    withContext(Dispatchers.Main) {
                        fullEpgData.clear()
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
                _repository.fetchAndStoreEpg(creds.second, creds.third, forceRefresh = true)
                fullEpgData.clear()
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
