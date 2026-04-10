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

class MediaRepository(private val api: XCodesApi, private val context: Context, private val database: MediaDatabase) {
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
        
        val allEntities = mutableListOf<MediaEntity>()
        val result = categories.mapIndexed { catIdx, category ->
            val categoryStreams = streamsByCategory[category.categoryId] ?: emptyList()
            val mediaSources = categoryStreams.mapIndexed { itemIdx, s ->
                MediaSource(
                    id = s.streamId,
                    title = s.name ?: "Okänd kanal",
                    icon = s.streamIcon,
                    type = MediaType.LIVE,
                    epgId = s.epgId
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

        val allEntities = mutableListOf<MediaEntity>()
        val result = categories.mapIndexed { catIdx, category ->
            val categoryMovies = moviesByCategory[category.categoryId] ?: emptyList()
            val mediaSources = categoryMovies.map { movie ->
                MediaSource(
                    id = movie.streamId,
                    title = movie.name ?: "Okänd film",
                    icon = movie.streamIcon,
                    type = MediaType.MOVIE,
                    extension = movie.containerExtension,
                    rating = movie.rating
                )
            }
            allEntities.addAll(mediaSources.mapIndexed { itemIdx, it -> 
                it.toEntity(category.categoryId, category.categoryName).copy(
                    categoryOrder = catIdx,
                    itemOrder = itemIdx
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

        val allEntities = mutableListOf<MediaEntity>()
        val result = categories.mapIndexed { catIdx, category ->
            val categorySeries = seriesByCategory[category.categoryId] ?: emptyList()
            val mediaSources = categorySeries.map { s ->
                MediaSource(
                    id = s.seriesId,
                    title = s.name ?: "Okänd serie",
                    icon = s.cover,
                    type = MediaType.SERIES,
                    plot = s.plot,
                    rating = s.rating,
                    director = s.director,
                    genre = s.genre,
                    cast = s.cast
                )
            }
            allEntities.addAll(mediaSources.mapIndexed { itemIdx, it -> 
                it.toEntity(category.categoryId, category.categoryName).copy(
                    categoryOrder = catIdx,
                    itemOrder = itemIdx
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
        val now = System.currentTimeMillis()
        val twelveHours = 12 * 60 * 60 * 1000L
        
        val dbCount = mediaDao.getEpgCount()

        // Hämta ny om forceRefresh, fil saknas eller fil är gammal
        val shouldDownload = forceRefresh || !xmlFile.exists() || (now - xmlFile.lastModified() > twelveHours)
        
        if (shouldDownload || dbCount == 0) {
            try {
                if (shouldDownload) {
                    val responseBody = api.getFullEpg(user, pass)
                    xmlFile.outputStream().use { output ->
                        responseBody.byteStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
                
                // Parsa och spara i DB i mindre batchar
                // VIKTIGT: Rensa bara om vi faktiskt har en fil att läsa in
                if (xmlFile.exists()) {
                    val batch = mutableListOf<EpgEntity>()
                    var isFirstBatch = true
                    
                    xmlFile.inputStream().use { input ->
                        epgParser.parseStreaming(input) { epgId, it ->
                            batch.add(EpgEntity(
                                epgId = epgId,
                                title = it.title,
                                description = it.description,
                                startTimestamp = it.startTimestamp ?: 0L,
                                stopTimestamp = it.stopTimestamp ?: 0L
                            ))
                            
                            if (batch.size >= 500) {
                                if (isFirstBatch) {
                                    mediaDao.clearEpg() // Rensa först när vi vet att vi har ny data att skriva
                                    isFirstBatch = false
                                }
                                mediaDao.insertEpg(ArrayList(batch))
                                batch.clear()
                            }
                        }
                    }

                    if (batch.isNotEmpty()) {
                        if (isFirstBatch) mediaDao.clearEpg()
                        mediaDao.insertEpg(batch)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun getEpgForChannel(epgId: String): List<EpgListing> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        mediaDao.getEpgForChannel(epgId, now).map {
            EpgListing(
                id = it.id.toString(),
                epgId = it.epgId,
                title = it.title,
                description = it.description,
                start = null,
                end = null,
                startTimestamp = it.startTimestamp,
                stopTimestamp = it.stopTimestamp
            )
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
