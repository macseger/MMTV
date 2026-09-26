package com.example.mmtv.repository

import com.example.mmtv.model.DetailMetadata
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
}
