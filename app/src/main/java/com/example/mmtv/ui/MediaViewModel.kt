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

    var isInPipMode by mutableStateOf(false)

    fun getOrInitializePlayer(): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = playerFactory.createPlayer().apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
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
    }

    val repository: MediaRepository get() = _repository

    var uiState by mutableStateOf(MediaUiState(history = sessionManager.getHistory()))
        private set

    var loginError by mutableStateOf<String?>(null)
        private set

    private var isFetching = false
    private var lastLoadedSeriesId: Int? = null

    val channelToEpgMap = mutableStateMapOf<Int, String>()
    val fullEpgData = mutableStateMapOf<String, List<EpgListing>>()
    private val fetchingEpgIds = mutableSetOf<Int>()

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

    var updateStatus by mutableStateOf<String?>(null)
        private set
    
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
                        _dbSearchResults.value = entities.map { it.toMediaSource() }
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
            _favorites.value = favs.map { it.toMediaSource() }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val recent = mediaDao.getRecentlyAdded()
            _recentlyAdded.value = recent.map { it.toMediaSource() }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            mediaDao.deleteOldEpg(now)
        }
    }

    private fun MediaEntity.toMediaSource() = MediaSource(
        id = id,
        title = title,
        icon = icon,
        type = type,
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
        return try {
            val response = _repository.api.login(u, p)
            val success = response.userInfo?.status == "Active"
            if (success) {
                sessionManager.saveLogin(h, u, p)
            } else {
                loginError = "Ogiltiga inloggningsuppgifter eller konto inte aktivt"
            }
            success
        } catch (e: Exception) {
            loginError = "Anslutningsfel: ${e.message}"
            false
        }
    }

    fun logout() {
        sessionManager.logout()
        uiState = MediaUiState()
        fullEpgData.clear()
        channelToEpgMap.clear()
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
        isUpdatingBackground = true
        
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true)
            try {
                // 1. Hämta kategorier först (mycket snabbt)
                updateStatus = "Hämtar kategorier..."
                val liveCats = async { _repository.getJustCategories(MediaType.LIVE, user, pass, forceRefresh) }
                val movieCats = async { _repository.getJustCategories(MediaType.MOVIE, user, pass, forceRefresh) }
                val seriesCats = async { _repository.getJustCategories(MediaType.SERIES, user, pass, forceRefresh) }

                val liveData = liveCats.await()
                val movieData = movieCats.await()
                val seriesData = seriesCats.await()
                
                val ppvKeywords = listOf("TV4 Play", "Viaplay", "Svensk Hockey.tv", "Telia play")
                val ppvData = liveData.filter { cat -> 
                    ppvKeywords.any { keyword -> cat.title?.contains(keyword, ignoreCase = true) == true }
                }

                val allFavs = withContext(Dispatchers.IO) { mediaDao.getFavorites() }
                
                fun List<GroupedMedia>.withFavoritesAndHistory(type: MediaType): List<GroupedMedia> {
                    val favsForType = allFavs.filter { it.type == type }.map { it.toMediaSource() }
                    val historyForType = sessionManager.getHistory().filter { it.type == type }
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

                // SLÄPP IN ANVÄNDAREN NU!
                onComplete?.invoke(true)
                
                // 2. Fortsätt med resten i bakgrunden
                launch(Dispatchers.IO) {
                    // Lokala ikoner i bakgrunden
                    _repository.extractPiconsIfNeeded()
                    
                    val isDbEmpty = mediaDao.getCountByType(MediaType.LIVE) == 0 
                    if (forceRefresh || isDbEmpty) {
                        updateStatus = "Synkar kanaler & filmer..."
                        _repository.syncLibrary(user, pass) // syncLibrary gör även EPG
                        
                        // Ladda in items för de första kategorierna när biblioteket är redo
                        withContext(Dispatchers.Main) {
                            loadItemsForCategory(MediaType.LIVE, liveData.firstOrNull()?.categoryId)
                            if (ppvData.isNotEmpty()) {
                                loadItemsForCategory(MediaType.LIVE, ppvData.firstOrNull()?.categoryId)
                            }
                            loadItemsForCategory(MediaType.MOVIE, movieData.firstOrNull()?.categoryId)
                            loadItemsForCategory(MediaType.SERIES, seriesData.firstOrNull()?.categoryId)
                            
                            // Uppdatera Flow-data
                            _recentlyAdded.value = mediaDao.getRecentlyAdded().map { it.toMediaSource() }
                            _favorites.value = mediaDao.getFavorites().map { it.toMediaSource() }
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
                        updateStatus = "Kollar tablåer..."
                        _repository.fetchAndStoreEpg(user, pass, forceRefresh)
                    }

                    isUpdatingBackground = false
                    updateStatus = "Klart!"
                    delay(3000)
                    updateStatus = null
                }

            } catch (e: Exception) {
                uiState = uiState.copy(isLoading = false)
                isUpdatingBackground = false
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
                mediaDao.getMediaByType(MediaType.LIVE).map { it.toMediaSource() }
            } else {
                _repository.getMediaForCategory(type, categoryId)
            }
            
            // Mappa kanal-ID till EPG-ID direkt
            if (type == MediaType.LIVE) {
                items.forEach { item ->
                    item.epgId?.let { epgId -> channelToEpgMap[item.id] = epgId }
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
        }
    }

    fun toggleFavorite(media: MediaSource) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = mediaDao.getMediaById(media.id) ?: return@launch
            val newFavStatus = !entity.isFavorite
            mediaDao.updateFavoriteWithDate(media.id, newFavStatus, if (newFavStatus) System.currentTimeMillis() else 0)
            
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
                val updatedFavs = mediaDao.getFavorites().map { it.toMediaSource() }
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
                        currentItems.add(0, itemToAdd)
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
        viewModelScope.launch {
            category.items.forEach { item ->
                if (item.epgId != null && !fullEpgData.containsKey(item.epgId)) {
                    val epg = _repository.getEpgForChannel(item.epgId, item.title)
                    if (epg.isNotEmpty()) {
                        fullEpgData[item.epgId] = epg
                    }
                }
            }
        }
    }

    fun getEpgForId(id: Int, name: String? = null): EpgListing? {
        val now = System.currentTimeMillis() / 1000
        
        // 1. Försök hitta EPG-ID (från minne eller state)
        var epgId = channelToEpgMap[id]
        
        // 2. Om vi har data i cachen, använd den
        if (epgId != null) {
            val listings = fullEpgData[epgId]
            if (listings != null) {
                return listings.find { 
                    val start = it.startTimestamp ?: 0L
                    val stop = it.stopTimestamp ?: 0L
                    start <= now && stop > now 
                }
            }
        }

        // 3. Om vi saknar data, hämta från DB asynkront
        if (!fetchingEpgIds.contains(id)) {
            fetchingEpgIds.add(id)
            viewModelScope.launch {
                try {
                    // Om vi inte ens har epgId, leta upp kanalen i DB först
                    if (epgId == null) {
                        val media = _repository.getAllMediaByType(MediaType.LIVE).find { it.id == id }
                        epgId = media?.epgId
                        if (epgId != null) {
                            withContext(Dispatchers.Main) {
                                channelToEpgMap[id] = epgId!!
                            }
                        }
                    }
                    
                    if (epgId != null) {
                        val epg = _repository.getEpgForChannel(epgId!!, name)
                        if (epg.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                fullEpgData[epgId!!] = epg
                            }
                        }
                    }
                } finally {
                    fetchingEpgIds.remove(id)
                }
            }
        }
        return null
    }

    fun getNextEpgForId(id: Int, name: String? = null): EpgListing? {
        val epgId = channelToEpgMap[id]
        val listings = if (epgId != null) fullEpgData[epgId] else null
        
        if (listings == null) {
            getEpgForId(id, name) // Trigga hämtning om saknas
            return null
        }

        val now = System.currentTimeMillis() / 1000
        val current = listings.find { 
            val start = it.startTimestamp ?: 0L
            val stop = it.stopTimestamp ?: 0L
            start <= now && stop > now 
        } ?: return null
        return listings.find { it.startTimestamp == current.stopTimestamp }
    }

    fun getFullEpgForId(id: Int, name: String? = null): List<EpgListing> {
        val epgId = channelToEpgMap[id] ?: return emptyList()
        return fullEpgData[epgId] ?: run {
            viewModelScope.launch {
                val epg = _repository.getEpgForChannel(epgId, name)
                if (epg.isNotEmpty()) {
                    fullEpgData[epgId] = epg
                }
            }
            emptyList()
        }
    }

    suspend fun getIconForChannel(id: Int, name: String? = null): String? {
        val epgId = channelToEpgMap[id]
        return _repository.getIconForChannel(epgId, name)
    }

    fun refreshDataManually() {
        val creds = sessionManager.getLogin() ?: return
        fetchData(creds.second, creds.third, forceRefresh = true)
    }

    fun refreshEpgOnly() {
        viewModelScope.launch {
            val creds = sessionManager.getLogin() ?: return@launch
            isUpdatingBackground = true
            updateStatus = "Uppdaterar tablåer..."
            _repository.fetchAndStoreEpg(creds.second, creds.third, forceRefresh = true)
            fullEpgData.clear()
            isUpdatingBackground = false
            updateStatus = "Tablåer uppdaterade!"
            delay(2000)
            updateStatus = null
        }
    }

    fun performOptimization() {
        viewModelScope.launch(Dispatchers.IO) {
            isUpdatingBackground = true
            updateStatus = "Optimerar databas..."
            database.openHelper.writableDatabase.execSQL("VACUUM")
            val now = System.currentTimeMillis() / 1000
            mediaDao.deleteOldEpg(now)
            withContext(Dispatchers.Main) {
                updateStatus = "Optimering klar!"
                delay(2000)
                updateStatus = null
                isUpdatingBackground = false
            }
        }
    }

    fun extractPicons() {
        viewModelScope.launch(Dispatchers.IO) {
            isUpdatingBackground = true
            updateStatus = "Extraherar lokala ikoner..."
            _repository.extractPiconsIfNeeded()
            withContext(Dispatchers.Main) {
                updateStatus = "Ikoner extraherade!"
                delay(2000)
                updateStatus = null
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
            history = newHistory,
            movieCategories = uiState.movieCategories.updateHistoryInCategory(newHistory, MediaType.MOVIE),
            seriesCategories = uiState.seriesCategories.updateHistoryInCategory(newHistory, MediaType.SERIES)
        )
    }

    private fun List<GroupedMedia>.updateHistoryInCategory(history: List<MediaSource>, type: MediaType): List<GroupedMedia> {
        return this.map { group ->
            if (group.categoryId == "HISTORY") {
                group.copy(items = history.filter { it.type == type })
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
