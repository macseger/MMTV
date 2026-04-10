package com.example.mmtv.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.mmtv.model.MediaType

@Entity(tableName = "media_items")
data class MediaEntity(
    @PrimaryKey val id: Int, 
    val title: String,
    val icon: String?,
    val type: MediaType,
    val categoryId: String?,
    val categoryName: String?,
    val extension: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val epgId: String? = null,
    val categoryOrder: Int = 0,
    val itemOrder: Int = 0
)
