package com.example.mmtv.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "picons")
data class PiconEntity(
    @PrimaryKey val name: String,
    val url: String,
    val localPath: String? = null
)
