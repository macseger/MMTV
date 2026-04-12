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
import com.example.mmtv.database.ChannelEntity

import java.util.zip.GZIPInputStream
import com.example.mmtv.api.M3uParser
import com.example.mmtv.api.M3uItem

class MediaRepository(val api: XCodesApi, private val context: Context, private val database: MediaDatabase) {
    private val gson = Gson()
    private val cacheDir = context.cacheDir
    private val epgParser = EpgParser()
    private val m3uParser = M3uParser()
    private val mediaDao = database.mediaDao()
    private val CACHE_VERSION = "v5"
    private var cachedM3uMap: Map<Int, M3uItem>? = null

    suspend fun getJustCategories(type: MediaType, user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        val dbCount = mediaDao.getCountByType(type)
        
        // Om vi tvingar refresh, ladda ner allt först och spara i DB
        if (forceRefresh || dbCount == 0) {
            syncMediaFromApi(type, user, pass)
        }

        // Hämta bara kategorierna från DB
        val categories = mediaDao.getCategoriesByType(type)
        categories.map { 
            GroupedMedia(title = it.categoryName, items = emptyList(), categoryId = it.categoryId) 
        }
    }

    suspend fun getMediaForCategory(type: MediaType, categoryId: String): List<MediaSource> = withContext(Dispatchers.IO) {
        mediaDao.getMediaByCategoryId(type, categoryId).map { it.toMediaSource() }
    }

