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
                        val hiddenLive = sessionManager.getHiddenCategories("live")
                        val hiddenMovies = sessionManager.getHiddenCategories("movies")
                        val hiddenSeries = sessionManager.getHiddenCategories("series")
                        val allHidden = hiddenLive + hiddenMovies + hiddenSeries

                        val entities = mediaDao.searchMedia("%$query%")
                        _dbSearchResults.value = entities
                            .filter { it.categoryName !in allHidden }
                            .map { it.toMediaSource() }
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
        isFavorite = isFavorite
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
        isFavorite = isFavorite
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
        updateStatus = "Uppdaterar allt i bakgrunden..."
        viewModelScope.launch {
            uiState = uiState.copy(username = user, password = pass, baseUrl = host)
            
            try {
                val favorites = withContext(Dispatchers.IO) { mediaDao.getFavorites().map { it.toMediaSource() } }
                
                // 1. Försök ladda från DB OMEDELBART för att få bort Splash Screen snabbt
                if (!forceRefresh) {
                    val cachedLive = withContext(Dispatchers.IO) {
                        repository.getGroupedLive(user, pass, forceRefresh = false)
                    }
                    val history = sessionManager.getHistory()
                    if (cachedLive.isNotEmpty()) {
                        val filteredLive = withContext(Dispatchers.Default) {
                            addSpecialCategories(applyFiltersAndOrder("live", cachedLive), history, favorites, MediaType.LIVE)
                        }
                        uiState = uiState.copy(liveStreamsGrouped = filteredLive, history = history, isLoading = false)
                        
                        // Kartlägg EPG-ID:n
                        cachedLive.flatMap { it.items }.forEach { 
                            if (it.epgId != null) channelToEpgMap[it.id] = it.epgId 
                        }
                        
                        // Ladda nyligen tillagt och favoriter från DB omedelbart
                        val recent = mediaDao.getRecentlyAdded()
                        _recentlyAdded.value = recent.map { it.toMediaSource() }
                        val favs = mediaDao.getFavorites()
                        _favorites.value = favs.map { it.toMediaSource() }

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
                            addSpecialCategories(applyFiltersAndOrder("live", newLive), history, favorites, MediaType.LIVE)
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
                            addSpecialCategories(applyFiltersAndOrder("movies", sortedMovies), history, favorites, MediaType.MOVIE)
                        }
                        withContext(Dispatchers.Main) {
                            uiState = uiState.copy(movies = filteredMovies)
                            // Uppdatera nyligen tillagda efter att filmer har laddats/cacheats
                            val recent = mediaDao.getRecentlyAdded()
                            _recentlyAdded.value = recent.map { it.toMediaSource() }
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
                            addSpecialCategories(applyFiltersAndOrder("series", sortedSeries), history, favorites, MediaType.SERIES)
                        }
                        withContext(Dispatchers.Main) {
                            uiState = uiState.copy(series = filteredSeries)
                            // Uppdatera nyligen tillagda även efter serier
                            val recent = mediaDao.getRecentlyAdded()
                            _recentlyAdded.value = recent.map { it.toMediaSource() }

                            updateStatus = "Allt är fixat, ha en fin dag!"
                            viewModelScope.launch {
                                delay(4000)
                                if (updateStatus == "Allt är fixat, ha en fin dag!") {
                                    updateStatus = null
                                }
                            }
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

    fun setLiveCategoryByMediaId(mediaId: Int) {
        val groupedList = uiState.liveStreamsGrouped
        val specialTitles = listOf("★ FAVORITER", "Senast visade")
        
        // 1. Letar först i RIKTIGA kategorier (inte specialkategorier)
        var index = groupedList.indexOfFirst { group ->
            !specialTitles.contains(group.title) && group.items.any { it.id == mediaId }
        }
        
        // 2. Om vi inte hittade den där, sök i alla (inklusive favoriter/historik)
        if (index == -1) {
            index = groupedList.indexOfFirst { group ->
                group.items.any { it.id == mediaId }
            }
        }

        if (index != -1) {
            lastLiveCategoryIndex = index
            // Tvinga även spelarens interna lista att uppdateras till den valda kategorins innehåll
            currentPlaylist = groupedList[index].items
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
        viewModelScope.launch {
            updateStatus = "Uppdaterar allt i bakgrunden..."
            loadData(uiState.username, uiState.password, uiState.baseUrl, forceRefresh = true) { success ->
                if (!success) {
                    updateStatus = "Kunde inte uppdatera."
                    viewModelScope.launch {
                        delay(3000)
                        updateStatus = null
                    }
                }
            }
        }
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
            val favorites = withContext(Dispatchers.IO) { mediaDao.getFavorites().map { it.toMediaSource() } }
            
            val sortedMovies = movies.map { it.copy(items = it.items.sortedByDescending { m -> m.id }) }
            val sortedSeries = series.map { it.copy(items = it.items.sortedByDescending { s -> s.id }) }

            uiState = uiState.copy(
                liveStreamsGrouped = addSpecialCategories(applyFiltersAndOrder("live", live), history, favorites, MediaType.LIVE),
                movies = addSpecialCategories(applyFiltersAndOrder("movies", sortedMovies), history, favorites, MediaType.MOVIE),
                series = addSpecialCategories(applyFiltersAndOrder("series", sortedSeries), history, favorites, MediaType.SERIES),
                history = history
            )
            _favorites.value = favorites
            // Trigger prefetch after refresh
            prefetchEpgForCategory(0)
            
            // Uppdatera även senast tillagda
            val recent = withContext(Dispatchers.IO) { mediaDao.getRecentlyAdded() }
            _recentlyAdded.value = recent.map { it.toMediaSource() }
        }
    }

    var isLoggingOut by mutableStateOf(false)
        private set

    fun performFullLogout(onComplete: () -> Unit) {
        viewModelScope.launch {
            isLoggingOut = true
            
            // 1. Rensa databasen (Favoriter, EPG etc)
            withContext(Dispatchers.IO) {
                database.clearAllTables()
            }
            
            // 2. Rensa VM:ens interna cacher
            _favorites.value = emptyList()
            _recentlyAdded.value = emptyList()
            _dbSearchResults.value = emptyList()
            fullEpgData.clear()
            channelToEpgMap.clear()
            
            // 3. Rensa sessionen (SharedPreferences)
            sessionManager.logout()
            
            // Lite delay för att användaren ska hinna se att det händer nåt
            delay(1500)
            
            isLoggingOut = false
            onComplete()
        }
    }

    private fun addSpecialCategories(list: List<GroupedMedia>, history: List<MediaSource>, favorites: List<MediaSource>, type: MediaType): List<GroupedMedia> {
        val result = mutableListOf<GroupedMedia>()
        
        // 1. Favoriter ÖVERST
        var relevantFavs = favorites.filter { it.type == type }
        
        // För LIVE TV vill vi ha nyaste favoriten sist i listan
        if (type == MediaType.LIVE) {
            relevantFavs = relevantFavs.reversed()
        }

        if (relevantFavs.isNotEmpty()) {
            result.add(GroupedMedia(title = "★ FAVORITER", items = relevantFavs))
        }

        // 2. Historik - Endast för Film och Serier, inte för LIVE TV (enligt önskemål)
        if (type != MediaType.LIVE) {
            val relevantHistory = history.filter { it.type == type }
            if (relevantHistory.isNotEmpty()) {
                result.add(GroupedMedia(title = "Historik", items = relevantHistory))
            }
        }

        result.addAll(list)
        return result
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

    fun getEpgForId(streamId: Int, channelName: String? = null): EpgListing? {
        val epgId = channelToEpgMap[streamId] ?: ""
        val now = System.currentTimeMillis() / 1000
        
        val epgList = fullEpgData[epgId]
        if (epgList == null && epgId.isNotEmpty()) {
            // Om den saknas i cachen, försök ladda den i bakgrunden för nästa gång
            viewModelScope.launch(Dispatchers.IO) {
                val dbEpg = repository.getEpgForChannel(epgId, channelName)
                if (dbEpg.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        fullEpgData[epgId] = dbEpg
                    }
                }
            }
        }
        
        return epgList?.find { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) }
    }

    fun prefetchEpgForCategory(categoryIndex: Int) {
        val category = uiState.liveStreamsGrouped.getOrNull(categoryIndex) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            category.items.forEach { media ->
                val epgId = media.epgId ?: ""
                if (!fullEpgData.containsKey(epgId)) {
                    val dbEpg = repository.getEpgForChannel(epgId, media.title)
                    if (dbEpg.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            fullEpgData[epgId] = dbEpg
                        }
                    }
                }
            }
        }
    }

    fun getNextEpgForId(streamId: Int, channelName: String? = null): EpgListing? {
        val epgId = channelToEpgMap[streamId] ?: return null
        val now = System.currentTimeMillis() / 1000
        var channelList = fullEpgData[epgId]
        
        if (channelList == null && channelName != null) {
             // Försök ladda om den saknas
             viewModelScope.launch(Dispatchers.IO) {
                 val dbEpg = repository.getEpgForChannel(epgId, channelName)
                 if (dbEpg.isNotEmpty()) {
                     withContext(Dispatchers.Main) {
                         fullEpgData[epgId] = dbEpg
                     }
                 }
             }
        }
        
        val currentIndex = channelList?.indexOfFirst { now in (it.startTimestamp ?: 0)..(it.stopTimestamp ?: 0) } ?: -1
        return if (currentIndex != -1) channelList?.getOrNull(currentIndex + 1) else null
    }

    fun getFullEpgForId(streamId: Int, channelName: String? = null): List<EpgListing> {
        val epgId = channelToEpgMap[streamId] ?: return emptyList()
        val epgData = fullEpgData[epgId]
        if (epgData == null) {
            viewModelScope.launch(Dispatchers.IO) {
                val dbEpg = repository.getEpgForChannel(epgId, channelName)
                if (dbEpg.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        fullEpgData[epgId] = dbEpg
                    }
                }
            }
        }
        return epgData ?: emptyList()
    }

    fun clearHistory() {
        sessionManager.clearHistory()
        refreshLists()
        viewModelScope.launch {
            updateStatus = "Historiken har rensats!"
            delay(3000)
            updateStatus = null
        }
    }

    fun clearAllFavorites() {
        viewModelScope.launch(Dispatchers.IO) {
            mediaDao.clearAllFavorites()
            withContext(Dispatchers.Main) {
                refreshLists()
                updateStatus = "Alla favoriter har tagits bort!"
                viewModelScope.launch {
                    delay(3000)
                    updateStatus = null
                }
            }
        }
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
