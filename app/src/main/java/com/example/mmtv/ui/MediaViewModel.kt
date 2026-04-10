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

class MediaViewModel(private val repository: MediaRepository, private val sessionManager: SessionManager, private val database: MediaDatabase) : ViewModel() {

    var uiState by mutableStateOf(MediaUiState())
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

    fun loadData(user: String, pass: String, host: String, forceRefresh: Boolean = false) {
        if (isFetching) return
        isFetching = true
        viewModelScope.launch {
            if (forceRefresh || uiState.liveStreamsGrouped.isEmpty()) {
                uiState = uiState.copy(isLoading = true, username = user, password = pass, baseUrl = host)
            }
            
            // Starta EPG-laddning direkt i bakgrunden
            loadEpgInBackground(user, pass, forceRefresh)

            try {
                // Rensa databasen först om det är en refresh
                if (forceRefresh) {
                    withContext(Dispatchers.IO) {
                        mediaDao.clearAll()
                    }
                }

                // Ladda kategorier parallellt men uppdatera UI så fort de är klara
                val liveJob = launch(Dispatchers.IO) {
                    val live = repository.getGroupedLive(user, pass, forceRefresh)
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(liveStreamsGrouped = applyFiltersAndOrder("live", live))
                        
                        // Kartlägg EPG-ID:n för Live-kanaler
                        live.flatMap { it.items }.forEach { 
                            if (it.epgId != null) channelToEpgMap[it.id] = it.epgId
                        }

                        // Prefetch EPG för den första kategorin
                        prefetchEpgForCategory(0)

                        // Spara till DB för sökning
                        launch(Dispatchers.IO) {
                            val entities = live.flatMap { group -> 
                                group.items.map { it.toEntity(null, group.title) } 
                            }
                            if (entities.isNotEmpty()) {
                                mediaDao.insertAll(entities)
                            }
                        }
                    }
                }

                val moviesJob = launch(Dispatchers.IO) {
                    val movies = repository.getGroupedMovies(user, pass, forceRefresh)
                    val history = sessionManager.getHistory()
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(movies = addHistoryCategory(applyFiltersAndOrder("movies", movies), history, MediaType.MOVIE))
                        
                        // Spara till DB för sökning
                        launch(Dispatchers.IO) {
                            val entities = movies.flatMap { group -> 
                                group.items.map { it.toEntity(null, group.title) } 
                            }
                            if (entities.isNotEmpty()) {
                                mediaDao.insertAll(entities)
                            }
                        }
                    }
                }

                val seriesJob = launch(Dispatchers.IO) {
                    val series = repository.getGroupedSeries(user, pass, forceRefresh)
                    val history = sessionManager.getHistory()
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(series = addHistoryCategory(applyFiltersAndOrder("series", series), history, MediaType.SERIES))
                        
                        // Spara till DB för sökning
                        launch(Dispatchers.IO) {
                            val entities = series.flatMap { group -> 
                                group.items.map { it.toEntity(null, group.title) } 
                            }
                            if (entities.isNotEmpty()) {
                                mediaDao.insertAll(entities)
                            }
                        }
                    }
                }

                // Vänta på att allt ska bli klart för att stänga av loading-spinnern
                liveJob.join()
                moviesJob.join()
                seriesJob.join()

            } catch (e: Exception) {
                uiState = uiState.copy(error = e.message)
            } finally {
                uiState = uiState.copy(isLoading = false)
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
            uiState = uiState.copy(
                liveStreamsGrouped = applyFiltersAndOrder("live", live),
                movies = addHistoryCategory(applyFiltersAndOrder("movies", movies), history, MediaType.MOVIE),
                series = addHistoryCategory(applyFiltersAndOrder("series", series), history, MediaType.SERIES)
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val baseUrl: String = "",
    val username: String = "",
    val password: String = ""
)