    suspend fun syncMediaFromApi(type: MediaType, user: String, pass: String) {
        try {
            // Vi försöker först hämta M3U för att få rika picons/beskrivningar om det är första gången
            // Men vi behåller Xtream för den faktiska strukturen
            val categories = when(type) {
                MediaType.LIVE -> api.getLiveCategories(user, pass)
                MediaType.MOVIE -> api.getMovieCategories(user, pass)
                MediaType.SERIES -> api.getSeriesCategories(user, pass)
            }
            
            val items = when(type) {
                MediaType.LIVE -> api.getLiveStreams(user, pass)
                    .map { 
                        MediaSource(id = it.streamId, title = it.name, icon = it.streamIcon, type = type, epgId = it.epgId)
                            .toEntity(it.categoryId, categories.find { c -> c.categoryId == it.categoryId }?.categoryName)
                    }
                MediaType.MOVIE -> api.getMovies(user, pass)
                    .sortedByDescending { it.added?.toLongOrNull() ?: parseDateToUnix(it.added) }
                    .map { 
                        val addedTs = it.added?.toLongOrNull() ?: parseDateToUnix(it.added)
                        MediaSource(id = it.streamId, title = it.name, icon = it.streamIcon, type = type, extension = it.containerExtension, rating = it.rating, addedDate = addedTs)
                            .toEntity(it.categoryId, categories.find { c -> c.categoryId == it.categoryId }?.categoryName)
                            .copy(addedDate = addedTs)
                    }
                MediaType.SERIES -> api.getSeries(user, pass)
                    .sortedByDescending { it.lastModified?.toLongOrNull() ?: parseDateToUnix(it.lastModified) }
                    .map { 
                        val addedTs = it.lastModified?.toLongOrNull() ?: parseDateToUnix(it.lastModified)
                        MediaSource(id = it.seriesId, title = it.name, icon = it.cover, type = type, plot = it.plot, rating = it.rating, director = it.director, genre = it.genre, cast = it.cast, addedDate = addedTs)
                            .toEntity(it.categoryId, categories.find { c -> c.categoryId == it.categoryId }?.categoryName)
                            .copy(addedDate = addedTs)
                    }
            }

            if (items.isNotEmpty()) {
                val existingFavorites = mediaDao.getFavorites().associateBy { it.id }
                
                // Berika med M3U-data om det finns lokalt
                val m3uMap = getLocalM3uMap()
                
                val itemsToInsert = items.mapIndexed { index, entity ->
                    val fav = existingFavorites[entity.id]
                    val m3uData = m3uMap[entity.id]
                    
                    entity.copy(
                        isFavorite = fav?.isFavorite ?: false,
                        favoriteDate = fav?.favoriteDate ?: 0L,
                        categoryOrder = categories.indexOfFirst { it.categoryId == entity.categoryId }.let { if (it == -1) 999 else it },
                        itemOrder = index,
                        // Använd M3U ikon/beskrivning om serverns är tom
                        icon = if (entity.icon.isNullOrEmpty()) m3uData?.logo ?: entity.icon else entity.icon,
                        plot = if (entity.plot.isNullOrEmpty()) m3uData?.title ?: entity.plot else entity.plot // M3U beskrivning kan vara titeln ibland
                    )
                }
                mediaDao.deleteByType(type)
                mediaDao.insertAll(itemsToInsert)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun getLocalM3uMap(): Map<Int, M3uItem> = withContext(Dispatchers.IO) {
        cachedM3uMap?.let { return@withContext it }
        
        val file = File(cacheDir, "provisioning.m3u")
        if (!file.exists()) return@withContext emptyMap()
        
        try {
            val map = file.inputStream().use { m3uParser.parse(it) }.associateBy { it.streamId ?: 0 }
            cachedM3uMap = map
            map
        } catch (e: Exception) { 
            emptyMap() 
        }
    }

    suspend fun performInitialProvisioning(user: String, pass: String, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("Laddar ner optimerad metadata...")
        val m3uFile = File(cacheDir, "provisioning.m3u")
        try {
            val response = api.getM3uPlus(user, pass)
            val body = response
            val totalBytes = body.contentLength()
            val totalMb = if (totalBytes > 0) totalBytes / 1024 / 1024 else -1
            
            m3uFile.outputStream().use { output ->
                body.byteStream().use { input -> 
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastUpdate = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            val currentMb = totalRead / 1024 / 1024
                            val progressText = if (totalMb > 0) {
                                "Laddar ner metadata: ${currentMb}MB / ${totalMb}MB..."
                            } else {
                                "Laddar ner metadata: ${currentMb}MB..."
                            }
                            onProgress(progressText)
                            lastUpdate = now
                        }
                    }
                }
            }
            
            onProgress("Analyserar ${m3uFile.length() / 1024 / 1024}MB metadata...")
            // Tvinga parsning här så det är klart till nästa steg
            getLocalM3uMap()
            onProgress("Optimering klar!")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
                    title = title,
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

    private fun MediaEntity.toMediaSource() = MediaSource(
        id = id,
        title = title, // Behåll originalnamnet från DB
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
        title = title ?: "", // Spara originalnamnet till DB
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
            val sortedCategoryMovies = categoryMovies.sortedByDescending { it.added?.toLongOrNull() ?: parseDateToUnix(it.added) }
            val mediaSources = sortedCategoryMovies.map { movie ->
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
            allEntities.addAll(sortedCategoryMovies.mapIndexed { itemIdx, movie ->
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
            val sortedCategorySeries = categorySeries.sortedByDescending { it.lastModified?.toLongOrNull() ?: parseDateToUnix(it.lastModified) }
            val mediaSources = sortedCategorySeries.map { s ->
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
            allEntities.addAll(sortedCategorySeries.mapIndexed { itemIdx, s ->
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
        val channelsToInsert = mutableListOf<ChannelEntity>()
        var isFirstBatch = true
        
        file.inputStream().use { input ->
            epgParser.parseStreaming(
                input,
                onChannelParsed = { id, displayName, icon ->
                    channelsToInsert.add(ChannelEntity(id, displayName, icon))
                    if (channelsToInsert.size >= 100) {
                        mediaDao.insertChannels(ArrayList(channelsToInsert))
                        channelsToInsert.clear()
                    }
                },
                onProgrammeParsed = { epgId, it ->
                    // Vi behöver veta ikon och namn för detta program. 
                    // Eftersom vi streamar kan vi inte vara säkra på att vi sett kanalen än om XML:en är dåligt strukturerad,
                    // men oftast kommer <channel> före <programme>.
                    batch.add(EpgEntity(
                        epgId = epgId,
                        title = it.title,
                        description = it.description,
                        startTimestamp = it.startTimestamp ?: 0L,
                        stopTimestamp = it.stopTimestamp ?: 0L
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

        if (channelsToInsert.isNotEmpty()) {
            mediaDao.insertChannels(channelsToInsert)
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
        // 1. Kolla exakt EPG ID
        if (epgId != null) {
            val icon = mediaDao.getIconByEpgId(epgId)
            if (icon != null) return@withContext icon
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
            val icon = mediaDao.findIconByFuzzyName(cleanName, nameNoSpaces)
            if (icon != null) return@withContext icon
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
        stopTimestamp = stopTimestamp,
        icon = icon
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
