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

    @Query("DELETE FROM media_items WHERE type = :type")
    suspend fun deleteByType(type: com.example.mmtv.model.MediaType)

    // EPG Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpg(listings: List<EpgEntity>)

    @Query("DELETE FROM epg_listings WHERE stopTimestamp < :currentTime")
    suspend fun deleteOldEpg(currentTime: Long)

    @Query("SELECT * FROM epg_listings WHERE epgId = :epgId AND stopTimestamp > :currentTime ORDER BY startTimestamp ASC LIMIT 5")
    suspend fun getEpgForChannel(epgId: String, currentTime: Long): List<EpgEntity>

    @Query("SELECT COUNT(*) FROM epg_listings")
    suspend fun getEpgCount(): Int

    @Query("DELETE FROM epg_listings")
    suspend fun clearEpg()
}
