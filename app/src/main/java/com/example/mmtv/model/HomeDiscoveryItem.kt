package com.example.mmtv.model

data class HomeDiscoveryItem(
    val media: MediaSource,
    val tmdbId: Int,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val rating: Double? = null
)
