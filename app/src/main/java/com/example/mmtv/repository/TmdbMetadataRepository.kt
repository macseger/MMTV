package com.example.mmtv.repository

import android.util.Log
import android.content.Context
import com.example.mmtv.BuildConfig
import com.example.mmtv.api.TmdbApi
import com.example.mmtv.model.DetailMetadata
import com.example.mmtv.model.HomeDiscoveryItem
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.TmdbCredits
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class TmdbMatchCandidate(
    val id: Int,
    val localizedTitle: String?,
    val originalTitle: String?,
    val releaseDate: String?
)

enum class TmdbMatchOutcome {
    ACCEPTED,
    NO_MATCH,
    REJECTED_YEAR,
    REJECTED_AMBIGUITY
}

data class TmdbMatchEvaluation(
    val candidate: TmdbMatchCandidate?,
    val candidateCount: Int,
    val exactMatchCount: Int,
    val yearRejectedCount: Int,
    val outcome: TmdbMatchOutcome
)

object TmdbTitleMatcher {
    private val leadingDecoration = Regex(
        "^(?:(?:VOD|MOVIE|FILM|SWE|SWEDISH|SVENSKA|NORDIC|SE|SV)\\s*(?:[|:-])\\s*|" +
            "(?:NC|2160P|1080P|720P|576P|4K|UHD|FHD|HD|SD|BLURAY|WEB-DL|WEBRIP)" +
            "(?:\\s*[|:-]\\s*|\\s+))",
        RegexOption.IGNORE_CASE
    )
    private val trailingYear = Regex("\\s*[\\[(]?(?:19|20)\\d{2}[\\])]?[\\s._-]*$")
    private val trailingNoise = Regex(
        "(?:[\\s._|/-]+(?:2160P|1080P|720P|576P|4K|UHD|FHD|HD|SD|BLURAY|WEB-DL|WEBRIP|SWE|SWEDISH|SVENSKA|MULTI|NORDIC|DUBBED|SUBBED))+$",
        RegexOption.IGNORE_CASE
    )
    private val bracketGroup = Regex("""(?:\[([^]]+)]|\(([^)]+)\))""")
    private val noiseToken = Regex(
        "^(?:(?:19|20)\\d{2}|2160P|1080P|720P|576P|4K|UHD|FHD|HD|SD|BLURAY|WEB-DL|WEBRIP|SWE|SWEDISH|SVENSKA|MULTI|NORDIC|DUBBED|SUBBED)$",
        RegexOption.IGNORE_CASE
    )

