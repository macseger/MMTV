package com.example.mmtv.repository

// Only normalize known Swedish prefixes, not arbitrary country identifiers.
internal fun normalizeEpgChannelName(name: String): String = name.uppercase(java.util.Locale.ROOT)
    .replace(Regex("^(?:SE|SWE|SWEDEN)(?:\\s*[:|_-]\\s*|\\s+)"), "")
    .replace(Regex("\\s+(?:FHD|UHD|HD|SD|4K)$"), "")
    .replace(Regex("[^\\p{L}\\p{N}]"), "")

internal fun decodeEpgText(value: String?): String? {
    if (value.isNullOrBlank()) return value
    return try {
        val decoded = java.util.Base64.getDecoder().decode(value)
        val text = decoded.toString(Charsets.UTF_8)
        if (text.any { it == '\uFFFD' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) value else text
    } catch (_: IllegalArgumentException) { value }
}
