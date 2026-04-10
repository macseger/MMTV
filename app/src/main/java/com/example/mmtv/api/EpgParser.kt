package com.example.mmtv.api

import android.util.Xml
import com.example.mmtv.model.EpgListing
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class EpgParser {
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

    fun parse(inputStream: InputStream): Map<String, List<EpgListing>> {
        val result = mutableMapOf<String, MutableList<EpgListing>>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            var currentChannel: String? = null
            var currentTitle: String? = null
            var currentDesc: String? = null
            var startTime: Long = 0
            var stopTime: Long = 0

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (name) {
                            "programme" -> {
                                currentChannel = parser.getAttributeValue(null, "channel")
                                startTime = parseDate(parser.getAttributeValue(null, "start"))
                                stopTime = parseDate(parser.getAttributeValue(null, "stop"))
                            }
                            "title" -> currentTitle = parser.nextText()
                            "desc" -> currentDesc = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "programme" && currentChannel != null) {
                            val listing = EpgListing(
                                id = null,
                                epgId = currentChannel,
                                title = currentTitle,
                                description = currentDesc,
                                start = null,
                                end = null,
                                startTimestamp = startTime,
                                stopTimestamp = stopTime
                            )
                            result.getOrPut(currentChannel) { mutableListOf() }.add(listing)
                            currentTitle = null
                            currentDesc = null
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    suspend fun parseStreaming(inputStream: InputStream, onProgrammeParsed: suspend (String, EpgListing) -> Unit) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            var currentChannel: String? = null
            var currentTitle: String? = null
            var currentDesc: String? = null
            var startTime: Long = 0
            var stopTime: Long = 0

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (name) {
                            "programme" -> {
                                currentChannel = parser.getAttributeValue(null, "channel")
                                startTime = parseDate(parser.getAttributeValue(null, "start"))
                                stopTime = parseDate(parser.getAttributeValue(null, "stop"))
                            }
                            "title" -> currentTitle = parser.nextText()
                            "desc" -> currentDesc = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "programme" && currentChannel != null) {
                            val listing = EpgListing(
                                id = null,
                                epgId = currentChannel,
                                title = currentTitle,
                                description = currentDesc,
                                start = null,
                                end = null,
                                startTimestamp = startTime,
                                stopTimestamp = stopTime
                            )
                            onProgrammeParsed(currentChannel, listing)
                            currentTitle = null
                            currentDesc = null
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0
        return try {
            // XC formats vary: "20231027120000 +0200" or just "20231027120000"
            val cleanStr = dateStr.trim()
            if (cleanStr.contains(" ")) {
                dateFormat.parse(cleanStr)?.time?.div(1000) ?: 0
            } else {
                // Try without timezone if parsing fails
                val simpleFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                simpleFormat.parse(cleanStr)?.time?.div(1000) ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