    fun cleanSearchTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        while (true) {
            val withoutDecoration = title.replaceFirst(leadingDecoration, "").trimStart()
            if (withoutDecoration == title) break
            title = withoutDecoration
        }
        title = title.replace(bracketGroup) { match ->
            val content = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            if (noiseToken.matches(content.trim())) " " else match.value
        }
        repeat(3) {
            title = title.replace(trailingNoise, "").replace(trailingYear, "").trim()
        }
        return title.replace(Regex("\\s+"), " ").trim(' ', '-', '_', '|')
    }

    fun normalizeTitle(rawTitle: String): String {
        val decomposed = Normalizer.normalize(cleanSearchTitle(rawTitle), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun extractYear(value: String?): Int? = value
        ?.let { Regex("(?<!\\d)((?:19|20)\\d{2})(?!\\d)").find(it) }
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    fun findConfidentMatch(
        sourceTitle: String,
        sourceYear: Int?,
        candidates: List<TmdbMatchCandidate>
    ): TmdbMatchCandidate? = evaluateMatch(sourceTitle, sourceYear, candidates).candidate

    fun evaluateMatch(
        sourceTitle: String,
        sourceYear: Int?,
        candidates: List<TmdbMatchCandidate>
    ): TmdbMatchEvaluation {
        val normalizedSource = normalizeTitle(sourceTitle)
        if (normalizedSource.isBlank()) {
            return TmdbMatchEvaluation(null, candidates.size, 0, 0, TmdbMatchOutcome.NO_MATCH)
        }

        var exactMatchCount = 0
        var yearRejectedCount = 0
        val distinctCandidates = candidates.distinctBy { it.id }
        val scored = distinctCandidates.mapNotNull { candidate ->
            val titleMatches = sequenceOf(candidate.localizedTitle, candidate.originalTitle)
                .filterNotNull()
                .any { normalizeTitle(it) == normalizedSource }
            if (!titleMatches) return@mapNotNull null
            exactMatchCount++

            val candidateYear = extractYear(candidate.releaseDate)
            if (sourceYear != null && candidateYear != null && abs(sourceYear - candidateYear) > 1) {
                yearRejectedCount++
                return@mapNotNull null
            }
            val score = 100 + when {
                sourceYear == null -> 0
                candidateYear == sourceYear -> 20
                candidateYear != null -> 10
                else -> 0
            }
            candidate to score
        }.sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return TmdbMatchEvaluation(
            candidate = null,
            candidateCount = distinctCandidates.size,
            exactMatchCount = exactMatchCount,
            yearRejectedCount = yearRejectedCount,
            outcome = if (yearRejectedCount > 0) TmdbMatchOutcome.REJECTED_YEAR else TmdbMatchOutcome.NO_MATCH
        )
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && best.second - runnerUp.second < 10) {
            return TmdbMatchEvaluation(
                null,
                distinctCandidates.size,
                exactMatchCount,
                yearRejectedCount,
                TmdbMatchOutcome.REJECTED_AMBIGUITY
            )
        }
        return TmdbMatchEvaluation(
            best.first,
            distinctCandidates.size,
            exactMatchCount,
            yearRejectedCount,
            TmdbMatchOutcome.ACCEPTED
        )
    }
}

enum class DiscoveryRefreshStatus {
    SUCCESS,
    FAILURE
}

data class DiscoveryRefreshResult(
    val status: DiscoveryRefreshStatus,
    val items: List<HomeDiscoveryItem> = emptyList(),
    val candidateCount: Int = 0
)

data class PersistentDiscoverySnapshot(
    val movies: List<HomeDiscoveryItem> = emptyList(),
    val series: List<HomeDiscoveryItem> = emptyList(),
    val moviesCachedAt: Long? = null,
    val seriesCachedAt: Long? = null
)

