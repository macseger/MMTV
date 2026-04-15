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

    var uiState by mutableStateOf(MediaUiState())
        private set

    var loginError by mutableStateOf<String?>(null)
        private set

    private var isFetching = false
    private var lastLoadedSeriesId: Int? = null

    private val channelToEpgMap = mutableMapOf<Int, String>()
    private val fullEpgData = mutableStateMapOf<String, List<EpgListing>>()

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

    fun fetchData(user: String, pass: String, forceRefresh: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        if (isFetching) return
        isFetching = true
        isUpdatingBackground = true
        
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true)
            try {
                // 1. Synka biblioteket endast om det är forceRefresh eller om databasen är tom
                val isDbEmpty = withContext(Dispatchers.IO) { 
                    mediaDao.getCountByType(MediaType.LIVE) == 0 
                }
                
                if (forceRefresh || isDbEmpty) {
                    updateStatus = "Synkar bibliotek..."
                    withContext(Dispatchers.IO) {
                        _repository.syncLibrary(user, pass)
                    }
                }

                // 2. Starta EPG-uppdatering i bakgrunden (behöver inte blockera UI-laddning av kategorier)
                val epgJob = launch { 
                    _repository.fetchAndStoreEpg(user, pass, forceRefresh) 
                }

                // 3. Ladda kategorierna från den nyss uppdaterade databasen
                val liveCats = async { _repository.getJustCategories(MediaType.LIVE, user, pass, forceRefresh) }
                val movieCats = async { _repository.getJustCategories(MediaType.MOVIE, user, pass, forceRefresh) }
                val seriesCats = async { _repository.getJustCategories(MediaType.SERIES, user, pass, forceRefresh) }

                val liveData = liveCats.await()
                val movieData = movieCats.await()
                val seriesData = seriesCats.await()
                
                val allFavs = withContext(Dispatchers.IO) { mediaDao.getFavorites().map { it.toMediaSource() } }
                
                fun List<GroupedMedia>.withFavoritesAndHistory(type: MediaType): List<GroupedMedia> {
                    val favsForType = allFavs.filter { it.type == type }.sortedByDescending { it.favoriteDate }
                    
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
                    seriesCategories = seriesData.withFavoritesAndHistory(MediaType.SERIES)
                )

                withContext(Dispatchers.Main) {
                    fun List<GroupedMedia>.updateFavoritesAndHistory(type: MediaType): List<GroupedMedia> {
                        val favsForType = allFavs.filter { it.type == type }.sortedByDescending { it.favoriteDate }
                        val historyForType = sessionManager.getHistory().filter { it.type == type }
                        val filtered = this.filterNot { it.title == "⭐ FAVORITER" || it.title == "🕒 HISTORIK" }
                        var result = filtered
                        if (type != MediaType.LIVE) {
                            result = listOf(GroupedMedia(title = "🕒 HISTORIK", items = historyForType)) + result
                        }
                        if (favsForType.isNotEmpty()) {
                            result = listOf(GroupedMedia(title = "⭐ FAVORITER", items = favsForType)) + result
                        }
                        return result
                    }

                    uiState = uiState.copy(
                        liveCategories = uiState.liveCategories.updateFavoritesAndHistory(MediaType.LIVE),
                        movieCategories = uiState.movieCategories.updateFavoritesAndHistory(MediaType.MOVIE),
                        seriesCategories = uiState.seriesCategories.updateFavoritesAndHistory(MediaType.SERIES),
                        isLoading = false,
                        history = sessionManager.getHistory()
                    )
                }
                
                loadItemsForCategory(MediaType.LIVE, liveData.firstOrNull()?.categoryId)
                loadItemsForCategory(MediaType.MOVIE, movieData.firstOrNull()?.categoryId)
                loadItemsForCategory(MediaType.SERIES, seriesData.firstOrNull()?.categoryId)

                withContext(Dispatchers.IO) {
                    val recent = mediaDao.getRecentlyAdded()
                    _recentlyAdded.value = recent.map { it.toMediaSource() }
                    val favs = mediaDao.getFavorites()
                    _favorites.value = favs.map { it.toMediaSource() }
                }

                epgJob.join()
                isUpdatingBackground = false
                updateStatus = "Kategorier & EPG uppdaterad!"
                onComplete?.invoke(true)
                delay(4000)
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
            if (type == MediaType.LIVE) {
                items.forEach { item ->
                    item.epgId?.let { epgId -> channelToEpgMap[item.id] = epgId }
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
            if (type == MediaType.LIVE) {
                val currentCategory = uiState.liveCategories.getOrNull(lastLiveCategoryIndex)
                if (currentCategory?.categoryId == categoryId) {
                    currentPlaylist = items
                }
                items.take(15).forEach { item ->
                    launch(Dispatchers.IO) { getEpgForId(item.id, item.title) }
                }
            }
        }
    }

    fun toggleFavorite(media: MediaSource) {
        viewModelScope.launch(Dispatchers.IO) {
            val newFavStatus = !media.isFavorite
            val favDate = if (newFavStatus) System.currentTimeMillis() else 0L
            mediaDao.updateFavoriteWithDate(media.id, newFavStatus, favDate)
            val updatedFavs = mediaDao.getFavorites().map { it.toMediaSource() }
            _favorites.value = updatedFavs
            if (selectedMedia?.id == media.id) {
                selectedMedia = selectedMedia?.copy(isFavorite = newFavStatus)
            }
            withContext(Dispatchers.Main) {
                val allFavs = mediaDao.getFavorites().map { it.toMediaSource() }
                fun List<GroupedMedia>.updateFavoritesAndHistory(type: MediaType): List<GroupedMedia> {
                    val favsForType = allFavs.filter { it.type == type }.sortedByDescending { it.favoriteDate }
                    val historyForType = sessionManager.getHistory().filter { it.type == type }
                    val filtered = this.filterNot { it.title == "⭐ FAVORITER" || it.title == "🕒 HISTORIK" }
                    var result = filtered
                    if (type != MediaType.LIVE) {
                        result = listOf(GroupedMedia(title = "🕒 HISTORIK", items = historyForType)) + result
                    }
                    if (favsForType.isNotEmpty()) {
                        result = listOf(GroupedMedia(title = "⭐ FAVORITER", items = favsForType)) + result
                    }
                    return result
                }
                uiState = uiState.copy(
                    liveCategories = uiState.liveCategories.updateFavoritesAndHistory(MediaType.LIVE),
                    movieCategories = uiState.movieCategories.updateFavoritesAndHistory(MediaType.MOVIE),
                    seriesCategories = uiState.seriesCategories.updateFavoritesAndHistory(MediaType.SERIES)
                )
            }
        }
    }

    fun loadSeriesInfo(seriesId: Int) {
        if (lastLoadedSeriesId == seriesId && selectedSeriesInfo != null) return
        val creds = sessionManager.getLogin() ?: return
        viewModelScope.launch {
            if (lastLoadedSeriesId != seriesId) {
                selectedSeriesInfo = null
            }
            isDetailsLoading = true
            try {
                val info = _repository.getSeriesInfo(creds.second, creds.third, seriesId)
                if (info.episodes != null && info.episodes.isNotEmpty()) {
                    selectedSeriesInfo = info
                    lastLoadedSeriesId = seriesId
                } else {
                    selectedSeriesInfo = null
                    lastLoadedSeriesId = null
                }
            } catch (e: Exception) {
                selectedSeriesInfo = null
                lastLoadedSeriesId = null
            } finally {
                isDetailsLoading = false
            }
        }
    }

    suspend fun getEpgForId(streamId: Int, channelName: String? = null, epgId: String? = null): EpgListing? = withContext(Dispatchers.IO) {
        val finalEpgId = epgId ?: channelToEpgMap[streamId]
        val now = System.currentTimeMillis() / 1000
        
        // 1. Kolla cache först
        if (finalEpgId != null) {
            val cached = fullEpgData[finalEpgId]
            if (cached != null) {
                val found = cached.find { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) }
                if (found != null) return@withContext found
            }
        }

        // 2. Hämta från databas/repository
        val fullEpg = _repository.getEpgForChannel(finalEpgId, channelName)
        if (fullEpg.isNotEmpty()) {
            val actualKey = finalEpgId ?: channelName ?: "unknown"
            withContext(Dispatchers.Main) {
                if (fullEpgData.size > 100) {
                    fullEpgData.clear() // Rensa om det blir för mycket
                }
                fullEpgData[actualKey] = fullEpg
            }
            return@withContext fullEpg.find { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) }
        }
        null
    }

    suspend fun getFullEpgForId(streamId: Int, channelName: String? = null, epgId: String? = null): List<EpgListing> = withContext(Dispatchers.IO) {
        val finalEpgId = epgId ?: channelToEpgMap[streamId]
        if (finalEpgId != null) {
            val cached = fullEpgData[finalEpgId]
            if (cached != null) return@withContext cached
        }
        
        val fullEpg = _repository.getEpgForChannel(finalEpgId, channelName)
        if (fullEpg.isNotEmpty()) {
            val actualEpgId = finalEpgId ?: fullEpg.first().epgId ?: channelName ?: "unknown"
            withContext(Dispatchers.Main) {
                if (fullEpgData.size > 50) {
                    val firstKey = fullEpgData.keys.first()
                    fullEpgData.remove(firstKey)
                }
                fullEpgData[actualEpgId] = fullEpg
            }
        }
        fullEpg
    }

    suspend fun getNextEpgForId(streamId: Int, channelName: String? = null, epgId: String? = null): EpgListing? = withContext(Dispatchers.IO) {
        val finalEpgId = epgId ?: channelToEpgMap[streamId]
        val now = System.currentTimeMillis() / 1000
        var channelList = finalEpgId?.let { fullEpgData[it] }
        if (channelList == null) {
             val dbEpg = _repository.getEpgForChannel(finalEpgId, channelName)
             if (dbEpg.isNotEmpty()) {
                 val actualEpgId = finalEpgId ?: dbEpg.first().epgId ?: channelName ?: "unknown"
                 withContext(Dispatchers.Main) {
                    if (fullEpgData.size > 50) {
                        val firstKey = fullEpgData.keys.first()
                        fullEpgData.remove(firstKey)
                    }
                    fullEpgData[actualEpgId] = dbEpg
                 }
                 channelList = dbEpg
             }
        }
        val currentIndex = channelList?.indexOfFirst { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) } ?: -1
        if (currentIndex != -1) channelList?.getOrNull(currentIndex + 1) else null
    }

    fun refreshDataManually() {
        val login = sessionManager.getLogin() ?: return
        fetchData(login.second, login.third, true)
    }

    fun clearAllFavorites() {
        viewModelScope.launch(Dispatchers.IO) {
            mediaDao.clearAllFavorites()
            refreshLists()
            withContext(Dispatchers.Main) {
                updateStatus = "Alla favoriter har rensats!"
                delay(3000)
                updateStatus = null
            }
        }
    }

    fun performOptimization() {
        val login = sessionManager.getLogin() ?: return
        viewModelScope.launch {
            _repository.performInitialProvisioning(login.second, login.third) { status ->
                updateStatus = status
            }
            fetchData(login.second, login.third, true)
            updateStatus = "Optimering klar!"
            delay(3000)
            updateStatus = null
        }
    }

    fun refreshEpgOnly() {
        val login = sessionManager.getLogin() ?: return
        viewModelScope.launch {
            isUpdatingBackground = true
            updateStatus = "Uppdaterar EPG..."
            try {
                _repository.fetchAndStoreEpg(login.second, login.third, forceRefresh = true)
                fullEpgData.clear()
                updateStatus = "EPG Uppdaterad!"
            } catch (e: Exception) {
                updateStatus = "Fel vid EPG-uppdatering"
            } finally {
                delay(3000)
                updateStatus = null
                isUpdatingBackground = false
            }
        }
    }

    fun clearHistory() {
        sessionManager.clearHistory()
        uiState = uiState.copy(history = emptyList())
        refreshLists()
        viewModelScope.launch {
            updateStatus = "Historiken har rensats!"
            delay(3000)
            updateStatus = null
        }
    }

    fun loadData(user: String, pass: String, forceRefresh: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        fetchData(user, pass, forceRefresh, onComplete)
    }

    fun addToHistory(media: MediaSource, episode: Episode? = null) {
        val mediaToSave = if (media.type == MediaType.SERIES && episode != null) {
            media.copy(plot = "S${episode.seasonNumber}E${episode.id}: ${episode.title}")
        } else media
        sessionManager.addToHistory(mediaToSave)
        val updatedHistory = sessionManager.getHistory()
        fun List<GroupedMedia>.updateHistory(type: MediaType): List<GroupedMedia> {
            val historyForType = updatedHistory.filter { it.type == type }
            return this.map { 
                if (it.title == "🕒 HISTORIK") it.copy(items = historyForType) else it
            }
        }
        uiState = uiState.copy(
            history = updatedHistory,
            liveCategories = uiState.liveCategories.updateHistory(MediaType.LIVE),
            movieCategories = uiState.movieCategories.updateHistory(MediaType.MOVIE),
            seriesCategories = uiState.seriesCategories.updateHistory(MediaType.SERIES)
        )
    }

    suspend fun getIconForChannel(epgId: String?, name: String?): String? {
        return _repository.getIconForChannel(epgId, name)
    }

    fun setLiveCategoryByMediaId(mediaId: Int) {
        uiState.liveCategories.forEachIndexed { index, group ->
            if (group.items.any { it.id == mediaId }) {
                lastLiveCategoryIndex = index
                return
            }
        }
    }

    fun prefetchEpgForCategory(categoryIndex: Int) {
        val category = uiState.liveCategories.getOrNull(categoryIndex) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            category.items.take(20).forEach { item ->
                getEpgForId(item.id, item.title)
            }
        }
    }
}
