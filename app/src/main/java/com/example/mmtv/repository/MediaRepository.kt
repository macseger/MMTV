package com.example.mmtv.repository

import android.content.Context
import com.example.mmtv.api.XCodesApi
import com.example.mmtv.database.*
import com.example.mmtv.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import com.example.mmtv.api.EpgParser

class MediaRepository(
    val api: XCodesApi,
    private val mediaDao: MediaDao,
    private val context: Context
) {
    companion object {
        private val liveSyncMutex = Mutex()
        private val movieSyncMutex = Mutex()
        private val seriesSyncMutex = Mutex()
        private val epgSyncMutex = Mutex()
    }

    private val session = com.example.mmtv.api.SessionManager(context)
    private val gson = Gson()
    private val cacheDir = context.cacheDir
    private val epgParser = EpgParser()
    private var piconFileMap: Map<String, String>? = null
    private val piconMapLock = Any()
    
    // Interna minnescachar för att undvika SQLite LIKE-sökningar vid skroll
    private val epgCache = java.util.Collections.synchronizedMap(mutableMapOf<String, List<EpgListing>>())
    private val iconCache = java.util.Collections.synchronizedMap(mutableMapOf<String, String?>())

    private fun getPiconFileMap(): Map<String, String> {
        synchronized(piconMapLock) {
            val cache = piconFileMap
            if (cache != null) return cache
            
            val piconsDir = File(context.filesDir, "picons")
            if (!piconsDir.exists()) return emptyMap()

            val map = mutableMapOf<String, String>()
            piconsDir.listFiles()?.forEach { file ->
                val name = file.name
                val lastDot = name.lastIndexOf('.')
                if (lastDot > 0) {
                    val cleanName = name.substring(0, lastDot).lowercase().replace(Regex("[^a-z0-9]"), "")
                    if (cleanName.isNotEmpty()) {
                        map[cleanName] = file.absolutePath
                    }
                }
            }
            piconFileMap = map
            return map
        }
    }

    suspend fun getLiveCategories(user: String, pass: String, forceRefresh: Boolean = false): List<Category> {
        return getCachedOrFetch("live_categories", { api.getLiveCategories(user, pass) }, object : TypeToken<List<Category>>() {}, forceRefresh)
    }

    suspend fun getLiveStreams(user: String, pass: String, categoryId: String? = null, forceRefresh: Boolean = false): List<LiveStream> {
        if (!session.hasSyncSelection()) return emptyList()
        val selected = session.getSyncCategories(MediaType.LIVE)
        if (categoryId == null) return selected.flatMap { getLiveStreams(user, pass, it, forceRefresh) }
        if (categoryId !in selected) return emptyList()
        val cacheKey = "live_streams_$categoryId"
        return getCachedOrFetch(cacheKey, { 
            api.getLiveStreams(user, pass, categoryId = categoryId)
        }, object : TypeToken<List<LiveStream>>() {}, forceRefresh).let { list ->
            list.filter { it.categoryId == categoryId }
        }
    }

    suspend fun getMovieCategories(user: String, pass: String, forceRefresh: Boolean = false): List<Category> {
        return getCachedOrFetch("movie_categories", { api.getMovieCategories(user, pass) }, object : TypeToken<List<Category>>() {}, forceRefresh)
    }

    suspend fun getMovies(user: String, pass: String, categoryId: String? = null, forceRefresh: Boolean = false): List<Movie> {
        if (!session.hasSyncSelection()) return emptyList()
        val selected = session.getSyncCategories(MediaType.MOVIE)
        if (categoryId == null) return selected.flatMap { getMovies(user, pass, it, forceRefresh) }
        if (categoryId !in selected) return emptyList()
        val cacheKey = "movies_$categoryId"
        return getCachedOrFetch(cacheKey, { 
            api.getMovies(user, pass, categoryId = categoryId)
        }, object : TypeToken<List<Movie>>() {}, forceRefresh).let { list ->
            list.filter { it.categoryId == categoryId }
        }
    }

    suspend fun getSeriesCategories(user: String, pass: String, forceRefresh: Boolean = false): List<Category> {
        return getCachedOrFetch("series_categories", { api.getSeriesCategories(user, pass) }, object : TypeToken<List<Category>>() {}, forceRefresh)
    }

    suspend fun getSeries(user: String, pass: String, categoryId: String? = null, forceRefresh: Boolean = false): List<Series> {
        if (!session.hasSyncSelection()) return emptyList()
        val selected = session.getSyncCategories(MediaType.SERIES)
        if (categoryId == null) return selected.flatMap { getSeries(user, pass, it, forceRefresh) }
        if (categoryId !in selected) return emptyList()
        val cacheKey = "series_$categoryId"
        return getCachedOrFetch(cacheKey, { 
            api.getSeries(user, pass, categoryId = categoryId)
        }, object : TypeToken<List<Series>>() {}, forceRefresh).let { list ->
            list.filter { it.categoryId == categoryId }
        }
    }

    suspend fun getSeriesInfo(user: String, pass: String, seriesId: Int): SeriesInfoResponse {
        return api.getSeriesInfo(user, pass, seriesId)
    }

    suspend fun getGroupedLive(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val categories = getLiveCategories(user, pass, forceRefresh)
        val streams = getLiveStreams(user, pass, forceRefresh = forceRefresh)
        val streamsByCategory = streams.groupBy { it.categoryId }
        
        categories.map { cat ->
            GroupedMedia(
                title = cat.categoryName,
                categoryId = cat.categoryId,
                items = streamsByCategory[cat.categoryId].orEmpty().map {
                    MediaSource(
                        id = it.streamId,
                        title = it.name,
                        icon = it.streamIcon,
                        type = MediaType.LIVE,
                        epgId = it.epgId
                    )
                }
            )
        }.filter { it.items.isNotEmpty() }
    }

    suspend fun getGroupedMovies(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val categories = getMovieCategories(user, pass, forceRefresh)
        val movies = getMovies(user, pass, forceRefresh = forceRefresh)
        val moviesByCategory = movies.groupBy { it.categoryId }
        
        categories.map { cat ->
            GroupedMedia(
                title = cat.categoryName,
                categoryId = cat.categoryId,
                items = moviesByCategory[cat.categoryId].orEmpty().map {
                    MediaSource(
                        id = it.streamId,
                        title = it.name,
                        icon = it.streamIcon,
                        type = MediaType.MOVIE,
                        extension = it.containerExtension,
                        rating = it.rating,
                        addedDate = parseDateToUnix(it.added)
                    )
                }
            )
        }.filter { it.items.isNotEmpty() }
    }

    suspend fun getGroupedSeries(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val categories = getSeriesCategories(user, pass, forceRefresh)
        val series = getSeries(user, pass, forceRefresh = forceRefresh)
        val seriesByCategory = series.groupBy { it.categoryId }
        
        categories.map { cat ->
            GroupedMedia(
                title = cat.categoryName,
                categoryId = cat.categoryId,
                items = seriesByCategory[cat.categoryId].orEmpty().map {
                    MediaSource(
                        id = it.seriesId,
                        title = it.name,
                        icon = it.cover,
                        type = MediaType.SERIES,
                        plot = it.plot,
                        rating = it.rating,
                        director = it.director,
                        genre = it.genre,
                        cast = it.cast,
                        addedDate = parseDateToUnix(it.lastModified)
                    )
                }
            )
        }.filter { it.items.isNotEmpty() }
    }

    suspend fun getJustCategories(type: MediaType, user: String, pass: String, forceRefresh: Boolean = false, includeUnselected: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        try {
            val categories = when (type) {
                MediaType.LIVE -> getLiveCategories(user, pass, forceRefresh)
                MediaType.MOVIE -> getMovieCategories(user, pass, forceRefresh)
                MediaType.SERIES -> getSeriesCategories(user, pass, forceRefresh)
            }
            categories.filter { includeUnselected || it.categoryId in session.getSyncCategories(type) }.map { GroupedMedia(title = it.categoryName, categoryId = it.categoryId, items = emptyList()) }
        } catch (e: Exception) {
            throw e
        }
    }

    suspend fun getMediaForCategory(type: MediaType, categoryId: String): List<MediaSource> = withContext(Dispatchers.IO) {
        if (categoryId !in session.getSyncCategories(type)) return@withContext emptyList()
        val entities = mediaDao.getMediaByCategoryId(type, categoryId)
        if (type == MediaType.LIVE) {
            entities.map { it.toMediaSource() }
        } else {
            // Sortera ALLTID VOD efter addedDate (nyaste först)
            entities.sortedByDescending { it.addedDate }.map { it.toMediaSource() }
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

    // Database methods
    suspend fun getAllMediaByType(type: MediaType): List<MediaEntity> = withContext(Dispatchers.IO) {
        mediaDao.getMediaByType(type)
    }

    suspend fun getCategoriesByType(type: MediaType): List<CategorySimple> = withContext(Dispatchers.IO) {
        mediaDao.getCategoriesByType(type)
    }

    suspend fun getMediaByCategoryId(type: MediaType, catId: String): List<MediaEntity> = withContext(Dispatchers.IO) {
        mediaDao.getMediaByCategoryId(type, catId)
    }

    suspend fun toggleFavorite(item: MediaEntity) = withContext(Dispatchers.IO) {
        val newFav = !item.isFavorite
        val favDate = if (newFav) System.currentTimeMillis() else 0L
        mediaDao.updateFavoriteWithDate(item.id, item.type, newFav, favDate)
    }

    suspend fun getFavorites(): List<MediaEntity> = withContext(Dispatchers.IO) {
        mediaDao.getFavorites()
    }

    suspend fun clearAllFavorites() = withContext(Dispatchers.IO) {
        mediaDao.clearAllFavorites()
    }

    suspend fun getRecentlyAdded(): List<MediaEntity> = withContext(Dispatchers.IO) {
        mediaDao.getRecentlyAdded()
    }

    suspend fun searchMedia(query: String): List<MediaEntity> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        mediaDao.searchMedia("%$query%")
    }

    suspend fun extractPiconsIfNeeded() = withContext(Dispatchers.IO) {
        val piconsDir = File(context.filesDir, "picons")
        val zipFileInAssets = "picons.zip"
        
        try {
            // Kontrollera om vi har filen i assets
            val assets = context.assets.list("") ?: emptyArray()
            if (!assets.contains(zipFileInAssets)) {
                android.util.Log.e("Picons", "Hittade inte picons.zip i assets!")
                return@withContext
            }

            // Om mappen redan finns och inte är tom, hoppa över extraktion för snabbare start
            val lastExtractionFile = File(piconsDir, ".last_extracted")
            if (piconsDir.exists() && piconsDir.list()?.isNotEmpty() == true && lastExtractionFile.exists()) {
                android.util.Log.d("Picons", "Ikoner redan extraherade, hoppar över.")
                return@withContext
            }

            if (!piconsDir.exists()) piconsDir.mkdirs()

            android.util.Log.d("Picons", "Börjar extrahera ikoner...")
            context.assets.open(zipFileInAssets).use { inputStream ->
                java.util.zip.ZipInputStream(inputStream).use { zipInput ->
                    var entry = zipInput.nextEntry
                    var count = 0
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val fileName = File(entry.name).name.lowercase()
                            if (fileName.endsWith(".png") || fileName.endsWith(".jpg")) {
                                val outFile = File(piconsDir, fileName)
                                outFile.outputStream().use { output ->
                                    zipInput.copyTo(output)
                                }
                                count++
                            }
                        }
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                    android.util.Log.d("Picons", "Extraherade $count ikoner.")
                    lastExtractionFile.createNewFile() // Markera att vi är klara
                    piconFileMap = null // Rensa cachen så den läses om nästa gång
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Picons", "Fel vid extrahering: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun syncLiveChannels(user: String, pass: String) = withContext(Dispatchers.IO) {
        liveSyncMutex.withLock {
            try {
                val liveCats = api.getLiveCategories(user, pass)
                if (!session.hasSyncSelection()) return@withContext
                val selected = session.getSyncCategories(MediaType.LIVE)
                val liveStreams = liveCats.filter { it.categoryId in selected }.flatMap { category ->
                    api.getLiveStreams(user, pass, categoryId = category.categoryId)
                        .filter { it.categoryId == category.categoryId }
                }
                val categoriesById = liveCats.associateBy { it.categoryId }
                val liveEntities = liveStreams.mapIndexed { index, stream ->
                    val cat = categoriesById[stream.categoryId]
                    // Vi behåller ursprungligt namn från servern enligt önskemål
                    val originalName = stream.name ?: ""
                    MediaEntity(
                        id = stream.streamId,
                        title = originalName,
                        type = MediaType.LIVE,
                        categoryId = stream.categoryId ?: "",
                        categoryName = cat?.categoryName ?: "Okänd",
                        icon = stream.streamIcon,
                        epgId = stream.epgId,
                        itemOrder = index,
                        addedDate = 0L
                    )
                }
                if (selected == session.getSyncCategories(MediaType.LIVE)) {
                    mediaDao.replaceTypePreservingFavorites(MediaType.LIVE, liveEntities)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    suspend fun resolveLiveIcons() = resolveAndStoreLiveIcons()

    /** Körs enbart under kanaluppdateringen. Overlayen ska aldrig behöva matcha picons. */
    private suspend fun resolveAndStoreLiveIcons() = withContext(Dispatchers.IO) {
        iconCache.clear()
        mediaDao.getMediaByType(MediaType.LIVE).forEach { channel ->
            val resolved = getIconForChannel(channel.epgId, channel.title)
            mediaDao.updateResolvedIcon(channel.id, MediaType.LIVE, resolved ?: channel.icon)
        }
    }

    suspend fun syncMovies(user: String, pass: String) = withContext(Dispatchers.IO) {
        movieSyncMutex.withLock {
            try {
                val movieCats = api.getMovieCategories(user, pass)
                if (!session.hasSyncSelection()) return@withContext
                val selected = session.getSyncCategories(MediaType.MOVIE)
                val movies = movieCats.filter { it.categoryId in selected }.flatMap { category ->
                    api.getMovies(user, pass, categoryId = category.categoryId)
                        .filter { it.categoryId == category.categoryId }
                }
                val categoriesById = movieCats.associateBy { it.categoryId }
                val movieEntities = movies.mapIndexed { index, movie ->
                    val cat = categoriesById[movie.categoryId]
                    MediaEntity(
                        id = movie.streamId,
                        title = movie.name ?: "",
                        type = MediaType.MOVIE,
                        categoryId = movie.categoryId ?: "",
                        categoryName = cat?.categoryName ?: "Okänd",
                        icon = movie.streamIcon,
                        extension = movie.containerExtension ?: "mp4",
                        itemOrder = index,
                        addedDate = parseDateToUnix(movie.added)
                    )
                }
                if (selected == session.getSyncCategories(MediaType.MOVIE)) {
                    mediaDao.replaceTypePreservingFavorites(MediaType.MOVIE, movieEntities)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    suspend fun syncSeries(user: String, pass: String) = withContext(Dispatchers.IO) {
        seriesSyncMutex.withLock {
            try {
                val seriesCats = api.getSeriesCategories(user, pass)
                if (!session.hasSyncSelection()) return@withContext
                val selected = session.getSyncCategories(MediaType.SERIES)
                val seriesItems = seriesCats.filter { it.categoryId in selected }.flatMap { category ->
                    api.getSeries(user, pass, categoryId = category.categoryId)
                        .filter { it.categoryId == category.categoryId }
                }
                val categoriesById = seriesCats.associateBy { it.categoryId }
                val seriesEntities = seriesItems.mapIndexed { index, item ->
                    val cat = categoriesById[item.categoryId]
                    MediaEntity(
                        id = item.seriesId,
                        title = item.name ?: "",
                        type = MediaType.SERIES,
                        categoryId = item.categoryId ?: "",
                        categoryName = cat?.categoryName ?: "Okänd",
                        icon = item.cover,
                        itemOrder = index,
                        addedDate = parseDateToUnix(item.lastModified)
                    )
                }
                if (selected == session.getSyncCategories(MediaType.SERIES)) {
                    mediaDao.replaceTypePreservingFavorites(MediaType.SERIES, seriesEntities)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    suspend fun syncVodLibrary(user: String, pass: String) = coroutineScope {
        val movieJob = async { syncMovies(user, pass) }
        val seriesJob = async { syncSeries(user, pass) }
        movieJob.await()
        seriesJob.await()
    }

    suspend fun syncLibrary(user: String, pass: String) = coroutineScope {
        val selection = MediaType.entries.associateWith(session::getSyncCategories)
        val liveJob = async { syncLiveChannels(user, pass) }
        val vodJob = async { syncVodLibrary(user, pass) }
        liveJob.await()
        vodJob.await()
        if (selection == MediaType.entries.associateWith(session::getSyncCategories)) session.markSyncSelectionComplete()
    }

    suspend fun fetchAndStoreEpg(user: String, pass: String, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        epgSyncMutex.withLock {
            if (!session.hasSyncSelection()) return@withContext
            val selectedLive = mediaDao.getMediaByType(MediaType.LIVE)
                .filter { it.categoryId in session.getSyncCategories(MediaType.LIVE) }
            val scope = selectedLive.map { "${it.id}:${it.epgId}" }.sorted().joinToString("|") + session.getUseExternalSwedishEpg()
            val scopeFile = File(cacheDir, "${accountCacheKey()}_epg_scope")
            val scopeChanged = !scopeFile.exists() || scopeFile.readText() != scope
            val refreshInterval = 3 * 60 * 60 * 1000L
            if (!forceRefresh && !scopeChanged && System.currentTimeMillis() - scopeFile.lastModified() < refreshInterval) {
                return@withContext
            }
            if (scopeChanged || forceRefresh) {
                mediaDao.clearEpg()
                mediaDao.clearChannelMetadata()
            }
            epgCache.clear()
            if (selectedLive.isEmpty()) {
                scopeFile.writeText(scope)
                return@withContext
            }

            // Bounded requests: only the selected stream IDs, with account-scoped disk caching.
            val channelApiWorked = fetchSelectedChannelEpg(user, pass, selectedLive, forceRefresh)
            if (!channelApiWorked) {
                val xmlFile = File(cacheDir, "${accountCacheKey()}_full_epg.xml")
                val stale = !xmlFile.exists() || System.currentTimeMillis() - xmlFile.lastModified() > 24 * 60 * 60 * 1000L
                if (forceRefresh || stale) downloadEpgFile(xmlFile) { api.getFullEpg(user, pass) }
                parseAndStore(xmlFile, true)
            }
            if (session.getUseExternalSwedishEpg()) {
                val xmlFile = File(cacheDir, "swedish_epg.xml")
                val stale = !xmlFile.exists() || System.currentTimeMillis() - xmlFile.lastModified() > 24 * 60 * 60 * 1000L
                if (forceRefresh || stale) downloadEpgFile(xmlFile) {
                    api.getExternalEpg("https://epgshare01.online/epgshare01/epg_ripper_SE1.xml.gz")
                }
                // Server data may have replaced the previous external entries.
                parseAndStore(xmlFile, false)
            }
            mediaDao.deleteOldEpg(System.currentTimeMillis() / 1000)
            scopeFile.writeText(scope)
            epgCache.clear()
            syncPiconsFromGithub(forceRefresh)
        }
    }

    private suspend fun fetchSelectedChannelEpg(user: String, pass: String, channels: List<MediaEntity>, forceRefresh: Boolean): Boolean {
        var hasListings = false
        for (chunk in channels.distinctBy { it.epgId?.takeIf(String::isNotBlank) ?: "stream:${it.id}" }.chunked(4)) {
            val results = coroutineScope {
                chunk.map { channel -> async {
                    try {
                        val response = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                            getCachedOrFetch("channel_epg_${channel.id}", { api.getChannelEpg(user, pass, channel.id) }, object : TypeToken<EpgResponse>() {}, forceRefresh, maxAgeMs = 3 * 60 * 60 * 1000L)
                        } ?: return@async null
                        val listings = response.listings ?: return@async null
                        val epgId = channel.epgId?.takeIf(String::isNotBlank) ?: "stream:${channel.id}"
                        val now = System.currentTimeMillis() / 1000
                        val entities = listings.mapNotNull { listing ->
                            val start = listing.startTimestamp ?: return@mapNotNull null
                            val stop = listing.stopTimestamp ?: return@mapNotNull null
                            if (stop <= now || start >= now + 7 * 24 * 60 * 60) return@mapNotNull null
                            EpgEntity(epgId = epgId, channelName = channel.title,
                                title = decodeEpgText(listing.title), description = decodeEpgText(listing.description),
                                startTimestamp = start, stopTimestamp = stop)
                        }
                        mediaDao.replaceChannelEpg(epgId, entities)
                        entities.isNotEmpty()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { null }
                } }.map { it.await() }
            }
            if (results.any { it == null }) return false
            hasListings = hasListings || results.any { it == true }
            // Unsupported servers should not incur hundreds of failing requests.
            if (!hasListings) return false
        }
        return hasListings
    }

    private suspend fun downloadEpgFile(file: File, fetch: suspend () -> okhttp3.ResponseBody) {
        val temporary = File(file.path + ".tmp")
        try {
            fetch().use { response ->
                response.byteStream().buffered().use { input ->
                    input.mark(2)
                    val gzip = input.read() == 0x1f && input.read() == 0x8b
                    input.reset()
                    val decoded = if (gzip) GZIPInputStream(input) else input
                    temporary.outputStream().use { decoded.copyTo(it) }
                }
            }
            check(temporary.length() > 0 && temporary.renameTo(file)) { "Kunde inte spara EPG" }
        } finally { temporary.delete() }
    }

    private fun accountCacheKey(): String {
        val account = session.getLogin()?.let { "${it.first}|${it.second}" }.orEmpty()
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    suspend fun performInitialProvisioning(user: String, pass: String, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("Synkar Live...")
        syncLibrary(user, pass)
        onProgress("Synkar EPG...")
        fetchAndStoreEpg(user, pass, true)
        onProgress("Synk klar!")
    }

    private suspend fun syncPiconsFromGithub(forceRefresh: Boolean) = withContext(Dispatchers.IO) {
        if (!forceRefresh && mediaDao.getAllPicons().isNotEmpty()) return@withContext

        try {
            val githubUrl = "https://api.github.com/repos/tv-logo/tv-logos/contents/countries/nordic/sweden"
            val files = api.getGithubFiles(githubUrl)
            
            val piconEntities = files.filter { it.name.endsWith(".png", true) || it.name.endsWith(".jpg", true) }.map { file ->
                val cleanName = file.name.substringBeforeLast(".")
                    .lowercase()
                    .replace(" ", "")
                    .replace("-", "")
                    .replace("_", "")
                    .replace(".", "")
                
                com.example.mmtv.database.PiconEntity(
                    name = cleanName,
                    url = file.downloadUrl ?: ""
                )
            }
            
            if (piconEntities.isNotEmpty()) {
                mediaDao.insertPicons(piconEntities)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun parseAndStore(file: File, isClearFirst: Boolean) = withContext(Dispatchers.IO) {
        val batch = mutableListOf<EpgEntity>()
        val channels = mutableListOf<ChannelEntity>()
        val channelNamesMap = mutableMapOf<String, String>()
        var isFirstBatch = true
        val selectedChannels = mediaDao.getMediaByType(MediaType.LIVE)
            .filter { it.categoryId in session.getSyncCategories(MediaType.LIVE) }
        val allowedIds = selectedChannels.mapNotNull { it.epgId?.takeIf(String::isNotBlank) }.toMutableSet()
        val allowedNames = selectedChannels.map { normalizeEpgChannelName(it.title) }.filter { it.isNotEmpty() }.toSet()
        val now = System.currentTimeMillis() / 1000
        val endLimit = now + 7 * 24 * 60 * 60

        file.inputStream().use { input ->
            epgParser.parseStreaming(
                input,
                onChannelParsed = { id, displayName, icon ->
                    if (id in allowedIds || normalizeEpgChannelName(displayName.orEmpty()) in allowedNames) {
                        allowedIds.add(id)
                        channels.add(ChannelEntity(id, displayName, icon))
                        if (displayName != null) channelNamesMap[id] = displayName
                        if (channels.size >= 100) {
                            mediaDao.insertChannels(ArrayList(channels))
                            channels.clear()
                        }
                    }
                },
                acceptChannel = { it in allowedIds },
                onProgrammeParsed = { epgId, it ->
                    if ((it.stopTimestamp ?: 0L) > now && (it.startTimestamp ?: 0L) < endLimit) {
                        batch.add(EpgEntity(
                            epgId = epgId,
                            channelName = channelNamesMap[epgId],
                            title = it.title,
                            description = it.description,
                            startTimestamp = it.startTimestamp ?: 0L,
                            stopTimestamp = it.stopTimestamp ?: 0L,
                            icon = it.icon
                        ))

                        if (batch.size >= 500) {
                            if (isFirstBatch && isClearFirst) {
                                mediaDao.clearEpg()
                            }
                            isFirstBatch = false
                            mediaDao.insertEpg(ArrayList(batch))
                            batch.clear()
                        }
                    }
                }
            )
        }

        if (isFirstBatch && isClearFirst && batch.isEmpty()) mediaDao.clearEpg()
        if (channels.isNotEmpty()) mediaDao.insertChannels(channels)
        if (batch.isNotEmpty()) {
            if (isFirstBatch && isClearFirst) mediaDao.clearEpg()
            mediaDao.insertEpg(batch)
        }
    }

    suspend fun getEpgForChannel(epgId: String?, channelName: String? = null): List<EpgListing> = withContext(Dispatchers.IO) {
        val cacheKey = epgId ?: channelName ?: return@withContext emptyList<EpgListing>()
        epgCache[cacheKey]?.let { return@withContext it }

        val now = System.currentTimeMillis() / 1000
        val endLimit = now + (48 * 60 * 60) // Öka till 48h för att vara säkrare vid tidszonsskillnader
        
        if (epgId != null) {
            val exactMatch = mediaDao.getEpgForChannelWithLimit(epgId, now - 3600, endLimit) // -1h för marginal
            if (exactMatch.isNotEmpty()) {
                val result = exactMatch.map { it.toEpgListing() }
                epgCache[cacheKey] = result
                return@withContext result
            }
        }

        if (channelName != null) {
            val searchName = getSearchName(channelName)
            val fuzzyMatch = mediaDao.findEpgByFuzzyName(channelName, searchName, epgId, now - 3600, endLimit)
            if (fuzzyMatch.isNotEmpty()) {
                val result = fuzzyMatch.map { it.toEpgListing() }
                epgCache[cacheKey] = result
                return@withContext result
            }
        }
        
        emptyList<EpgListing>()
    }

    /**
     * Hämtar en hel kanalgrupp i några få indexerade SQLite-frågor. Detta körs när
     * kategorin laddas, aldrig när användaren flyttar fokus i overlayen.
     */
    suspend fun getEpgForChannels(epgIds: Collection<String>): Map<String, List<EpgListing>> = withContext(Dispatchers.IO) {
        if (epgIds.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis() / 1000
        val endLimit = now + (48 * 60 * 60)
        epgIds.distinct().chunked(900)
            .flatMap { mediaDao.getEpgForChannelsWithLimit(it, now - 3600, endLimit) }
            .groupBy { it.epgId }
            .mapValues { (_, entities) -> entities.map { it.toEpgListing() } }
    }

    suspend fun getIconForChannel(epgId: String?, channelName: String?): String? = withContext(Dispatchers.IO) {
        val cacheKey = epgId ?: channelName ?: return@withContext null
        if (iconCache.containsKey(cacheKey)) return@withContext iconCache[cacheKey]

        val piconMap = getPiconFileMap()
        
        fun findLocalPath(target: String): String? {
            if (target.isEmpty()) return null
            // 1. Exakt match på rensat namn
            piconMap[target]?.let { return "file://$it" }
            // 2. Kolla om något filnamn slutar på target
            return piconMap.keys.find { it.endsWith(target) }?.let { "file://${piconMap[it]}" }
        }

        var result: String? = null

        // 1. Kolla lokalt via kanalnamn
        if (channelName != null) {
            val searchName = getSearchName(channelName)
            result = findLocalPath(searchName)
        }

        // 2. Kolla lokalt via EPG-ID
        if (result == null && epgId != null) {
            val cleanEpgId = epgId.lowercase().substringBefore(".").replace(Regex("[^a-z0-9]"), "")
            result = findLocalPath(cleanEpgId)
        }

        // 3. Fallback till GitHub Picons
        if (result == null && epgId != null) {
            val cleanEpgId = epgId.lowercase()
                .substringBefore(".")
                .replace(Regex("[^a-z0-9]"), "")
            
            val githubPicon = mediaDao.getPiconByName(cleanEpgId)
            if (githubPicon != null) result = githubPicon.url
        }

        if (result == null && channelName != null) {
            val searchName = getSearchName(channelName)
            val githubPicon = mediaDao.getPiconByName(searchName)
            if (githubPicon != null) result = githubPicon.url
        }

        // 4. Sista utväg: Serverns egen ikon
        if (result == null && epgId != null) {
            val icon = mediaDao.getIconByEpgId(epgId)
            if (icon != null) result = icon
        }
        
        iconCache[cacheKey] = result
        result
    }

    private fun cleanChannelName(name: String): String {
        var cleaned = name
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            // Vi tar INTE bort HD/FHD/4K här längre för visningens skull
            .replace("|", "")
            .replace(".", " ")

        // Ta bort vanliga IPTV-prefix (t.ex. "SE:", "NO:", "SE |", "SWE ")
        val prefixRegex = Regex("(?i)^([a-z]{1,3}\\s?[:|\\-]\\s?|SE\\s+|SWE\\s+)")
        cleaned = cleaned.replace(prefixRegex, "")

        return cleaned.trim()
    }

    private fun getSearchName(name: String): String {
        return name.lowercase()
            .replace(Regex("(?i)\\b(HD|FHD|UHD|4K|SD|H265|HEVC|S\\d+)\\b"), "") // Ta bort kvalitéts-markörer
            .replace(Regex("[^a-z0-9]"), "") // Ta bort allt utom bokstäver och siffror
            .trim()
    }

    private fun EpgEntity.toEpgListing() = EpgListing(
        id = id.toString(),
        epgId = epgId,
        title = title,
        description = description,
        start = null,
        end = null,
        startTimestamp = startTimestamp,
        stopTimestamp = stopTimestamp,
        icon = icon
    )

    private fun parseDateToUnix(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "yyyyMMddHHmmss"
        )
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
                val date = sdf.parse(dateStr)
                if (date != null) return date.time / 1000L
            } catch (e: Exception) {}
        }
        return dateStr.toLongOrNull() ?: 0L
    }

    private suspend fun <T> getCachedOrFetch(
        cacheKey: String, 
        fetcher: suspend () -> T, 
        typeToken: TypeToken<T>, 
        forceRefresh: Boolean = false,
        maxAgeMs: Long = 24 * 60 * 60 * 1000L
    ): T = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "${accountCacheKey()}_$cacheKey.json")
        val now = System.currentTimeMillis()
        if (!forceRefresh && cacheFile.exists() && (now - cacheFile.lastModified() < maxAgeMs)) {
            try {
                val json = cacheFile.bufferedReader().use { it.readText() }
                val cached = gson.fromJson<T>(json, typeToken.type)
                if (cached != null) return@withContext cached
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val data = fetcher()
        try { 
            cacheFile.bufferedWriter().use { out ->
                gson.toJson(data, typeToken.type, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        data
    }
}