class TmdbMetadataRepository(
    private val api: TmdbApi?,
    context: Context? = null
) {
    private data class CacheKey(val type: MediaType, val normalizedTitle: String, val year: Int?)

    private val memoryCache = ConcurrentHashMap<CacheKey, DetailMetadata>()
    private val gson = Gson()
    private val discoveryCacheFile = context?.applicationContext?.filesDir
        ?.resolve("tmdb_home_discovery.json")
    private val discoveryCacheLock = Any()

    private data class PersistentDiscoveryItem(
        val tmdbId: Int,
        val localMediaId: Int,
        val title: String,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val rating: Double? = null
    )

    private data class PersistentDiscoveryBucket(
        val cachedAt: Long? = null,
        val items: List<PersistentDiscoveryItem> = emptyList()
    )

    private data class PersistentDiscoveryFile(
        val movies: PersistentDiscoveryBucket? = null,
        val series: PersistentDiscoveryBucket? = null
    )

    private sealed interface DiscoveryFetchResult {
        data class Success(val candidateCount: Int, val items: List<HomeDiscoveryItem>) : DiscoveryFetchResult
        data object Failure : DiscoveryFetchResult
    }

    companion object {
        const val DISCOVERY_FRESHNESS_MS = 24 * 60 * 60 * 1000L
    }

    suspend fun discoverMovies(localMedia: List<MediaSource>): List<HomeDiscoveryItem> =
        refreshMovies(localMedia).items

    suspend fun discoverSeries(localMedia: List<MediaSource>): List<HomeDiscoveryItem> =
        refreshSeries(localMedia).items

    fun loadPersistentDiscovery(
        localMovies: List<MediaSource>,
        localSeries: List<MediaSource>
    ): PersistentDiscoverySnapshot {
        val file = readPersistentFile()
        if (file == null) {
            logPersistent("cache_miss")
            return PersistentDiscoverySnapshot()
        }
        val movies = reconnect(file.movies, localMovies, MediaType.MOVIE)
        val series = reconnect(file.series, localSeries, MediaType.SERIES)
        logPersistentBucket("Movies", file.movies, movies)
        logPersistentBucket("Series", file.series, series)
        return PersistentDiscoverySnapshot(
            movies = movies,
            series = series,
            moviesCachedAt = file.movies?.cachedAt,
            seriesCachedAt = file.series?.cachedAt
        )
    }

    fun isDiscoveryFresh(cachedAt: Long?, now: Long = System.currentTimeMillis()): Boolean =
        cachedAt != null && now - cachedAt in 0 until DISCOVERY_FRESHNESS_MS

    suspend fun refreshMovies(localMedia: List<MediaSource>): DiscoveryRefreshResult =
        refresh(MediaType.MOVIE, localMedia)

    suspend fun refreshSeries(localMedia: List<MediaSource>): DiscoveryRefreshResult =
        refresh(MediaType.SERIES, localMedia)

    private suspend fun refresh(type: MediaType, localMedia: List<MediaSource>): DiscoveryRefreshResult {
        logDiscoveryRefresh(type, "start")
        val fetched = discover(type, localMedia)
        if (fetched is DiscoveryFetchResult.Failure) {
            logDiscoveryRefresh(type, "failure")
            return DiscoveryRefreshResult(DiscoveryRefreshStatus.FAILURE)
        }
        val success = fetched as DiscoveryFetchResult.Success
        persist(type, success.items, System.currentTimeMillis())
        logDiscoveryRefresh(type, "success matches=${success.items.size}")
        return DiscoveryRefreshResult(
            status = DiscoveryRefreshStatus.SUCCESS,
            items = success.items,
            candidateCount = success.candidateCount
        )
    }

    private suspend fun discover(type: MediaType, localMedia: List<MediaSource>): DiscoveryFetchResult {
        val candidates = localMedia.filter { it.type == type && it.id > 0 && !it.title.isNullOrBlank() }
        if (candidates.isEmpty()) {
            logDiscovery(type, 0, emptyList())
            return DiscoveryFetchResult.Failure
        }

        val matched: List<HomeDiscoveryItem>
        val candidateCount: Int
        try {
            when (type) {
                MediaType.MOVIE -> {
                    val results = (1..2).flatMap { api?.getTrendingMovies(it)?.results ?: throw IOException("TMDB API unavailable") }.take(30)
                    candidateCount = results.size
                    matched = results.mapNotNull { result ->
                        val local = findLocalMatch(
                            localizedTitle = result.title,
                            originalTitle = result.originalTitle,
                            tmdbYear = TmdbTitleMatcher.extractYear(result.releaseDate),
                            localMedia = candidates
                        ) ?: return@mapNotNull null
                        HomeDiscoveryItem(
                            media = local,
                            tmdbId = result.id,
                            title = result.title ?: result.originalTitle ?: local.title.orEmpty(),
                            posterUrl = imageUrl(result.posterPath, "w500"),
                            backdropUrl = imageUrl(result.backdropPath, "w1280"),
                            overview = result.overview,
                            year = TmdbTitleMatcher.extractYear(result.releaseDate),
                            rating = result.voteAverage
                        )
                    }.distinctBy { it.media.id }.take(10)
                }
                MediaType.SERIES -> {
                    val results = (1..2).flatMap { api?.getTrendingTv(it)?.results ?: throw IOException("TMDB API unavailable") }.take(30)
                    candidateCount = results.size
                    matched = results.mapNotNull { result ->
                        val local = findLocalMatch(
                            localizedTitle = result.name,
                            originalTitle = result.originalName,
                            tmdbYear = TmdbTitleMatcher.extractYear(result.firstAirDate),
                            localMedia = candidates
                        ) ?: return@mapNotNull null
                        HomeDiscoveryItem(
                            media = local,
                            tmdbId = result.id,
                            title = result.name ?: result.originalName ?: local.title.orEmpty(),
                            posterUrl = imageUrl(result.posterPath, "w500"),
                            backdropUrl = imageUrl(result.backdropPath, "w1280"),
                            overview = result.overview,
                            year = TmdbTitleMatcher.extractYear(result.firstAirDate),
                            rating = result.voteAverage
                        )
                    }.distinctBy { it.media.id }.take(10)
                }
                MediaType.LIVE -> return DiscoveryFetchResult.Failure
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logDiscoveryRefresh(type, "failure=${e.javaClass.simpleName}")
            return DiscoveryFetchResult.Failure
        }

        logDiscovery(type, candidateCount, matched)
        return DiscoveryFetchResult.Success(candidateCount, matched)
    }

    private fun reconnect(
        bucket: PersistentDiscoveryBucket?,
        localMedia: List<MediaSource>,
        type: MediaType
    ): List<HomeDiscoveryItem> {
        if (bucket == null) return emptyList()
        val localById = localMedia.asSequence()
            .filter { it.type == type }
            .associateBy { it.id }
        return bucket.items.orEmpty().mapNotNull { cached ->
            val local = localById[cached.localMediaId] ?: return@mapNotNull null
            HomeDiscoveryItem(
                media = local,
                tmdbId = cached.tmdbId,
                title = cached.title.orEmpty().ifBlank { local.title.orEmpty() },
                posterUrl = cached.posterUrl,
                backdropUrl = cached.backdropUrl,
                overview = cached.overview,
                year = cached.year,
                rating = cached.rating
            )
        }
    }

    private fun readPersistentFile(): PersistentDiscoveryFile? {
        val file = discoveryCacheFile ?: return null
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<PersistentDiscoveryFile>() {}.type
            file.reader().use { gson.fromJson<PersistentDiscoveryFile>(it, type) }
        } catch (e: Exception) {
            logPersistent("cache_read_failure=${e.javaClass.simpleName}")
            null
        }
    }

    private fun persist(type: MediaType, items: List<HomeDiscoveryItem>, cachedAt: Long) {
        val file = discoveryCacheFile ?: return
        synchronized(discoveryCacheLock) {
            val existing = readPersistentFile() ?: PersistentDiscoveryFile()
            val bucket = PersistentDiscoveryBucket(
                cachedAt = cachedAt,
                items = items.map {
                    PersistentDiscoveryItem(
                        tmdbId = it.tmdbId,
                        localMediaId = it.media.id,
                        title = it.title,
                        posterUrl = it.posterUrl,
                        backdropUrl = it.backdropUrl,
                        overview = it.overview,
                        year = it.year,
                        rating = it.rating
                    )
                }
            )
            val replacement = if (type == MediaType.MOVIE) {
                existing.copy(movies = bucket)
            } else {
                existing.copy(series = bucket)
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            try {
                temp.writer().use { it.write(gson.toJson(replacement)) }
                if (!temp.renameTo(file)) {
                    throw IOException("Could not atomically replace discovery cache")
                }
            } catch (e: Exception) {
                temp.delete()
                logPersistent("cache_write_failure=${e.javaClass.simpleName}")
            }
        }
    }

    private fun logPersistent(event: String) {
        if (BuildConfig.DEBUG) Log.d("TmdbDiscoveryCache", event)
    }

    private fun logPersistentBucket(label: String, bucket: PersistentDiscoveryBucket?, valid: List<HomeDiscoveryItem>) {
        if (!BuildConfig.DEBUG) return
        if (bucket == null) {
            Log.d("TmdbDiscoveryCache", "$label: cache_miss")
            return
        }
        val age = bucket.cachedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
        Log.d("TmdbDiscoveryCache", "$label: hit ageMs=${age ?: "unknown"} " +
            "fresh=${isDiscoveryFresh(bucket.cachedAt)} cached=${bucket.items.size} validLocal=${valid.size}")
    }

    private fun logDiscoveryRefresh(type: MediaType, event: String) {
        if (BuildConfig.DEBUG) Log.d("TmdbDiscoveryCache", "${type.name}: background_refresh_$event")
    }

    private fun logDiscovery(type: MediaType, candidateCount: Int, matches: List<HomeDiscoveryItem>) {
        if (!BuildConfig.DEBUG) return
        val label = if (type == MediaType.MOVIE) "Movies" else "Series"
        Log.d(
            "TmdbDiscovery2B",
            "TMDB $label: candidates=$candidateCount localMatches=${matches.size} " +
                "titles=${matches.joinToString { it.title }}"
        )
    }

    private fun findLocalMatch(
        localizedTitle: String?,
        originalTitle: String?,
        tmdbYear: Int?,
        localMedia: List<MediaSource>
    ): MediaSource? {
        val normalizedTitles = sequenceOf(localizedTitle, originalTitle)
            .filterNotNull()
            .map(TmdbTitleMatcher::normalizeTitle)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedTitles.isEmpty()) return null

        val exact = localMedia.filter { media ->
            TmdbTitleMatcher.normalizeTitle(media.title.orEmpty()) in normalizedTitles
        }
        if (exact.isEmpty()) return null
        val yearMatched = exact.filter { media ->
            val localYear = TmdbTitleMatcher.extractYear(media.title)
            tmdbYear != null && localYear != null && tmdbYear == localYear
        }
        val candidates = when {
            yearMatched.isNotEmpty() -> yearMatched
            exact.any { TmdbTitleMatcher.extractYear(it.title) != null && tmdbYear != null } -> return null
            else -> exact
        }
        return candidates.singleOrNull()
    }

    private fun imageUrl(path: String?, size: String): String? =
        path?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/$size/${it.trimStart('/')}" }

    suspend fun getMetadata(request: TmdbLookupRequest): DetailMetadata? {
        if (api == null) return null
        val searchTitle = TmdbTitleMatcher.cleanSearchTitle(request.catalogTitle)
        val normalizedTitle = TmdbTitleMatcher.normalizeTitle(searchTitle)
        if (normalizedTitle.isBlank()) return null
        val cacheKey = CacheKey(request.key.mediaType, normalizedTitle, request.year)
        memoryCache[cacheKey]?.let {
            log(request, normalizedTitle, "cache_hit")
            return it
        }

        log(request, normalizedTitle, "requested")
        val metadata = try {
            when (request.key.mediaType) {
                MediaType.MOVIE -> loadMovie(request, searchTitle, normalizedTitle)
                MediaType.SERIES -> loadSeries(request, searchTitle, normalizedTitle)
                MediaType.LIVE -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            log(request, normalizedTitle, "http_error status=${e.code()}")
            null
        } catch (e: IOException) {
            log(request, normalizedTitle, "network_error category=${e.javaClass.simpleName}")
            null
        } catch (e: Exception) {
            log(request, normalizedTitle, "parse_or_unexpected_error category=${e.javaClass.simpleName}")
            null
        }
        if (metadata != null) memoryCache[cacheKey] = metadata
        return metadata
    }

    private suspend fun loadMovie(
        request: TmdbLookupRequest,
        title: String,
        normalizedTitle: String
    ): DetailMetadata? {
        val client = api ?: return null
        val candidates = client.searchMovies(title, request.year).results.map {
            TmdbMatchCandidate(it.id, it.title, it.originalTitle, it.releaseDate)
        }
        val evaluation = TmdbTitleMatcher.evaluateMatch(title, request.year, candidates)
        logEvaluation(request, normalizedTitle, evaluation)
        val match = evaluation.candidate ?: return null
        val details = client.getMovieDetails(match.id)
        return DetailMetadata(
            plot = details.overview.nonBlank(),
            genre = details.genres.mapNotNull { it.name.nonBlank() }.joinToString(", ").nonBlank(),
            releaseDate = details.releaseDate.nonBlank(),
            rating = formatRating(details.voteAverage, details.voteCount),
            backdropPath = details.backdropPath.nonBlank(),
            director = movieDirectors(details.credits),
            cast = topCast(details.credits)
        ).also { log(request, normalizedTitle, "details_success") }
    }

    private suspend fun loadSeries(
        request: TmdbLookupRequest,
        title: String,
        normalizedTitle: String
    ): DetailMetadata? {
        val client = api ?: return null
        val candidates = client.searchTv(title, request.year).results.map {
            TmdbMatchCandidate(it.id, it.name, it.originalName, it.firstAirDate)
        }
        val evaluation = TmdbTitleMatcher.evaluateMatch(title, request.year, candidates)
        logEvaluation(request, normalizedTitle, evaluation)
        val match = evaluation.candidate ?: return null
        val details = client.getTvDetails(match.id)
        return DetailMetadata(
            plot = details.overview.nonBlank(),
            genre = details.genres.mapNotNull { it.name.nonBlank() }.joinToString(", ").nonBlank(),
            releaseDate = details.firstAirDate.nonBlank(),
            rating = formatRating(details.voteAverage, details.voteCount),
            backdropPath = details.backdropPath.nonBlank(),
            cast = topCast(details.aggregateCredits)
        ).also { log(request, normalizedTitle, "details_success") }
    }

    private fun logEvaluation(
        request: TmdbLookupRequest,
        normalizedTitle: String,
        evaluation: TmdbMatchEvaluation
    ) {
        log(
            request,
            normalizedTitle,
            "match=${evaluation.outcome.name.lowercase(Locale.ROOT)} " +
                "candidates=${evaluation.candidateCount} exact=${evaluation.exactMatchCount} " +
                "yearRejected=${evaluation.yearRejectedCount}"
        )
    }

    private fun log(request: TmdbLookupRequest, normalizedTitle: String, outcome: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            "TmdbMetadata",
            "lookup=${request.kind.name.lowercase(Locale.ROOT)} type=${request.key.mediaType} " +
                "id=${request.key.serverMediaId} title=${normalizedTitle.take(80)} " +
                "year=${request.year != null} outcome=$outcome"
        )
    }

    private fun formatRating(average: Double?, count: Int?): String? = average
        ?.takeIf { it > 0.0 && (count ?: 0) > 0 }
        ?.let { String.format(Locale.US, "%.1f", it) }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun movieDirectors(credits: TmdbCredits?): String? = credits
    ?.crew
    .orEmpty()
    .asSequence()
    .filter { it.job.equals("Director", ignoreCase = true) }
    .mapNotNull { credit -> credit.name?.trim()?.takeIf(String::isNotEmpty)?.let { credit.id to it } }
    .distinctBy { (id, name) -> id?.let { "id:$it" } ?: "name:${name.lowercase(Locale.ROOT)}" }
    .map { it.second }
    .toList()
    .joinToString(" • ")
    .takeIf(String::isNotEmpty)

internal fun topCast(credits: TmdbCredits?, limit: Int = 5): String? = credits
    ?.cast
    .orEmpty()
    .asSequence()
    .sortedBy { it.order ?: Int.MAX_VALUE }
    .mapNotNull { credit -> credit.name?.trim()?.takeIf(String::isNotEmpty)?.let { credit.id to it } }
    .distinctBy { (id, name) -> id?.let { "id:$it" } ?: "name:${name.lowercase(Locale.ROOT)}" }
    .take(limit)
    .map { it.second }
    .toList()
    .joinToString(" • ")
    .takeIf(String::isNotEmpty)
