package com.example.mmtv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_listings",
    indices = [Index(value = ["epgId", "startTimestamp", "stopTimestamp"], unique = true)]
)
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgId: String,
    val channelName: String? = null,
    val title: String?,
    val description: String?,
    val startTimestamp: Long,
    val stopTimestamp: Long,
    val icon: String? = null
)
