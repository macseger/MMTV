package com.example.mmtv.database

import androidx.room.*

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items WHERE LOWER(title) LIKE LOWER(:query)")
    suspend fun searchMedia(query: String): List<MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media_items")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM media_items WHERE type = :type")
    suspend fun getCountByType(type: com.example.mmtv.model.MediaType): Int

    @Query("SELECT * FROM media_items WHERE type = :type ORDER BY categoryOrder ASC, itemOrder ASC")
    suspend fun getMediaByType(type: com.example.mmtv.model.MediaType): List<MediaEntity>

    @Query("SELECT DISTINCT categoryId, categoryName FROM media_items WHERE type = :type ORDER BY categoryOrder ASC")
    suspend fun getCategoriesByType(type: com.example.mmtv.model.MediaType): List<CategorySimple>

    @Query("SELECT * FROM media_items WHERE type = :type AND categoryId = :catId ORDER BY itemOrder ASC")
    suspend fun getMediaByCategoryId(type: com.example.mmtv.model.MediaType, catId: String): List<MediaEntity>

    @Query("SELECT * FROM media_items WHERE isFavorite = 1 ORDER BY favoriteDate DESC")
    suspend fun getFavorites(): List<MediaEntity>

    @Query("UPDATE media_items SET isFavorite = :isFav, favoriteDate = :favDate WHERE id = :id AND type = :type")
    suspend fun updateFavoriteWithDate(id: Int, type: com.example.mmtv.model.MediaType, isFav: Boolean, favDate: Long)

    @Query("SELECT * FROM media_items WHERE id = :id AND type = :type LIMIT 1")
    suspend fun getMediaById(id: Int, type: com.example.mmtv.model.MediaType): MediaEntity?

    @Query("UPDATE media_items SET isFavorite = 0, favoriteDate = 0")
    suspend fun clearAllFavorites()

    @Query("SELECT * FROM media_items WHERE type != 'LIVE' ORDER BY addedDate DESC LIMIT 10")
    suspend fun getRecentlyAdded(): List<MediaEntity>

    @Query("DELETE FROM media_items WHERE type = :type")
    suspend fun deleteByType(type: com.example.mmtv.model.MediaType)

    // EPG Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpg(listings: List<EpgEntity>)

    @Query("DELETE FROM epg_listings WHERE stopTimestamp < :currentTime")
    suspend fun deleteOldEpg(currentTime: Long)

    @Query("SELECT * FROM epg_listings WHERE epgId = :epgId AND stopTimestamp > :currentTime AND startTimestamp < :endLimit ORDER BY startTimestamp ASC")
    suspend fun getEpgForChannelWithLimit(epgId: String, currentTime: Long, endLimit: Long): List<EpgEntity>

    @Query("SELECT * FROM epg_listings WHERE (epgId = :epgId OR epgId LIKE '%' || :nameNoSpaces || '%' OR :nameNoSpaces LIKE '%' || epgId || '%' OR channelName LIKE '%' || :name || '%' OR :name LIKE '%' || channelName || '%') AND stopTimestamp > :currentTime AND startTimestamp < :endLimit GROUP BY startTimestamp ORDER BY startTimestamp ASC LIMIT 50")
    suspend fun findEpgByFuzzyName(name: String, nameNoSpaces: String, epgId: String?, currentTime: Long, endLimit: Long): List<EpgEntity>

    @Query("SELECT icon FROM channel_metadata WHERE epgId = :epgId LIMIT 1")
    suspend fun getIconByEpgId(epgId: String): String?

    @Query("SELECT icon FROM channel_metadata WHERE (displayName LIKE '%' || :name || '%' OR displayName LIKE '%' || :nameNoSpaces || '%' OR epgId LIKE '%' || :name || '%' OR epgId LIKE '%' || :nameNoSpaces || '%') LIMIT 1")
    suspend fun findIconByFuzzyName(name: String, nameNoSpaces: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Query("SELECT COUNT(*) FROM epg_listings")
    suspend fun getEpgCount(): Int

    @Query("DELETE FROM epg_listings")
    suspend fun clearEpg()

    // Picon Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPicons(picons: List<PiconEntity>)

    @Query("SELECT * FROM picons WHERE name = :name LIMIT 1")
    suspend fun getPiconByName(name: String): PiconEntity?

    @Query("SELECT * FROM picons")
    suspend fun getAllPicons(): List<PiconEntity>

    @Query("DELETE FROM picons")
    suspend fun clearPicons()
}

data class CategorySimple(
    val categoryId: String?,
    val categoryName: String?
)
