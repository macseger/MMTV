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
import com.example.mmtv.database.EpgEntity

class MediaRepository(private val api: XCodesApi, private val context: Context, private val database: MediaDatabase) {
    private val gson = Gson()
    private val cacheDir = context.cacheDir
    private val epgParser = EpgParser()
    private val mediaDao = database.mediaDao()
    private val CACHE_VERSION = "v5"

    suspend fun getGroupedLive(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        getCachedOrFetch("live_${user}_$CACHE_VERSION", {
            val streams = try { api.getLiveStreams(user, pass) } catch (e: Exception) { emptyList() }
            val categories = try { api.getLiveCategories(user, pass) } catch (e: Exception) { emptyList() }
            categories.map { category ->
                val categoryStreams = streams.filter { it.categoryId == category.categoryId }
                GroupedMedia(
                    title = category.categoryName ?: "Okänd kategori",
                    items = categoryStreams.map { s ->
                        MediaSource(
                            id = s.streamId,
                            title = s.name ?: "Okänd kanal",
                            icon = s.streamIcon,
                            type = MediaType.LIVE,
                            epgId = s.epgId
                        )
                    }
                )
            }.filter { it.items.isNotEmpty() }
        }, object : TypeToken<List<GroupedMedia>>() {}, forceRefresh)
    }

    suspend fun getGroupedMovies(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        getCachedOrFetch("movies_${user}_$CACHE_VERSION", {
            val movies = try { api.getMovies(user, pass) } catch (e: Exception) { emptyList() }
            val categories = try { api.getMovieCategories(user, pass) } catch (e: Exception) { emptyList() }
            categories.map { category ->
                val categoryMovies = movies.filter { it.categoryId == category.categoryId }
                GroupedMedia(
                    title = category.categoryName ?: "Okänd kategori",
                    items = categoryMovies.map { movie ->
                        MediaSource(
                            id = movie.streamId,
                            title = movie.name ?: "Okänd film",
                            icon = movie.streamIcon,
                            type = MediaType.MOVIE,
                            extension = movie.containerExtension,
                            rating = movie.rating
                        )
                    }
                )
            }.filter { it.items.isNotEmpty() }
        }, object : TypeToken<List<GroupedMedia>>() {}, forceRefresh)
    }

    suspend fun getGroupedSeries(user: String, pass: String, forceRefresh: Boolean = false): List<GroupedMedia> = withContext(Dispatchers.IO) {
        getCachedOrFetch("series_${user}_$CACHE_VERSION", {
            val series = try { api.getSeries(user, pass) } catch (e: Exception) { emptyList() }
            val categories = try { api.getSeriesCategories(user, pass) } catch (e: Exception) { emptyList() }
            categories.map { category ->
                val categorySeries = series.filter { it.categoryId == category.categoryId }
                GroupedMedia(
                    title = category.categoryName ?: "Okänd kategori",
                    items = categorySeries.map { s ->
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
                )
            }.filter { it.items.isNotEmpty() }
        }, object : TypeToken<List<GroupedMedia>>() {}, forceRefresh)
    }

    suspend fun getSeriesInfo(user: String, pass: String, seriesId: Int): SeriesInfoResponse = withContext(Dispatchers.IO) {
        api.getSeriesInfo(user, pass, seriesId)
    }

    suspend fun fetchAndStoreEpg(user: String, pass: String, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val xmlFile = File(cacheDir, "full_epg.xml")
        val now = System.currentTimeMillis()
        val twelveHours = 12 * 60 * 60 * 1000L
        
        val dbCount = mediaDao.getEpgCount()

        // Rensa gammal EPG (äldre än nu)
        mediaDao.deleteOldEpg(now / 1000)

        // Hämta ny om forceRefresh, fil saknas, fil är gammal ELLER om databasen är tom
        if (forceRefresh || !xmlFile.exists() || (now - xmlFile.lastModified() > twelveHours) || dbCount == 0) {
            try {
                if (forceRefresh || !xmlFile.exists() || (now - xmlFile.lastModified() > twelveHours)) {
                    val response = api.getFullEpg(user, pass)
                    xmlFile.writeBytes(response.bytes())
                }
                
                // Parsa och spara i DB i mindre batchar för att spara minne
                mediaDao.clearEpg()
                val batch = mutableListOf<EpgEntity>()
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
                            mediaDao.insertEpg(ArrayList(batch))
                            batch.clear()
                        }
                    }
                }

                if (batch.isNotEmpty()) {
                    mediaDao.insertEpg(batch)
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
    ): T {
        val cacheFile = File(cacheDir, "$cacheKey.json")
        val now = System.currentTimeMillis()
        val twelveHours = 12 * 60 * 60 * 1000L
        
        if (!forceRefresh && cacheFile.exists() && (now - cacheFile.lastModified() < twelveHours)) {
            try {
                val cached = gson.fromJson<T>(cacheFile.readText(), typeToken.type)
                if (cached != null) return cached
            } catch (e: Exception) {}
        }
        val data = fetcher()
        try { cacheFile.writeText(gson.toJson(data)) } catch (e: Exception) {}
        return data
    }
}
