package com.example.mmtv.ui

import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.MediaType
import java.time.LocalDate
import java.text.Normalizer
import java.util.Locale

sealed interface VodCategoryKey {
    val stableValue: String

    data object All : VodCategoryKey { override val stableValue = "ALL" }
    data object New : VodCategoryKey { override val stableValue = "NEW" }
    data class Genre(val genre: VodGenre) : VodCategoryKey {
        override val stableValue = "GENRE:${genre.name}"
    }
    data object History : VodCategoryKey { override val stableValue = "HISTORY" }
    data object Favorites : VodCategoryKey { override val stableValue = "FAVORITES" }
    data class Provider(val categoryId: String) : VodCategoryKey {
        init { require(categoryId.isNotBlank()) }
        override val stableValue = "PROVIDER:$categoryId"
    }

    companion object {
        fun fromStableValue(value: String): VodCategoryKey? = when {
            value == All.stableValue -> All
            value == New.stableValue -> New
            value == History.stableValue -> History
            value == Favorites.stableValue -> Favorites
            value.startsWith("GENRE:") -> value.substringAfter("GENRE:")
                .let { runCatching { VodGenre.valueOf(it) }.getOrNull() }
                ?.let(::Genre)
            value.startsWith("PROVIDER:") -> value.substringAfter("PROVIDER:")
                .takeIf { it.isNotBlank() }
                ?.let(::Provider)
            else -> null
        }
    }
}

enum class VodGenre(val title: String, val aliases: Set<String>) {
    ACTION("ACTION", setOf("action")),
    THRILLER("THRILLER", setOf("thriller")),
    DRAMA("DRAMA", setOf("drama")),
    COMEDY("KOMEDI", setOf("comedy", "komedi")),
    SCIENCE_FICTION(
        "SCIENCE FICTION",
        setOf("science fiction", "sci fi", "scifi", "sci-fi")
    );

    companion object {
        fun fromMetadata(value: String?): Set<VodGenre> {
            if (value.isNullOrBlank()) return emptySet()
            val normalizedParts = value
                .split(Regex("[,;/|&]+"))
                .map(::normalizeGenreToken)
                .filter { it.isNotBlank() }
                .toSet()
            return entries.filterTo(linkedSetOf()) { genre ->
                genre.aliases.any { normalizeGenreToken(it) in normalizedParts }
            }
        }
    }
}

private fun normalizeGenreToken(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace('-', ' ')
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

data class VodMediaKey(val type: MediaType, val mediaId: Int)

data class VodMetadataRecord(
    val mediaKey: VodMediaKey,
    val genres: Set<VodGenre> = emptySet(),
    val releaseDate: LocalDate? = null
)

data class VodCategoryDefinition(
    val key: VodCategoryKey,
    val title: String,
    val mediaIds: List<Int>
)

class VodMetadataIndex private constructor(
    private val allIds: Map<MediaType, List<Int>>,
    private val dynamicIds: Map<Pair<MediaType, VodCategoryKey>, List<Int>>
) {
    fun allMediaIds(type: MediaType): List<Int> = allIds[type].orEmpty()

    fun mediaIds(type: MediaType, key: VodCategoryKey): List<Int> = when (key) {
        VodCategoryKey.All -> allMediaIds(type)
        else -> dynamicIds[type to key].orEmpty()
    }

    fun hasMedia(type: MediaType, key: VodCategoryKey): Boolean = mediaIds(type, key).isNotEmpty()

    companion object {
        val Empty = VodMetadataIndex(emptyMap(), emptyMap())

        fun build(
            library: List<MediaSource>,
            metadata: Collection<VodMetadataRecord> = library.map { media ->
                VodMetadataRecord(
                    mediaKey = VodMediaKey(media.type, media.id),
                    genres = VodGenre.fromMetadata(media.genre)
                )
            },
            today: LocalDate = LocalDate.now()
        ): VodMetadataIndex {
            val distinctLibrary = library.distinctBy { VodMediaKey(it.type, it.id) }
            val orderedKeys = distinctLibrary.map { VodMediaKey(it.type, it.id) }
            val metadataByKey = metadata.associateBy { it.mediaKey }
            val all = distinctLibrary.groupBy { it.type }
                .mapValues { (_, items) -> items.map { it.id } }
            val dynamic = mutableMapOf<Pair<MediaType, VodCategoryKey>, MutableList<Int>>()
            val newThreshold = today.minusMonths(12)

            orderedKeys.forEach { mediaKey ->
                val record = metadataByKey[mediaKey] ?: return@forEach
                record.genres.forEach { genre ->
                    dynamic.getOrPut(mediaKey.type to VodCategoryKey.Genre(genre)) { mutableListOf() }
                        .add(mediaKey.mediaId)
                }
                record.releaseDate?.let { releaseDate ->
                    if (!releaseDate.isBefore(newThreshold) && !releaseDate.isAfter(today)) {
                        dynamic.getOrPut(mediaKey.type to VodCategoryKey.New) { mutableListOf() }
                            .add(mediaKey.mediaId)
                    }
                }
            }
            return VodMetadataIndex(all, dynamic.mapValues { it.value.toList() })
        }
    }
}

internal fun buildVodCategoryDefinitions(
    type: MediaType,
    groupedList: List<GroupedMedia>,
    allMediaIds: List<Int>,
    metadataIndex: VodMetadataIndex
): List<VodCategoryDefinition> {
    require(type != MediaType.LIVE)
    val result = mutableListOf(
        VodCategoryDefinition(
            key = VodCategoryKey.All,
            title = if (type == MediaType.MOVIE) "ALLA FILMER" else "ALLA SERIER",
            mediaIds = allMediaIds
        )
    )
    val dynamicKeys = listOf(
        VodCategoryKey.New,
        VodCategoryKey.Genre(VodGenre.ACTION),
        VodCategoryKey.Genre(VodGenre.THRILLER),
        VodCategoryKey.Genre(VodGenre.DRAMA),
        VodCategoryKey.Genre(VodGenre.COMEDY),
        VodCategoryKey.Genre(VodGenre.SCIENCE_FICTION)
    )
    dynamicKeys.forEach { key ->
        val ids = metadataIndex.mediaIds(type, key)
        if (ids.isNotEmpty()) {
            val title = when (key) {
                VodCategoryKey.New -> "NYTT"
                is VodCategoryKey.Genre -> key.genre.title
                else -> error("Unexpected dynamic VOD category")
            }
            result += VodCategoryDefinition(key, title, ids)
        }
    }
    groupedList.forEach { group ->
        val categoryId = group.categoryId ?: return@forEach
        val key = when (categoryId) {
            "HISTORY" -> VodCategoryKey.History
            "FAVORITES" -> VodCategoryKey.Favorites
            else -> VodCategoryKey.Provider(categoryId)
        }
        result += VodCategoryDefinition(key, group.title.orEmpty(), group.items.map { it.id })
    }
    return result.distinctBy { it.key }
}

internal fun restoreVodCategoryKey(
    requested: VodCategoryKey,
    categories: List<VodCategoryDefinition>
): VodCategoryKey = requested.takeIf { key -> categories.any { it.key == key } } ?: VodCategoryKey.All
