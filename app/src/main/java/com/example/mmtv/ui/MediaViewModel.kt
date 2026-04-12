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
import kotlinx.coroutines.runBlocking

import com.example.mmtv.database.MediaDatabase
import com.example.mmtv.database.MediaEntity
import com.example.mmtv.database.MediaDao
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
    // Aliases for MainActivity
    val liveStreamsGrouped get() = liveCategories
    val movies get() = movieCategories
    val series get() = seriesCategories
}

class MediaViewModel(private var repository: MediaRepository, private val sessionManager: SessionManager, private val database: MediaDatabase) : ViewModel() {

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
    var currentPlaylist by mutableStateOf<List<MediaSource>>(emptyList())

    var selectedSeriesInfo by mutableStateOf<SeriesInfoResponse?>(null)
    var isDetailsLoading by mutableStateOf(false)

    var searchQuery by mutableStateOf("")

    var updateStatus by mutableStateOf<String?>(null)
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
        
        // Ladda favoriter
        viewModelScope.launch(Dispatchers.IO) {
            val favs = mediaDao.getFavorites()
            _favorites.value = favs.map { it.toMediaSource() }
        }
        
        // Ladda senast tillagda
        viewModelScope.launch(Dispatchers.IO) {
            val recent = mediaDao.getRecentlyAdded()
            _recentlyAdded.value = recent.map { it.toMediaSource() }
        }
        
