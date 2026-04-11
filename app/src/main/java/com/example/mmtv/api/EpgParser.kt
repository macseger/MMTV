package com.example.mmtv.api

import android.util.Xml
import com.example.mmtv.model.EpgListing
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class EpgParser {
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val simpleFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    fun parse(inputStream: InputStream, onChannelParsed: ((String, String?) -> Unit)? = null): Map<String, List<EpgListing>> {
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
                            "channel" -> {
                                val id = parser.getAttributeValue(null, "id")
                                var icon: String? = null
                                while (parser.next() != XmlPullParser.END_TAG || parser.name != "channel") {
                                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "icon") {
                                        icon = parser.getAttributeValue(null, "src")
                                    }
                                    if (parser.eventType == XmlPullParser.END_DOCUMENT) break
                                }
                                if (id != null) onChannelParsed?.invoke(id, icon)
                            }
                            "programme" -> {
                                currentChannel = parser.getAttributeValue(null, "channel")
                                startTime = parseDate(parser.getAttributeValue(null, "start"))
                                stopTime = parseDate(parser.getAttributeValue(null, "stop"))
                            }
                            "title" -> {
                                if (parser.next() == XmlPullParser.TEXT) {
                                    currentTitle = parser.text
                                }
                            }
                            "desc" -> {
                                if (parser.next() == XmlPullParser.TEXT) {
                                    currentDesc = parser.text
                                }
                            }
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

    suspend fun parseStreaming(
        inputStream: InputStream, 
        onChannelParsed: (suspend (String, String?) -> Unit)? = null,
        onProgrammeParsed: suspend (String, EpgListing) -> Unit
    ) {
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
                            "channel" -> {
                                val id = parser.getAttributeValue(null, "id")
                                var icon: String? = null
                                // Vi behöver gå igenom barnen för att hitta icon
                                var channelDepth = 1
                                while (channelDepth > 0) {
                                    val nextType = parser.next()
                                    if (nextType == XmlPullParser.START_TAG) {
                                        channelDepth++
                                        if (parser.name == "icon") {
                                            icon = parser.getAttributeValue(null, "src")
                                        }
                                    } else if (nextType == XmlPullParser.END_TAG) {
                                        channelDepth--
                                    } else if (nextType == XmlPullParser.END_DOCUMENT) {
                                        break
                                    }
                                }
                                if (id != null) onChannelParsed?.invoke(id, icon)
                            }
                            "programme" -> {
                                currentChannel = parser.getAttributeValue(null, "channel")
                                startTime = parseDate(parser.getAttributeValue(null, "start"))
                                stopTime = parseDate(parser.getAttributeValue(null, "stop"))
                            }
                            "title" -> {
                                if (parser.next() == XmlPullParser.TEXT) {
                                    currentTitle = parser.text
                                }
                            }
                            "desc" -> {
                                if (parser.next() == XmlPullParser.TEXT) {
                                    currentDesc = parser.text
                                }
                            }
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
            val cleanStr = dateStr.trim()
            if (cleanStr.contains(" ")) {
                dateFormat.parse(cleanStr)?.time?.div(1000) ?: 0
            } else {
                simpleFormat.parse(cleanStr)?.time?.div(1000) ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
