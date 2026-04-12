package com.example.mmtv.repository

import android.content.Context
import com.example.mmtv.api.EpgParser
import com.example.mmtv.api.XCodesApi
import com.example.mmtv.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import com.example.mmtv.database.MediaDatabase
import com.example.mmtv.database.MediaEntity
import com.example.mmtv.database.EpgEntity

import java.util.zip.GZIPInputStream

class MediaRepository(val api: XCodesApi, private val context: Context, private val database: MediaDatabase) {
    private val gson = Gson()
    private val cacheDir = context.cacheDir
    private val epgParser = EpgParser()
    private val mediaDao = database.mediaDao()
    private val CACHE_VERSION = "v5"

    suspend fun getGroupedLive(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val dbCount = mediaDao.getCountByType(MediaType.LIVE)
        
        if (!forceRefresh && dbCount > 0) {
            val entities = mediaDao.getMediaByType(MediaType.LIVE)
            return@withContext withContext(Dispatchers.Default) {
                entities.groupBy { it.categoryId ?: it.categoryName ?: "Okänd" }
                    .values
                    .map { items ->
                        val first = items.first()
                        GroupedMedia(
                            title = first.categoryName ?: "Okänd",
                            items = items.map { it.toMediaSource() }
                        )
                    }
                    .sortedBy { group -> 
                        entities.firstOrNull { it.categoryName == group.title }?.categoryOrder ?: 999
                    }
            }
        }

        val streams = try { api.getLiveStreams(user, pass) } catch (e: Exception) { emptyList() }
        val categories = try { api.getLiveCategories(user, pass) } catch (e: Exception) { emptyList() }
        
        if (streams.isEmpty() && categories.isEmpty() && dbCount > 0) {
             val entities = mediaDao.getMediaByType(MediaType.LIVE)
             return@withContext withContext(Dispatchers.Default) {
                 entities.groupBy { it.categoryId ?: it.categoryName ?: "Okänd" }
                    .values
                    .map { items ->
                        val first = items.first()
                        GroupedMedia(
                            title = first.categoryName ?: "Okänd",
                            items = items.map { it.toMediaSource() }
                        )
                    }
             }
        }

        val streamsByCategory = streams.groupBy { it.categoryId }
        
        val existingFavorites = mediaDao.getFavorites().map { it.id }.toSet()
        
        val allEntities = mutableListOf<MediaEntity>()
        val result = categories.mapIndexed { catIdx, category ->
            val categoryStreams = streamsByCategory[category.categoryId] ?: emptyList()
            val mediaSources = categoryStreams.mapIndexed { itemIdx, s ->
                val title = s.name ?: "Okänd kanal"
                MediaSource(
                    id = s.streamId,
                    title = cleanName(title, MediaType.LIVE),
                    icon = s.streamIcon,
                    type = MediaType.LIVE,
                    epgId = s.epgId,
                    isFavorite = existingFavorites.contains(s.streamId),
                    addedDate = 0L
                )
            }
            
            allEntities.addAll(mediaSources.mapIndexed { itemIdx, it -> 
                it.toEntity(category.categoryId, category.categoryName).copy(
                    categoryOrder = catIdx,
                    itemOrder = itemIdx
                )
            })
            
            GroupedMedia(
                title = category.categoryName ?: "Okänd kategori",
                items = mediaSources
            )
        }.filter { it.items.isNotEmpty() }

        if (allEntities.isNotEmpty()) {
            mediaDao.deleteByType(MediaType.LIVE)
            mediaDao.insertAll(allEntities)
        }
        result
    }

    private fun cleanName(name: String?, type: MediaType): String? {
        if (type != MediaType.LIVE || name == null) return name
        return when {
            name.startsWith("SE:", ignoreCase = true) -> name.substring(3).trim()
            name.startsWith("SE :", ignoreCase = true) -> name.substring(4).trim()
            name.startsWith("SE |", ignoreCase = true) -> name.substring(4).trim()
            name.startsWith("SE|", ignoreCase = true) -> name.substring(3).trim()
            else -> name
        }
    }

