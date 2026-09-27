package com.example.mmtv.repository

import com.example.mmtv.model.DetailMetadata
import com.example.mmtv.model.TmdbCastCredit
import com.example.mmtv.model.TmdbCredits
import com.example.mmtv.model.TmdbMovieDetails
import com.example.mmtv.model.TmdbTvDetails
import com.example.mmtv.model.mergeDetailMetadata
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbMetadataRepositoryTest {

    @Test
    fun normalizeTitle_removesOnlyCommonProviderNoise() {
        assertEquals(
            "the matrix",
            TmdbTitleMatcher.normalizeTitle("VOD | The Matrix (1999) [1080p] SWE")
        )
    }

    @Test
    fun cleanSearchTitle_removesNcPrefixFromCollision() {
        assertEquals("Collision", TmdbTitleMatcher.cleanSearchTitle("NC Collision"))
        assertEquals("collision", TmdbTitleMatcher.normalizeTitle("NC Collision"))
    }

    @Test
    fun cleanSearchTitle_removesNcPrefixFromYouth() {
        assertEquals("Youth", TmdbTitleMatcher.cleanSearchTitle("NC Youth"))
        assertEquals("youth", TmdbTitleMatcher.normalizeTitle("NC Youth"))
    }

    @Test
    fun cleanSearchTitle_removesNcPrefixFromLioness() {
        assertEquals("Lioness", TmdbTitleMatcher.cleanSearchTitle("NC Lioness"))
        assertEquals("lioness", TmdbTitleMatcher.normalizeTitle("NC Lioness"))
    }

    @Test
    fun cleanSearchTitle_preservesTitlesWithoutExplicitNcPrefix() {
        assertEquals("NCIS", TmdbTitleMatcher.cleanSearchTitle("NCIS"))
        assertEquals("No Country for Old Men", TmdbTitleMatcher.cleanSearchTitle("No Country for Old Men"))
    }

    @Test
    fun cleanSearchTitle_removesChainedKnownLeadingDecorations() {
        assertEquals("Lucky", TmdbTitleMatcher.cleanSearchTitle("4K NC Lucky"))
        assertEquals("lucky", TmdbTitleMatcher.normalizeTitle("4K NC Lucky"))
        assertEquals("Lucky", TmdbTitleMatcher.cleanSearchTitle("NC Lucky"))
    }

    @Test
    fun cleanSearchTitle_preservesFourKLikeWordsAndNcis() {
        assertEquals("4Kings", TmdbTitleMatcher.cleanSearchTitle("4Kings"))
        assertEquals("NCIS", TmdbTitleMatcher.cleanSearchTitle("NCIS"))
    }

    @Test
    fun selectConfidentMatch_prefersExactTitleWithMatchingYear() {
        val match = TmdbTitleMatcher.findConfidentMatch(
            sourceTitle = "Dune",
            sourceYear = 2021,
            candidates = listOf(
                TmdbMatchCandidate(1, "Dune", "Dune", "1984-12-14"),
                TmdbMatchCandidate(2, "Dune", "Dune", "2021-10-22")
            )
        )

        assertEquals(2, match?.id)
    }

    @Test
    fun selectConfidentMatch_rejectsAmbiguousExactTitlesWithoutYear() {
        val match = TmdbTitleMatcher.findConfidentMatch(
            sourceTitle = "Dune",
            sourceYear = null,
            candidates = listOf(
                TmdbMatchCandidate(1, "Dune", "Dune", "1984-12-14"),
                TmdbMatchCandidate(2, "Dune", "Dune", "2021-10-22")
            )
        )

        assertNull(match)
    }

    @Test
    fun selectConfidentMatch_rejectsWeakPartialTitle() {
        val match = TmdbTitleMatcher.findConfidentMatch(
            sourceTitle = "Office Space",
            sourceYear = 1999,
            candidates = listOf(
                TmdbMatchCandidate(1, "The Office", "The Office", "2005-03-24")
            )
        )

        assertNull(match)
    }

    @Test
    fun mergeDetailMetadata_fallsBackPerFieldWhenTmdbValueIsBlank() {
        val merged = mergeDetailMetadata(
            tmdb = DetailMetadata(
                plot = " ",
                genre = "Drama",
                releaseDate = "",
                rating = "8.2"
            ),
            xtream = DetailMetadata(
                plot = "Xtream plot",
                genre = "Action",
                releaseDate = "2020-05-06",
                rating = "7.0"
            ),
            mediaSource = DetailMetadata(
                plot = "Catalog plot",
                genre = "Catalog genre",
                releaseDate = "2019",
                rating = "6.0"
            )
        )

        assertEquals("Xtream plot", merged.plot)
        assertEquals("Drama", merged.genre)
        assertEquals("2020-05-06", merged.releaseDate)
        assertEquals("8.2", merged.rating)
    }

    @Test
    fun movieDetails_parsesBackdropPath() {
        val details = Gson().fromJson(
            """{"backdrop_path":"/cinematic.jpg","genres":[]}""",
            TmdbMovieDetails::class.java
        )

        assertEquals("/cinematic.jpg", details.backdropPath)
    }

    @Test
    fun tvDetails_parsesBackdropPath() {
        val details = Gson().fromJson(
            """{"backdrop_path":"/series-cinematic.jpg","genres":[]}""",
            TmdbTvDetails::class.java
        )

        assertEquals("/series-cinematic.jpg", details.backdropPath)
    }

    @Test
    fun mergeDetailMetadata_exposesTmdbBackdropPath() {
        val merged = mergeDetailMetadata(
            tmdb = DetailMetadata(backdropPath = "/cinematic.jpg"),
            xtream = DetailMetadata(),
            mediaSource = DetailMetadata()
        )

        assertEquals("/cinematic.jpg", merged.backdropPath)
    }

    @Test
    fun movieCredits_extractsDirectorAndMultipleDirectors() {
        val details = Gson().fromJson(
            """{"credits":{"cast":[],"crew":[{"id":1,"name":"Director One","job":"Director","department":"Directing"},{"id":2,"name":"Editor","job":"Editor","department":"Editing"},{"id":3,"name":"Director Two","job":"Director","department":"Directing"},{"id":1,"name":"Director One","job":"Director","department":"Directing"}]}}""",
            TmdbMovieDetails::class.java
        )

        assertEquals("Director One • Director Two", movieDirectors(details.credits))
    }

    @Test
    fun movieCredits_sortsDeduplicatesAndLimitsCastToFive() {
        val credits = TmdbCredits(
            cast = listOf(
                TmdbCastCredit(6, "Sixth", order = 6),
                TmdbCastCredit(2, "Second", order = 2),
                TmdbCastCredit(1, "First", order = 1),
                TmdbCastCredit(3, "Third", order = 3),
                TmdbCastCredit(4, "Fourth", order = 4),
                TmdbCastCredit(5, "Fifth", order = 5),
                TmdbCastCredit(1, "First duplicate", order = 7)
            )
        )

        assertEquals("First • Second • Third • Fourth • Fifth", topCast(credits))
    }

    @Test
    fun tvAggregateCredits_parsesAndSelectsTopCast() {
        val details = Gson().fromJson(
            """{"aggregate_credits":{"cast":[{"id":2,"name":"Second","order":2},{"id":1,"name":"First","order":1}],"crew":[]}}""",
            TmdbTvDetails::class.java
        )

        assertEquals("First • Second", topCast(details.aggregateCredits))
    }

    @Test
    fun detailsWithoutCredits_remainValid() {
        val movie = Gson().fromJson("""{"genres":[]}""", TmdbMovieDetails::class.java)
        val series = Gson().fromJson("""{"genres":[]}""", TmdbTvDetails::class.java)

        assertNull(movie.credits)
        assertNull(series.aggregateCredits)
        assertNull(movieDirectors(movie.credits))
        assertNull(topCast(series.aggregateCredits))
    }

    @Test
    fun mergeDetailMetadata_fallsBackToXtreamCreditsPerField() {
        val merged = mergeDetailMetadata(
            tmdb = DetailMetadata(director = " ", cast = null),
            xtream = DetailMetadata(director = "Xtream Director", cast = "Xtream Cast"),
            mediaSource = DetailMetadata(director = "Catalog Director", cast = "Catalog Cast")
        )

        assertEquals("Xtream Director", merged.director)
        assertEquals("Xtream Cast", merged.cast)
    }

    @Test
    fun mergeDetailMetadata_prefersTmdbCreditsWhenPresent() {
        val merged = mergeDetailMetadata(
            tmdb = DetailMetadata(director = "TMDB Director", cast = "TMDB Cast"),
            xtream = DetailMetadata(director = "Xtream Director", cast = "Xtream Cast"),
            mediaSource = DetailMetadata(director = "Catalog Director", cast = "Catalog Cast")
        )

        assertEquals("TMDB Director", merged.director)
        assertEquals("TMDB Cast", merged.cast)
    }
}
