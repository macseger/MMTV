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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

data class MediaUiState(
    val liveCategories: List<GroupedMedia> = emptyList(),
    val movieCategories: List<GroupedMedia> = emptyList(),
    val seriesCategories: List<GroupedMedia> = emptyList(),
    val isLoading: Boolean = true,
    val history: List<MediaSource> = emptyList()
) {
    val liveStreamsGrouped get() = liveCategories
    val movies get() = movieCategories
    val series get() = seriesCategories
}

class MediaViewModel(private var _repository: MediaRepository, private val sessionManager: SessionManager, private val database: MediaDatabase) : ViewModel() {

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
    var lastMovieCategoryIndex by mutableIntStateOf(0)
    var lastSeriesCategoryIndex by mutableIntStateOf(0)

    var selectedMedia by mutableStateOf<MediaSource?>(null)
    var playingEpisode by mutableStateOf<Episode?>(null)
    var currentPlaylist by mutableStateOf<List<MediaSource>>(emptyList())

    var selectedSeriesInfo by mutableStateOf<SeriesInfoResponse?>(null)
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
                updateStatus = "Extraherar lokala ikoner..."
                withContext(Dispatchers.IO) {
                    _repository.extractPiconsIfNeeded()
                }

                updateStatus = "Verifierar konto..."
                
                val isDbEmpty = withContext(Dispatchers.IO) { 
                    mediaDao.getCountByType(MediaType.LIVE) == 0 
                }
                
                if (forceRefresh || isDbEmpty) {
                    updateStatus = "Hämtar kanaler & filmer..."
                    withContext(Dispatchers.IO) {
                        _repository.syncLibrary(user, pass)
                    }
                }

                updateStatus = "Kollar tablåer..."
                val epgJob = launch { 
                    _repository.fetchAndStoreEpg(user, pass, forceRefresh) 
                }

                val liveCats = async { _repository.getJustCategories(MediaType.LIVE, user, pass, forceRefresh) }
                val movieCats = async { _repository.getJustCategories(MediaType.MOVIE, user, pass, forceRefresh) }
                val seriesCats = async { _repository.getJustCategories(MediaType.SERIES, user, pass, forceRefresh) }

                val liveData = liveCats.await()
                val movieData = movieCats.await()
                val seriesData = seriesCats.await()
                
                val allFavs = withContext(Dispatchers.IO) { mediaDao.getFavorites() }
                
                fun List<GroupedMedia>.withFavoritesAndHistory(type: MediaType): List<GroupedMedia> {
                    val favsForType = allFavs.filter { it.type == type }.map { it.toMediaSource() }
                    var result = if (favsForType.isNotEmpty()) {
                        listOf(GroupedMedia(title = "⭐ FAVORITER", items = favsForType)) + this
                    } else this

                    if (type != MediaType.LIVE) {
                        val historyForType = sessionManager.getHistory().filter { it.type == type }
                        result = listOf(GroupedMedia(title = "🕒 HISTORIK", items = historyForType)) + result
                    }
                    return result
                }

                uiState = uiState.copy(
                    liveCategories = liveData.withFavoritesAndHistory(MediaType.LIVE),
                    movieCategories = movieData.withFavoritesAndHistory(MediaType.MOVIE),
                    seriesCategories = seriesData.withFavoritesAndHistory(MediaType.SERIES),
                    isLoading = false
                )
                
                loadItemsForCategory(MediaType.LIVE, liveData.firstOrNull()?.categoryId)
                loadItemsForCategory(MediaType.MOVIE, movieData.firstOrNull()?.categoryId)
                loadItemsForCategory(MediaType.SERIES, seriesData.firstOrNull()?.categoryId)

                epgJob.join()
                isUpdatingBackground = false
                updateStatus = "Klart!"
                onComplete?.invoke(true)
                delay(3000)
                updateStatus = null

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
        viewModelScope.launch {
            val items = _repository.getMediaForCategory(type, categoryId)
            
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
                MediaType.LIVE -> uiState.copy(liveCategories = uiState.liveCategories.map { 
                    if (it.categoryId == categoryId) it.copy(items = items) else it 
                })
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
                uiState = uiState.copy(
                    liveCategories = uiState.liveCategories.updateItemFav(media.id, newFavStatus),
                    movieCategories = uiState.movieCategories.updateItemFav(media.id, newFavStatus),
                    seriesCategories = uiState.seriesCategories.updateItemFav(media.id, newFavStatus)
                )
            }
        }
    }

    private fun List<GroupedMedia>.updateItemFav(id: Int, isFav: Boolean): List<GroupedMedia> {
        return this.map { group ->
            group.copy(items = group.items.map { if (it.id == id) it.copy(isFavorite = isFav) else it })
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
                    liveCategories = uiState.liveCategories.map { g -> g.copy(items = g.items.map { it.copy(isFavorite = false) }) },
                    movieCategories = uiState.movieCategories.map { g -> g.copy(items = g.items.map { it.copy(isFavorite = false) }) },
                    seriesCategories = uiState.seriesCategories.map { g -> g.copy(items = g.items.map { it.copy(isFavorite = false) }) }
                )
            }
        }
    }

    fun fetchSeriesDetails(seriesId: Int) {
        if (lastLoadedSeriesId == seriesId) return
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
        uiState = uiState.copy(history = emptyList())
    }

    fun clearAllFavorites() {
        deleteFavorites()
    }

    fun loadSeriesInfo(seriesId: Int) {
        fetchSeriesDetails(seriesId)
    }

    fun addToHistory(media: MediaSource, episode: Episode? = null) {
        sessionManager.addToHistory(media, episode)
        uiState = uiState.copy(history = sessionManager.getHistory())
    }
}
