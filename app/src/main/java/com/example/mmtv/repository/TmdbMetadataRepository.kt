package com.example.mmtv.repository

import android.util.Log
import com.example.mmtv.BuildConfig
import com.example.mmtv.api.TmdbApi
import com.example.mmtv.model.DetailMetadata
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.TmdbCredits
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
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

class TmdbMetadataRepository(private val api: TmdbApi) {
    private data class CacheKey(val type: MediaType, val normalizedTitle: String, val year: Int?)

    private val memoryCache = ConcurrentHashMap<CacheKey, DetailMetadata>()

    suspend fun getMetadata(request: TmdbLookupRequest): DetailMetadata? {
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
        val candidates = api.searchMovies(title, request.year).results.map {
            TmdbMatchCandidate(it.id, it.title, it.originalTitle, it.releaseDate)
        }
        val evaluation = TmdbTitleMatcher.evaluateMatch(title, request.year, candidates)
        logEvaluation(request, normalizedTitle, evaluation)
        val match = evaluation.candidate ?: return null
        val details = api.getMovieDetails(match.id)
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
        val candidates = api.searchTv(title, request.year).results.map {
            TmdbMatchCandidate(it.id, it.name, it.originalName, it.firstAirDate)
        }
        val evaluation = TmdbTitleMatcher.evaluateMatch(title, request.year, candidates)
        logEvaluation(request, normalizedTitle, evaluation)
        val match = evaluation.candidate ?: return null
        val details = api.getTvDetails(match.id)
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
