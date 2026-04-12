package com.example.mmtv.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channel_metadata")
data class ChannelEntity(
    @PrimaryKey val epgId: String,
    val displayName: String?,
    val icon: String?
)
