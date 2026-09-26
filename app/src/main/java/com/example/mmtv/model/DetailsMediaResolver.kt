package com.example.mmtv.model

fun resolveCanonicalSeriesDetailsMedia(
    selectedMedia: MediaSource,
    canonicalMedia: MediaSource?
): MediaSource {
    if (selectedMedia.type != MediaType.SERIES) return selectedMedia
    return canonicalMedia?.takeIf {
        it.type == MediaType.SERIES &&
            it.id == selectedMedia.id &&
            !it.title.isNullOrBlank()
    } ?: selectedMedia
}
