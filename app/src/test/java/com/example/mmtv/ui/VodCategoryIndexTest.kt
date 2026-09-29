package com.example.mmtv.ui

import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCategoryIndexTest {
    @Test fun stableKeysRoundTripIncludingProviderCategory() {
        val keys = listOf(
            VodCategoryKey.All,
            VodCategoryKey.New,
            VodCategoryKey.Genre(VodGenre.SCIENCE_FICTION),
            VodCategoryKey.History,
            VodCategoryKey.Favorites,
            VodCategoryKey.Provider("42")
        )
        keys.forEach { assertEquals(it, VodCategoryKey.fromStableValue(it.stableValue)) }
        assertEquals("PROVIDER:42", VodCategoryKey.Provider("42").stableValue)
    }

    @Test fun genreAliasesAreTokenizedWithoutFuzzyMatching() {
        assertEquals(
            setOf(VodGenre.ACTION, VodGenre.SCIENCE_FICTION),
            VodGenre.fromMetadata("Action, Sci-Fi & Fantasy")
        )
        assertEquals(setOf(VodGenre.COMEDY), VodGenre.fromMetadata("Komedi"))
        assertTrue(VodGenre.fromMetadata("Actionfilm").isEmpty())
    }

    @Test fun mediaCanBelongToSeveralDynamicCategories() {
        val media = movie(7, genre = "Action, Thriller")
        val index = VodMetadataIndex.build(listOf(media))
        assertEquals(listOf(7), index.mediaIds(MediaType.MOVIE, VodCategoryKey.Genre(VodGenre.ACTION)))
        assertEquals(listOf(7), index.mediaIds(MediaType.MOVIE, VodCategoryKey.Genre(VodGenre.THRILLER)))
    }

    @Test fun mediaWithoutMetadataAlwaysRemainsInAll() {
        val media = movie(9)
        val index = VodMetadataIndex.build(listOf(media))
        assertEquals(listOf(9), index.mediaIds(MediaType.MOVIE, VodCategoryKey.All))
        assertFalse(index.hasMedia(MediaType.MOVIE, VodCategoryKey.New))
        assertFalse(index.hasMedia(MediaType.MOVIE, VodCategoryKey.Genre(VodGenre.DRAMA)))
    }

    @Test fun newUsesReleaseDateAndNeverAddedDate() {
        val media = movie(11).copy(addedDate = Long.MAX_VALUE)
        val withoutReleaseDate = VodMetadataIndex.build(listOf(media), today = LocalDate.of(2026, 9, 29))
        assertFalse(withoutReleaseDate.hasMedia(MediaType.MOVIE, VodCategoryKey.New))

        val withReleaseDate = VodMetadataIndex.build(
            library = listOf(media),
            metadata = listOf(
                VodMetadataRecord(VodMediaKey(MediaType.MOVIE, 11), releaseDate = LocalDate.of(2026, 1, 1))
            ),
            today = LocalDate.of(2026, 9, 29)
        )
        assertEquals(listOf(11), withReleaseDate.mediaIds(MediaType.MOVIE, VodCategoryKey.New))
    }

    @Test fun restorationUsesStableKeyNotDynamicUiIndex() {
        val provider = GroupedMedia("Nordic", listOf(movie(1)), "provider-1")
        val emptyIndex = VodMetadataIndex.build(listOf(movie(1)))
        val providerKey = VodCategoryKey.Provider("provider-1")
        val before = buildVodCategoryDefinitions(MediaType.MOVIE, listOf(provider), listOf(1), emptyIndex)

        val enrichedIndex = VodMetadataIndex.build(listOf(movie(1, "Action")))
        val after = buildVodCategoryDefinitions(MediaType.MOVIE, listOf(provider), listOf(1), enrichedIndex)

        assertTrue(before.indexOfFirst { it.key == providerKey } != after.indexOfFirst { it.key == providerKey })
        assertEquals(providerKey, restoreVodCategoryKey(providerKey, after))
    }

    private fun movie(id: Int, genre: String? = null) = MediaSource(
        id = id,
        title = "Movie $id",
        icon = null,
        type = MediaType.MOVIE,
        categoryId = "provider-1",
        genre = genre
    )
}
