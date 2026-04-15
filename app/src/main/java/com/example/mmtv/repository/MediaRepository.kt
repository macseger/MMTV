package com.example.mmtv.repository

import android.content.Context
import com.example.mmtv.api.XCodesApi
import com.example.mmtv.database.*
import com.example.mmtv.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import com.example.mmtv.api.EpgParser

class MediaRepository(
    val api: XCodesApi,
    private val mediaDao: MediaDao,
    private val context: Context
) {
    private val gson = Gson()
    private val cacheDir = context.cacheDir
    private val epgParser = EpgParser()

    suspend fun getLiveCategories(user: String, pass: String, forceRefresh: Boolean = false): List<Category> {
        return getCachedOrFetch("live_categories", { api.getLiveCategories(user, pass) }, object : TypeToken<List<Category>>() {}, forceRefresh)
    }

    suspend fun getLiveStreams(user: String, pass: String, categoryId: String? = null, forceRefresh: Boolean = false): List<LiveStream> {
        val cacheKey = if (categoryId != null) "live_streams_$categoryId" else "live_streams_all"
        return getCachedOrFetch(cacheKey, { 
            api.getLiveStreams(user, pass)
        }, object : TypeToken<List<LiveStream>>() {}, forceRefresh).let { list ->
            if (categoryId != null) list.filter { it.categoryId == categoryId } else list
        }
    }

    suspend fun getMovieCategories(user: String, pass: String, forceRefresh: Boolean = false): List<Category> {
        return getCachedOrFetch("movie_categories", { api.getMovieCategories(user, pass) }, object : TypeToken<List<Category>>() {}, forceRefresh)
    }

    suspend fun getMovies(user: String, pass: String, categoryId: String? = null, forceRefresh: Boolean = false): List<Movie> {
        val cacheKey = if (categoryId != null) "movies_$categoryId" else "movies_all"
        return getCachedOrFetch(cacheKey, { 
            api.getMovies(user, pass)
        }, object : TypeToken<List<Movie>>() {}, forceRefresh).let { list ->
            if (categoryId != null) list.filter { it.categoryId == categoryId } else list
        }
    }

    suspend fun getSeriesCategories(user: String, pass: String, forceRefresh: Boolean = false): List<Category> {
        return getCachedOrFetch("series_categories", { api.getSeriesCategories(user, pass) }, object : TypeToken<List<Category>>() {}, forceRefresh)
    }

    suspend fun getSeries(user: String, pass: String, categoryId: String? = null, forceRefresh: Boolean = false): List<Series> {
        val cacheKey = if (categoryId != null) "series_$categoryId" else "series_all"
        return getCachedOrFetch(cacheKey, { 
            api.getSeries(user, pass)
        }, object : TypeToken<List<Series>>() {}, forceRefresh).let { list ->
            if (categoryId != null) list.filter { it.categoryId == categoryId } else list
        }
    }

    suspend fun getSeriesInfo(user: String, pass: String, seriesId: Int): SeriesInfoResponse {
        return api.getSeriesInfo(user, pass, seriesId)
    }

    suspend fun getGroupedLive(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val categories = getLiveCategories(user, pass, forceRefresh)
        val streams = getLiveStreams(user, pass, forceRefresh = forceRefresh)
        
        categories.map { cat ->
            GroupedMedia(
                title = cat.categoryName,
                categoryId = cat.categoryId,
                items = streams.filter { it.categoryId == cat.categoryId }.map { 
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
        
        categories.map { cat ->
            GroupedMedia(
                title = cat.categoryName,
                categoryId = cat.categoryId,
                items = movies.filter { it.categoryId == cat.categoryId }.map { 
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
        
        categories.map { cat ->
            GroupedMedia(
                title = cat.categoryName,
                categoryId = cat.categoryId,
                items = series.filter { it.categoryId == cat.categoryId }.map { 
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

    suspend fun getJustCategories(type: MediaType, user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        try {
            val categories = when (type) {
                MediaType.LIVE -> getLiveCategories(user, pass, forceRefresh)
                MediaType.MOVIE -> getMovieCategories(user, pass, forceRefresh)
                MediaType.SERIES -> getSeriesCategories(user, pass, forceRefresh)
            }
            categories.map { GroupedMedia(title = it.categoryName, categoryId = it.categoryId, items = emptyList()) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getMediaForCategory(type: MediaType, categoryId: String): List<MediaSource> = withContext(Dispatchers.IO) {
        mediaDao.getMediaByCategoryId(type, categoryId).map { it.toMediaSource() }
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
        mediaDao.updateFavoriteWithDate(item.id, newFav, favDate)
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

    suspend fun syncLibrary(user: String, pass: String) = withContext(Dispatchers.IO) {
        try {
            // 1. Live
            val liveCats = api.getLiveCategories(user, pass)
            val liveStreams = api.getLiveStreams(user, pass)
            val liveEntities = liveStreams.map { stream ->
                val cat = liveCats.find { it.categoryId == stream.categoryId }
                MediaEntity(
                    id = stream.streamId,
                    title = stream.name ?: "",
                    type = MediaType.LIVE,
                    categoryId = stream.categoryId ?: "",
                    categoryName = cat?.categoryName ?: "Okänd",
                    icon = stream.streamIcon,
                    epgId = stream.epgId,
                    addedDate = 0L
                )
            }
            mediaDao.deleteByType(MediaType.LIVE)
            mediaDao.insertAll(liveEntities)

            // 2. Movie
            val movieCats = api.getMovieCategories(user, pass)
            val movies = api.getMovies(user, pass)
            val movieEntities = movies.map { movie ->
                val cat = movieCats.find { it.categoryId == movie.categoryId }
                MediaEntity(
                    id = movie.streamId,
                    title = movie.name ?: "",
                    type = MediaType.MOVIE,
                    categoryId = movie.categoryId ?: "",
                    categoryName = cat?.categoryName ?: "Okänd",
                    icon = movie.streamIcon,
                    extension = movie.containerExtension ?: "mp4",
                    addedDate = parseDateToUnix(movie.added)
                )
            }
            mediaDao.deleteByType(MediaType.MOVIE)
            mediaDao.insertAll(movieEntities)

            // 3. Series
            val seriesCats = api.getSeriesCategories(user, pass)
            val seriesItems = api.getSeries(user, pass)
            val seriesEntities = seriesItems.map { item ->
                val cat = seriesCats.find { it.categoryId == item.categoryId }
                MediaEntity(
                    id = item.seriesId,
                    title = item.name ?: "",
                    type = MediaType.SERIES,
                    categoryId = item.categoryId ?: "",
                    categoryName = cat?.categoryName ?: "Okänd",
                    icon = item.cover,
                    addedDate = parseDateToUnix(item.lastModified)
                )
            }
            mediaDao.deleteByType(MediaType.SERIES)
            mediaDao.insertAll(seriesEntities)
            
            // Sync EPG also
            fetchAndStoreEpg(user, pass)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun fetchAndStoreEpg(user: String, pass: String, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val xmlFile = File(cacheDir, "full_epg.xml")
        val externalXmlFile = File(cacheDir, "external_epg.xml")
        val swedishXmlFile = File(cacheDir, "swedish_epg.xml")
        val now = System.currentTimeMillis()
        val twentyFourHours = 24 * 60 * 60 * 1000L
        
        val dbCount = mediaDao.getEpgCount()
        val useExternalSwedish = com.example.mmtv.api.SessionManager(context).getUseExternalSwedishEpg()

        // 1. Hantera Serverns EPG
        val shouldDownloadServer = forceRefresh || !xmlFile.exists() || (now - xmlFile.lastModified() > twentyFourHours)
        if (shouldDownloadServer || dbCount == 0) {
            try {
                if (shouldDownloadServer) {
                    val responseBody = api.getFullEpg(user, pass)
                    xmlFile.outputStream().use { output ->
                        responseBody.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                if (xmlFile.exists()) {
                    parseAndStore(xmlFile, true)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Hantera Extern Backup EPG
        val shouldDownloadExternal = forceRefresh || !externalXmlFile.exists() || (now - externalXmlFile.lastModified() > twentyFourHours)
        if (shouldDownloadExternal) {
            try {
                val url = "https://epgshare01.online/epgshare01/epg_ripper_SE1.xml.gz"
                val responseBody = api.getExternalEpg(url)
                
                GZIPInputStream(responseBody.byteStream()).use { gzipInput ->
                    externalXmlFile.outputStream().use { output ->
                        gzipInput.copyTo(output)
                    }
                }
                
                if (externalXmlFile.exists()) {
                    parseAndStore(externalXmlFile, false)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 3. Hantera den Svenska EPG:n (iptv-epg.org)
        if (useExternalSwedish) {
            val shouldDownloadSwedish = forceRefresh || !swedishXmlFile.exists() || (now - swedishXmlFile.lastModified() > twentyFourHours)
            if (shouldDownloadSwedish) {
                try {
                    val url = "https://iptv-epg.org/files/epg-se.xml"
                    val responseBody = api.getExternalEpg(url)
                    swedishXmlFile.outputStream().use { output ->
                        responseBody.byteStream().use { input -> input.copyTo(output) }
                    }
                    if (swedishXmlFile.exists()) {
                        parseAndStore(swedishXmlFile, false)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        // 4. Hantera Picons från GitHub
        syncPiconsFromGithub(forceRefresh)
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
        
        file.inputStream().use { input ->
            epgParser.parseStreaming(
                input,
                onChannelParsed = { id, displayName, icon ->
                    channels.add(ChannelEntity(id, displayName, icon))
                    if (displayName != null) channelNamesMap[id] = displayName
                    if (channels.size >= 100) {
                        mediaDao.insertChannels(ArrayList(channels))
                        channels.clear()
                    }
                },
                onProgrammeParsed = { epgId, it ->
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
            )
        }

        if (channels.isNotEmpty()) mediaDao.insertChannels(channels)
        if (batch.isNotEmpty()) {
            if (isFirstBatch && isClearFirst) mediaDao.clearEpg()
            mediaDao.insertEpg(batch)
        }
    }

    suspend fun getEpgForChannel(epgId: String?, channelName: String? = null): List<EpgListing> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val endLimit = now + (24 * 60 * 60)
        
        if (epgId != null) {
            val exactMatch = mediaDao.getEpgForChannelWithLimit(epgId, now, endLimit)
            if (exactMatch.isNotEmpty()) {
                return@withContext exactMatch.map { it.toEpgListing() }
            }
        }

        if (channelName != null) {
            val cleanName = cleanChannelName(channelName)
            val nameNoSpaces = cleanName.lowercase()
                .replace(" ", "")
                .replace(".", "")
                .replace("-", "")
            
            val fuzzyMatch = mediaDao.findEpgByFuzzyName(cleanName, nameNoSpaces, now, endLimit)
            if (fuzzyMatch.isNotEmpty()) {
                return@withContext fuzzyMatch.map { it.toEpgListing() }
            }
        }
        
        emptyList<EpgListing>()
    }

    suspend fun getIconForChannel(epgId: String?, channelName: String?): String? = withContext(Dispatchers.IO) {
        if (channelName != null) {
            val cleanName = cleanChannelName(channelName)
                .lowercase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")
            
            val githubPicon = mediaDao.getPiconByName(cleanName)
            if (githubPicon != null) return@withContext githubPicon.url
        }

        if (epgId != null) {
            val icon = mediaDao.getIconByEpgId(epgId)
            if (icon != null) return@withContext icon
        }
        
        if (channelName != null) {
            val cleanName = cleanChannelName(channelName)
            val nameNoSpaces = cleanName.lowercase()
                .replace(" ", "")
                .replace(".", "")
            val icon = mediaDao.findIconByFuzzyName(cleanName, nameNoSpaces)
            if (icon != null) return@withContext icon
        }
        
        null
    }

    private fun cleanChannelName(name: String): String {
        var cleaned = name
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("(?i)(\\d{3,4}p|H265|HEVC|HD|FHD|UHD|4K|SD|Sverige|Sweden)"), "")
            .replace("|", "")
            .replace(".", " ")

        // Ta bort vanliga IPTV-prefix (t.ex. "SE:", "NO:", "SE |", "SWE ")
        val prefixRegex = Regex("(?i)^([a-z]{1,3}\\s?[:|\\-]\\s?|SE\\s+|SWE\\s+)")
        cleaned = cleaned.replace(prefixRegex, "")

        return cleaned.trim()
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
        if (dateStr == null) return 0L
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            sdf.parse(dateStr)?.time?.div(1000) ?: 0L
        } catch (e: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                sdf.parse(dateStr)?.time?.div(1000) ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }

    private suspend fun <T> getCachedOrFetch(
        cacheKey: String, 
        fetcher: suspend () -> T, 
        typeToken: TypeToken<T>, 
        forceRefresh: Boolean = false
    ): T = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "$cacheKey.json")
        val now = System.currentTimeMillis()
        val twentyFourHours = 24 * 60 * 60 * 1000L
        
        if (!forceRefresh && cacheFile.exists() && (now - cacheFile.lastModified() < twentyFourHours)) {
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
