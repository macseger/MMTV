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

import com.example.mmtv.database.MediaDatabase
import com.example.mmtv.database.MediaEntity
import com.example.mmtv.database.MediaDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

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

    private val mediaDao = database.mediaDao()
    private val _dbSearchResults = MutableStateFlow<List<MediaSource>>(emptyList())
    val dbSearchResults: StateFlow<List<MediaSource>> = _dbSearchResults.asStateFlow()

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
        epgId = epgId
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
        epgId = epgId
    )

    fun updateRepository(newRepository: MediaRepository) {
        this.repository = newRepository
    }

    fun loadData(user: String, pass: String, host: String, forceRefresh: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        if (isFetching && !forceRefresh) return
        
        loginError = null
        // Om vi redan har data i minnet och inte tvingar refresh, gör ingenting.
        if (!forceRefresh && uiState.liveStreamsGrouped.isNotEmpty()) {
            onResult?.invoke(true)
            return
        }

        isFetching = true
        viewModelScope.launch {
            uiState = uiState.copy(username = user, password = pass, baseUrl = host)
            
            try {
                // 1. Försök ladda från DB OMEDELBART för att få bort Splash Screen snabbt
                if (!forceRefresh) {
                    val cachedLive = withContext(Dispatchers.IO) {
                        repository.getGroupedLive(user, pass, forceRefresh = false)
                    }
                    val history = sessionManager.getHistory()
                    if (cachedLive.isNotEmpty()) {
                        val filteredLive = withContext(Dispatchers.Default) {
                            addHistoryCategory(applyFiltersAndOrder("live", cachedLive), history, MediaType.LIVE)
                        }
                        uiState = uiState.copy(liveStreamsGrouped = filteredLive, history = history, isLoading = false)
                        
                        // Kartlägg EPG-ID:n
                        cachedLive.flatMap { it.items }.forEach { 
                            if (it.epgId != null) channelToEpgMap[it.id] = it.epgId 
                        }
                        
                        // Ladda EPG för den första synliga kategorin direkt
                        prefetchEpgForCategory(0)
                        onResult?.invoke(true)
                    }
                }

                // 2. Starta nätverksuppdatering i bakgrunden (eller prioriterad om DB var tom)
                val liveJob = async(Dispatchers.IO) {
                    val dbCount = withContext(Dispatchers.IO) { database.mediaDao().getCountByType(MediaType.LIVE) }
                    if (forceRefresh || dbCount == 0) {
                        repository.getGroupedLive(user, pass, forceRefresh)
                    } else {
                        null
                    }
                }

                launch(Dispatchers.IO) {
                    loadEpgInBackground(user, pass, forceRefresh)
                }

                val newLive = liveJob.await()
                if (newLive != null) {
                    if (newLive.isEmpty()) {
                         loginError = "Kunde inte hämta data. Kontrollera uppgifter och server."
                         onResult?.invoke(false)
                    } else {
                        val history = sessionManager.getHistory()
                        val filteredLive = withContext(Dispatchers.Default) {
                            addHistoryCategory(applyFiltersAndOrder("live", newLive), history, MediaType.LIVE)
                        }
                        uiState = uiState.copy(
                            liveStreamsGrouped = filteredLive,
                            history = history,
                            isLoading = false
                        )
                        
                        newLive.flatMap { it.items }.forEach { 
                            if (it.epgId != null) channelToEpgMap[it.id] = it.epgId 
                        }
                        onResult?.invoke(true)
                    }
                } else if (uiState.liveStreamsGrouped.isNotEmpty()) {
                    onResult?.invoke(true)
                }

                // Ladda Film och Serier asynkront
                launch(Dispatchers.IO) {
                    try {
                        val movies = repository.getGroupedMovies(user, pass, forceRefresh)
                        val history = sessionManager.getHistory()
                        val filteredMovies = withContext(Dispatchers.Default) {
                            val sortedMovies = movies.filter { it.items.isNotEmpty() }.map { group ->
                                group.copy(items = group.items.sortedByDescending { it.id })
                            }
                            addHistoryCategory(applyFiltersAndOrder("movies", sortedMovies), history, MediaType.MOVIE)
                        }
                        withContext(Dispatchers.Main) {
                            uiState = uiState.copy(movies = filteredMovies)
                        }
                    } catch (e: Exception) {}
                }

                launch(Dispatchers.IO) {
                    try {
                        val series = repository.getGroupedSeries(user, pass, forceRefresh)
                        val history = sessionManager.getHistory()
                        val filteredSeries = withContext(Dispatchers.Default) {
                            val sortedSeries = series.filter { it.items.isNotEmpty() }.map { group ->
                                group.copy(items = group.items.sortedByDescending { it.id })
                            }
                            addHistoryCategory(applyFiltersAndOrder("series", sortedSeries), history, MediaType.SERIES)
                        }
                        withContext(Dispatchers.Main) {
                            uiState = uiState.copy(series = filteredSeries)
                        }
                    } catch (e: Exception) {}
                }

            } catch (e: Exception) {
                loginError = "Anslutningsfel: ${e.message}"
                uiState = uiState.copy(error = e.message, isLoading = false)
                onResult?.invoke(false)
            } finally {
                isFetching = false
            }
        }
    }

    private fun addHistoryCategory(list: List<GroupedMedia>, history: List<MediaSource>, type: MediaType): List<GroupedMedia> {
        val relevantHistory = history.filter { it.type == type }
        if (relevantHistory.isEmpty()) return list
        return listOf(GroupedMedia(title = "Historik", items = relevantHistory)) + list
    }

    private fun applyFiltersAndOrder(type: String, list: List<GroupedMedia>): List<GroupedMedia> {
        val hiddenCategories = sessionManager.getHiddenCategories(type)
        val filteredList = list.filter { it.title !in hiddenCategories }
        val savedOrder = sessionManager.getCategoryOrder(type)
        if (savedOrder.isEmpty()) return filteredList
        val orderedList = mutableListOf<GroupedMedia>()
        val remainingList = filteredList.toMutableList()
        savedOrder.forEach { title ->
            val found = remainingList.find { it.title == title }
            if (found != null) {
                orderedList.add(found)
                remainingList.remove(found)
            }
        }
        orderedList.addAll(remainingList)
        return orderedList
    }

    fun refreshDataManually() {
        loadData(uiState.username, uiState.password, uiState.baseUrl, forceRefresh = true)
    }

    fun hideCategory(type: String, categoryTitle: String) {
        sessionManager.hideCategory(type, categoryTitle)
        refreshLists()
    }

    fun showAllCategories() {
        sessionManager.clearHiddenCategories()
        refreshLists()
    }

    fun refreshLists() {
        viewModelScope.launch {
            val live = repository.getGroupedLive(uiState.username, uiState.password)
            val movies = repository.getGroupedMovies(uiState.username, uiState.password)
            val series = repository.getGroupedSeries(uiState.username, uiState.password)
            val history = sessionManager.getHistory()
            
            val sortedMovies = movies.map { it.copy(items = it.items.sortedByDescending { m -> m.id }) }
            val sortedSeries = series.map { it.copy(items = it.items.sortedByDescending { s -> s.id }) }

            uiState = uiState.copy(
                liveStreamsGrouped = addHistoryCategory(applyFiltersAndOrder("live", live), history, MediaType.LIVE),
                movies = addHistoryCategory(applyFiltersAndOrder("movies", sortedMovies), history, MediaType.MOVIE),
                series = addHistoryCategory(applyFiltersAndOrder("series", sortedSeries), history, MediaType.SERIES),
                history = history
            )
            // Trigger prefetch after refresh
            prefetchEpgForCategory(0)
        }
    }

    fun addToHistory(media: MediaSource) {
        sessionManager.addToHistory(media)
        refreshLists()
    }

    fun loadSeriesInfo(seriesId: Int) {
        viewModelScope.launch {
            isDetailsLoading = true
            try {
                selectedSeriesInfo = repository.getSeriesInfo(uiState.username, uiState.password, seriesId)
            } catch (e: Exception) {} finally {
                isDetailsLoading = false
            }
        }
    }

    fun getEpgForId(streamId: Int): EpgListing? {
        val epgId = channelToEpgMap[streamId] ?: return null
        val now = System.currentTimeMillis() / 1000
        
        return fullEpgData[epgId]?.find { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) }
    }

    fun prefetchEpgForCategory(categoryIndex: Int) {
        val category = uiState.liveStreamsGrouped.getOrNull(categoryIndex) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            category.items.forEach { media ->
                val epgId = media.epgId ?: return@forEach
                if (!fullEpgData.containsKey(epgId)) {
                    val dbEpg = repository.getEpgForChannel(epgId)
                    if (dbEpg.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            fullEpgData[epgId] = dbEpg
                        }
                    }
                }
            }
        }
    }

    fun getNextEpgForId(streamId: Int): EpgListing? {
        val epgId = channelToEpgMap[streamId] ?: return null
        val now = System.currentTimeMillis() / 1000
        val channelList = fullEpgData[epgId] ?: return null
        val currentIndex = channelList.indexOfFirst { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) }
        return if (currentIndex != -1) channelList.getOrNull(currentIndex + 1) else null
    }

    fun getFullEpgForId(streamId: Int): List<EpgListing> {
        val epgId = channelToEpgMap[streamId] ?: return emptyList()
        val epgData = fullEpgData[epgId]
        if (epgData == null) {
            viewModelScope.launch(Dispatchers.IO) {
                val dbEpg = repository.getEpgForChannel(epgId)
                if (dbEpg.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        fullEpgData[epgId] = dbEpg
                    }
                }
            }
        }
        return epgData ?: emptyList()
    }

    private fun loadEpgInBackground(user: String, pass: String, forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.fetchAndStoreEpg(user, pass, forceRefresh)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

data class MediaUiState(
    val liveStreamsGrouped: List<GroupedMedia> = emptyList(),
    val movies: List<GroupedMedia> = emptyList(),
    val series: List<GroupedMedia> = emptyList(),
    val history: List<MediaSource> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val baseUrl: String = "",
    val username: String = "",
    val password: String = ""
)
