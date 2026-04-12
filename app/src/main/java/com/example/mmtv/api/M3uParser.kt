package com.example.mmtv.api

import java.io.InputStream
import java.util.Scanner

data class M3uItem(
    val title: String,
    val logo: String? = null,
    val epgId: String? = null,
    val category: String? = null,
    val url: String? = null,
    val streamId: Int? = null,
    val type: String? = null // "live", "movie", "series"
)

class M3uParser {
    fun parse(inputStream: InputStream): List<M3uItem> {
        val items = mutableListOf<M3uItem>()
        val reader = inputStream.bufferedReader()
        var currentItemMeta: String? = null

        reader.forEachLine { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("#EXTINF:")) {
                currentItemMeta = trimmedLine
            } else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                if (currentItemMeta != null) {
                    val item = parseLine(currentItemMeta!!, trimmedLine)
                    if (item != null) items.add(item)
                    currentItemMeta = null
                }
            }
        }
        return items
    }

    private fun parseLine(meta: String, url: String): M3uItem? {
        // Exempel: #EXTINF:-1 tvg-id="SVT1.se" tvg-name="SVT 1 HD" tvg-logo="http://..." group-title="Sweden",SVT 1 HD
        val title = meta.substringAfterLast(",").trim()
        val logo = extractAttribute(meta, "tvg-logo")
        val epgId = extractAttribute(meta, "tvg-id")
        val category = extractAttribute(meta, "group-title")
        
        // Försök extrahera streamId från URL:en (vanligtvis .../username/password/ID)
        val streamId = url.substringAfterLast("/").substringBefore(".").toIntOrNull()
        
        val type = when {
            url.contains("/movie/") -> "movie"
            url.contains("/series/") -> "series"
            else -> "live"
        }

        return M3uItem(
            title = title,
            logo = logo,
            epgId = epgId,
            category = category,
            url = url,
            streamId = streamId,
            type = type
        )
    }

    private fun extractAttribute(meta: String, name: String): String? {
        val pattern = "$name=\"(.*?)\"".toRegex()
        return pattern.find(meta)?.groupValues?.get(1)
    }
}
