package com.example.mmtv.model

enum class MetadataSource {
    PROVIDER,
    TMDB;

    companion object {
        fun fromStoredValue(value: String?): MetadataSource =
            entries.firstOrNull { it.name == value } ?: TMDB
    }
}
