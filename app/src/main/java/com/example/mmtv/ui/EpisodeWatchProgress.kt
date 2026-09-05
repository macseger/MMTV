package com.example.mmtv.ui

// Older saved positions have no player duration; use HH:MM:SS/MM:SS metadata.
internal fun episodeWatchProgress(position: Long, savedDuration: Long, metadataDuration: String?): Float? {
    val duration = if (savedDuration > 0) savedDuration else {
        val parts = metadataDuration?.trim()?.split(':') ?: return null
        if (parts.size !in 2..3) return null
        val values = parts.map { it.toLongOrNull() ?: return null }
        if (values.any { it < 0 } || values.drop(1).any { it >= 60 }) return null
        values.fold(0L) { seconds, value -> seconds * 60 + value } * 1000
    }
    // Match the player's resume threshold and completed-episode cutoff.
    if (position <= 10_000 || duration <= 0 || position >= duration - 5_000) return null
    return (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
}