        // Automatiskt underhåll av EPG-databasen (ta bort gammal data vid start)
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
        addedDate = addedDate
    )

    private fun MediaSource.toEntity(catId: String?, catName: String?) = MediaEntity(
        id = id,
        title = title ?: "",
        icon = icon,
        type = type,
        categoryId = catId,
        categoryName = catName,
        extension = extension,
        plot = plot,
        rating = rating,
        director = director,
        genre = genre,
        cast = cast,
        epgId = epgId,
        isFavorite = isFavorite,
        addedDate = addedDate
    )

    fun updateRepository(newRepository: MediaRepository) {
        this.repository = newRepository
    }

    suspend fun login(h: String, u: String, p: String): Boolean {
        loginError = null
        return try {
            val response = repository.api.login(u, p)
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

    fun fetchData(user: String, pass: String, forceRefresh: Boolean = false) {
        if (isFetching) return
        isFetching = true
        
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true)
            try {
                // Hämta EPG asynkront
                launch { repository.fetchAndStoreEpg(user, pass, forceRefresh) }

                val live = async { repository.getGroupedLive(user, pass, forceRefresh) }
                val movies = async { repository.getGroupedMovies(user, pass, forceRefresh) }
                val series = async { repository.getGroupedSeries(user, pass, forceRefresh) }

                val liveData = live.await()
                val movieData = movies.await()
                val seriesData = series.await()
                
                // Mappa stream_id till epg_channel_id för snabbare lookup
                liveData.forEach { group ->
                    group.items.forEach { item ->
                        item.epgId?.let { epgId ->
                            channelToEpgMap[item.id] = epgId
                        }
                    }
                }

                // Hämta favoriter från DB för att bygga favoritkategorier
                val allFavs = withContext(Dispatchers.IO) { mediaDao.getFavorites().map { it.toMediaSource() } }
                
                fun List<GroupedMedia>.withFavorites(type: MediaType): List<GroupedMedia> {
                    var favsForType = allFavs.filter { it.type == type }
                    // För TV vill vi ha nyaste sist (omvänd kronologisk i DB -> kronologisk här)
                    if (type == MediaType.LIVE) {
                        favsForType = favsForType.reversed()
                    }
                    
                    val listWithFavs = if (favsForType.isNotEmpty()) {
                        listOf(GroupedMedia(title = "⭐ FAVORITER", items = favsForType)) + this
                    } else this

                    // Lägg till historik för filmer och serier (men inte TV)
                    return if (type != MediaType.LIVE) {
                        val historyForType = sessionManager.getHistory().filter { it.type == type }
                        if (historyForType.isNotEmpty()) {
                            listOf(GroupedMedia(title = "🕒 SENAST SEDDA", items = historyForType)) + listWithFavs
                        } else listWithFavs
                    } else listWithFavs
                }

                uiState = uiState.copy(
                    liveCategories = liveData.withFavorites(MediaType.LIVE),
                    movieCategories = movieData.withFavorites(MediaType.MOVIE),
                    seriesCategories = seriesData.withFavorites(MediaType.SERIES),
                    isLoading = false,
                    history = sessionManager.getHistory()
                )
                
                // Uppdatera nyligen tillagda och favoriter från DB
                withContext(Dispatchers.IO) {
                    val recent = mediaDao.getRecentlyAdded()
                    _recentlyAdded.value = recent.map { it.toMediaSource() }
                    val favs = mediaDao.getFavorites()
                    _favorites.value = favs.map { it.toMediaSource() }
                }

            } catch (e: Exception) {
                uiState = uiState.copy(isLoading = false)
            } finally {
                isFetching = false
            }
        }
    }

    fun toggleFavorite(media: MediaSource) {
        viewModelScope.launch(Dispatchers.IO) {
            val newFavStatus = !media.isFavorite
            val favDate = if (newFavStatus) System.currentTimeMillis() else 0L
            mediaDao.updateFavoriteWithDate(media.id, newFavStatus, favDate)
            
            // Uppdatera favoritlistan direkt
            val updatedFavs = mediaDao.getFavorites().map { it.toMediaSource() }
            _favorites.value = updatedFavs
            
            // Om det är en serie som vi precis laddat info för, uppdatera även den
            if (selectedMedia?.id == media.id) {
                selectedMedia = selectedMedia?.copy(isFavorite = newFavStatus)
            }
            
            withContext(Dispatchers.Main) {
                refreshLists()
            }
        }
    }

    fun loadSeriesInfo(seriesId: Int) {
        if (lastLoadedSeriesId == seriesId) return
        
        val creds = sessionManager.getLogin()
        if (creds == null) return

        viewModelScope.launch {
            isDetailsLoading = true
            try {
                selectedSeriesInfo = repository.getSeriesInfo(creds.second, creds.third, seriesId)
                lastLoadedSeriesId = seriesId
            } catch (e: Exception) {
                selectedSeriesInfo = null
            } finally {
                isDetailsLoading = false
            }
        }
    }

    suspend fun getEpgForId(streamId: Int, channelName: String? = null): EpgListing? = withContext(Dispatchers.IO) {
        val epgId = channelToEpgMap[streamId]
        val now = System.currentTimeMillis() / 1000
        
        // Kolla cachen först
        if (epgId != null) {
            val cached = fullEpgData[epgId]
            if (cached != null) {
                return@withContext cached.find { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) }
            }
        }
        
        // Annars hämta från repository (som kollar DB/API)
        val fullEpg = repository.getEpgForChannel(epgId, channelName)
        if (fullEpg.isNotEmpty()) {
            val finalEpgId = epgId ?: fullEpg.first().epgId ?: channelName ?: "unknown"
            withContext(Dispatchers.Main) {
                fullEpgData[finalEpgId] = fullEpg
            }
            return@withContext fullEpg.find { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) }
        }
        null
    }

    suspend fun getNextEpgForId(streamId: Int, channelName: String? = null): EpgListing? = withContext(Dispatchers.IO) {
        val epgId = channelToEpgMap[streamId]
        val now = System.currentTimeMillis() / 1000
        
        var channelList = epgId?.let { fullEpgData[it] }
        if (channelList == null) {
             val dbEpg = repository.getEpgForChannel(epgId, channelName)
             if (dbEpg.isNotEmpty()) {
                 val finalEpgId = epgId ?: dbEpg.first().epgId ?: channelName ?: "unknown"
                 withContext(Dispatchers.Main) {
                    fullEpgData[finalEpgId] = dbEpg
                 }
                 channelList = dbEpg
             }
        }
        
        val currentIndex = channelList?.indexOfFirst { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) } ?: -1
        if (currentIndex != -1) channelList?.getOrNull(currentIndex + 1) else null
    }

    suspend fun getFullEpgForId(streamId: Int, channelName: String? = null): List<EpgListing> = withContext(Dispatchers.IO) {
        val epgId = channelToEpgMap[streamId]
        var epgData = epgId?.let { fullEpgData[it] }
        if (epgData == null) {
            val dbEpg = repository.getEpgForChannel(epgId, channelName)
            if (dbEpg.isNotEmpty()) {
                val finalEpgId = epgId ?: dbEpg.first().epgId ?: channelName ?: "unknown"
                withContext(Dispatchers.Main) {
                    fullEpgData[finalEpgId] = dbEpg
                }
                epgData = dbEpg
            }
        }
        epgData ?: emptyList()
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

    fun loadData(user: String, pass: String, host: String? = null, forceRefresh: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        fetchData(user, pass, forceRefresh)
        onComplete?.invoke(true)
    }

    fun addToHistory(media: MediaSource) {
        sessionManager.addToHistory(media)
        uiState = uiState.copy(history = sessionManager.getHistory())
    }

    suspend fun getIconForChannel(epgId: String?, name: String?): String? {
        return repository.getIconForChannel(epgId, name)
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

    fun refreshDataManually() {
        refreshLists()
    }

    fun clearAllFavorites() {
        viewModelScope.launch {
            mediaDao.clearAllFavorites()
            refreshLists()
        }
    }
}