    private fun MediaEntity.toMediaSource() = MediaSource(
        id = id,
        title = cleanName(title, type),
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

    suspend fun getGroupedMovies(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val dbCount = mediaDao.getCountByType(MediaType.MOVIE)
        
        if (!forceRefresh && dbCount > 0) {
            val entities = mediaDao.getMediaByType(MediaType.MOVIE)
            return@withContext withContext(Dispatchers.Default) {
                entities.groupBy { it.categoryId ?: it.categoryName ?: "Okänd" }
                    .values
                    .map { items ->
                        val first = items.first()
                        GroupedMedia(
                            title = first.categoryName ?: "Okänd",
                            items = items.map { it.toMediaSource() }
                        )
                    }
                    .sortedBy { group -> 
                        entities.firstOrNull { it.categoryName == group.title }?.categoryOrder ?: 999 
                    }
            }
        }

        val movies = try { api.getMovies(user, pass) } catch (e: Exception) { emptyList() }
        val categories = try { api.getMovieCategories(user, pass) } catch (e: Exception) { emptyList() }
        
        if (movies.isEmpty() && categories.isEmpty() && dbCount > 0) {
            val entities = mediaDao.getMediaByType(MediaType.MOVIE)
            return@withContext withContext(Dispatchers.Default) {
                entities.groupBy { it.categoryId ?: it.categoryName ?: "Okänd" }
                    .values
                    .map { items ->
                        val first = items.first()
                        GroupedMedia(
                            title = first.categoryName ?: "Okänd",
                            items = items.map { it.toMediaSource() }
                        )
                    }
            }
        }

        val moviesByCategory = movies.groupBy { it.categoryId }
        val existingFavorites = mediaDao.getFavorites().map { it.id }.toSet()

        val allEntities = mutableListOf<MediaEntity>()
        val result = categories.mapIndexed { catIdx, category ->
            val categoryMovies = moviesByCategory[category.categoryId] ?: emptyList()
                val mediaSources = categoryMovies.map { movie ->
                    val addedTs = movie.added?.toLongOrNull() ?: parseDateToUnix(movie.added)
                    MediaSource(
                        id = movie.streamId,
                        title = movie.name ?: "Okänd film",
                        icon = movie.streamIcon,
                        type = MediaType.MOVIE,
                        extension = movie.containerExtension,
                        rating = movie.rating,
                        isFavorite = existingFavorites.contains(movie.streamId),
                        addedDate = addedTs
                    )
                }
                allEntities.addAll(categoryMovies.mapIndexed { itemIdx, movie ->
                    val addedTs = movie.added?.toLongOrNull() ?: parseDateToUnix(movie.added)
                    val media = MediaSource(
                        id = movie.streamId,
                        title = movie.name ?: "Okänd film",
                        icon = movie.streamIcon,
                        type = MediaType.MOVIE,
                        extension = movie.containerExtension,
                        rating = movie.rating,
                        isFavorite = existingFavorites.contains(movie.streamId),
                        addedDate = addedTs
                    )
                    media.toEntity(category.categoryId, category.categoryName).copy(
                        categoryOrder = catIdx,
                        itemOrder = itemIdx,
                        addedDate = addedTs
                    )
                })
            GroupedMedia(title = category.categoryName ?: "Okänd kategori", items = mediaSources)
        }.filter { it.items.isNotEmpty() }

        if (allEntities.isNotEmpty()) {
            mediaDao.deleteByType(MediaType.MOVIE)
            mediaDao.insertAll(allEntities)
        }
        result
    }

    suspend fun getGroupedSeries(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val dbCount = mediaDao.getCountByType(MediaType.SERIES)
        
        if (!forceRefresh && dbCount > 0) {
            val entities = mediaDao.getMediaByType(MediaType.SERIES)
            return@withContext withContext(Dispatchers.Default) {
                entities.groupBy { it.categoryId ?: it.categoryName ?: "Okänd" }
                    .values
                    .map { items ->
                        val first = items.first()
                        GroupedMedia(
                            title = first.categoryName ?: "Okänd",
                            items = items.map { it.toMediaSource() }
                        )
                    }
                    .sortedBy { group -> 
                        entities.firstOrNull { it.categoryName == group.title }?.categoryOrder ?: 999
                    }
            }
        }

        val series = try { api.getSeries(user, pass) } catch (e: Exception) { emptyList() }
        val categories = try { api.getSeriesCategories(user, pass) } catch (e: Exception) { emptyList() }

        if (series.isEmpty() && categories.isEmpty() && dbCount > 0) {
            val entities = mediaDao.getMediaByType(MediaType.SERIES)
            return@withContext withContext(Dispatchers.Default) {
                entities.groupBy { it.categoryId ?: it.categoryName ?: "Okänd" }
                    .values
                    .map { items ->
                        val first = items.first()
                        GroupedMedia(
                            title = first.categoryName ?: "Okänd",
                            items = items.map { it.toMediaSource() }
                        )
                    }
            }
        }

        val seriesByCategory = series.groupBy { it.categoryId }
        val existingFavorites = mediaDao.getFavorites().map { it.id }.toSet()

        val allEntities = mutableListOf<MediaEntity>()
        val result = categories.mapIndexed { catIdx, category ->
            val categorySeries = seriesByCategory[category.categoryId] ?: emptyList()
            val mediaSources = categorySeries.map { s ->
                val addedTs = s.lastModified?.toLongOrNull() ?: parseDateToUnix(s.lastModified)
                MediaSource(
                    id = s.seriesId,
                    title = s.name ?: "Okänd serie",
                    icon = s.cover,
                    type = MediaType.SERIES,
                    plot = s.plot,
                    rating = s.rating,
                    director = s.director,
                    genre = s.genre,
                    cast = s.cast,
                    isFavorite = existingFavorites.contains(s.seriesId),
                    addedDate = addedTs
                )
            }
            allEntities.addAll(categorySeries.mapIndexed { itemIdx, s ->
                val addedTs = s.lastModified?.toLongOrNull() ?: parseDateToUnix(s.lastModified)
                val media = MediaSource(
                    id = s.seriesId,
                    title = s.name ?: "Okänd serie",
                    icon = s.cover,
                    type = MediaType.SERIES,
                    plot = s.plot,
                    rating = s.rating,
                    director = s.director,
                    genre = s.genre,
                    cast = s.cast,
                    isFavorite = existingFavorites.contains(s.seriesId),
                    addedDate = addedTs
                )
                media.toEntity(category.categoryId, category.categoryName).copy(
                    categoryOrder = catIdx,
                    itemOrder = itemIdx,
                    addedDate = addedTs
                )
            })
            GroupedMedia(title = category.categoryName ?: "Okänd kategori", items = mediaSources)
        }.filter { it.items.isNotEmpty() }

        if (allEntities.isNotEmpty()) {
            mediaDao.deleteByType(MediaType.SERIES)
            mediaDao.insertAll(allEntities)
        }
        result
    }

    suspend fun getSeriesInfo(user: String, pass: String, seriesId: Int): SeriesInfoResponse = withContext(Dispatchers.IO) {
        api.getSeriesInfo(user, pass, seriesId)
    }

    suspend fun fetchAndStoreEpg(user: String, pass: String, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val xmlFile = File(cacheDir, "full_epg.xml")
        val externalXmlFile = File(cacheDir, "external_epg.xml")
        val now = System.currentTimeMillis()
        val twentyFourHours = 24 * 60 * 60 * 1000L
        
        val dbCount = mediaDao.getEpgCount()

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

        // 2. Hantera Extern Backup EPG (epgshare01)
        val shouldDownloadExternal = forceRefresh || !externalXmlFile.exists() || (now - externalXmlFile.lastModified() > twentyFourHours)
        if (shouldDownloadExternal) {
            try {
                val url = "https://epgshare01.online/epgshare01/epg_ripper_SE1.xml.gz"
                val responseBody = api.getExternalEpg(url)
                
                // Packa upp GZIP och spara som XML
                GZIPInputStream(responseBody.byteStream()).use { gzipInput ->
                    externalXmlFile.outputStream().use { output ->
                        gzipInput.copyTo(output)
                    }
                }
                
                if (externalXmlFile.exists()) {
                    // Vi rensar INTE databasen här, vi bara fyller på (isClearFirst = false)
                    parseAndStore(externalXmlFile, false)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private suspend fun parseAndStore(file: File, isClearFirst: Boolean) {
        val batch = mutableListOf<EpgEntity>()
        val channelIcons = mutableMapOf<String, String>()
        val channelNames = mutableMapOf<String, String>()
        var isFirstBatch = true
        
        file.inputStream().use { input ->
            epgParser.parseStreaming(
                input,
                onChannelParsed = { id, displayName, icon ->
                    if (icon != null) channelIcons[id] = icon
                    if (displayName != null) channelNames[id] = displayName
                },
                onProgrammeParsed = { epgId, it ->
                    batch.add(EpgEntity(
                        epgId = epgId,
                        channelName = channelNames[epgId],
                        title = it.title,
                        description = it.description,
                        startTimestamp = it.startTimestamp ?: 0L,
                        stopTimestamp = it.stopTimestamp ?: 0L,
                        icon = channelIcons[epgId]
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

        if (batch.isNotEmpty()) {
            if (isFirstBatch && isClearFirst) mediaDao.clearEpg()
            mediaDao.insertEpg(batch)
        }
    }

    suspend fun getEpgForChannel(epgId: String?, channelName: String? = null): List<EpgListing> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val endLimit = now + (24 * 60 * 60) // Hämta 24 timmar framåt
        
        // 1. Försök med exakt ID först
        if (epgId != null) {
            val exactMatch = mediaDao.getEpgForChannelWithLimit(epgId, now, endLimit)
            if (exactMatch.isNotEmpty()) {
                return@withContext exactMatch.map { it.toEpgListing() }
            }
        }

        // 2. Om ingen träff, försök med Fuzzy Matching på namnet
        if (channelName != null) {
            val cleanName = channelName
                .replace(Regex("\\(.*?\\)"), "") // Ta bort (S) etc
                .replace(Regex("\\[.*?\\]"), "") // Ta bort [SE] etc
                .replace("HD", "", ignoreCase = true)
                .replace("FHD", "", ignoreCase = true)
                .replace("4K", "", ignoreCase = true)
                .replace("SD", "", ignoreCase = true)
                .replace("Sverige", "", ignoreCase = true)
                .replace("|", "")
                .trim()
            
            val nameNoSpaces = cleanName.replace(" ", "")
            
            val fuzzyMatch = mediaDao.findEpgByFuzzyName(cleanName, nameNoSpaces, now, endLimit)
            if (fuzzyMatch.isNotEmpty()) {
                return@withContext fuzzyMatch.map { it.toEpgListing() }
            }
        }

        emptyList()
    }

    suspend fun getIconForChannel(epgId: String?, channelName: String?): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val endLimit = now + (48 * 60 * 60)
        
        // 1. Kolla exakt EPG ID
        if (epgId != null) {
            val entity = mediaDao.getEpgForChannelWithLimit(epgId, now - 86400, endLimit).firstOrNull { it.icon != null }
            if (entity?.icon != null) return@withContext entity.icon
        }
        
        // 2. Kolla fuzzy på namnet
        if (channelName != null) {
            val cleanName = channelName
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("\\[.*?\\]"), "")
                .replace("HD", "", ignoreCase = true)
                .replace("FHD", "", ignoreCase = true)
                .replace("4K", "", ignoreCase = true)
                .replace("SD", "", ignoreCase = true)
                .replace("Sverige", "", ignoreCase = true)
                .replace("|", "")
                .trim()
            
            val nameNoSpaces = cleanName.replace(" ", "")
            val entity = mediaDao.findEpgByFuzzyName(cleanName, nameNoSpaces, now - 86400, endLimit).firstOrNull { it.icon != null }
            if (entity?.icon != null) return@withContext entity.icon
        }
        
        null
    }

    private fun EpgEntity.toEpgListing() = EpgListing(
        id = id.toString(),
        epgId = epgId,
        title = title,
        description = description,
        start = null,
        end = null,
        startTimestamp = startTimestamp,
        stopTimestamp = stopTimestamp
    )

    private fun parseDateToUnix(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            // Försök med formatet YYYY-MM-DD HH:MM:SS som är vanligt i IPTV-listor
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            sdf.parse(dateStr)?.time?.div(1000) ?: 0L
        } catch (e: Exception) {
            try {
                // Försök med bara datum YYYY-MM-DD
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
                // Använd en BufferedSource för snabbare läsning av stora filer
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
