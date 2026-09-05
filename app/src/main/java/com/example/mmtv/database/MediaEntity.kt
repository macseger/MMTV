package com.example.mmtv.database

import androidx.room.Entity
import androidx.room.Index
import com.example.mmtv.model.MediaType

@Entity(
    tableName = "media_items",
    primaryKeys = ["id", "type"],
    indices = [
        Index(value = ["type", "categoryId", "itemOrder"]),
        Index(value = ["isFavorite", "favoriteDate"]),
        Index(value = ["addedDate"])
    ]
)
data class MediaEntity(
    val id: Int, 
    val title: String,
    val icon: String?,
    val resolvedIcon: String? = null,
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
    val itemOrder: Int = 0,
    val isFavorite: Boolean = false,
    val favoriteDate: Long = 0,
    val addedDate: Long = System.currentTimeMillis()
)
