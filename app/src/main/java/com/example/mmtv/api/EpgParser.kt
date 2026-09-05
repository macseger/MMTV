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
    private val altFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun parse(inputStream: InputStream, onChannelParsed: ((String, String?, String?) -> Unit)? = null): Map<String, List<EpgListing>> {
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
                                var displayName: String? = null
                                
                                var depth = 1
                                while (depth > 0) {
                                    val nextType = parser.next()
                                    if (nextType == XmlPullParser.START_TAG) {
                                        depth++
                                        when (parser.name) {
                                            "icon" -> icon = parser.getAttributeValue(null, "src")
                                            "display-name" -> {
                                                if (parser.next() == XmlPullParser.TEXT) {
                                                    displayName = parser.text
                                                }
                                                // depth stannar kvar här för vi konsumerar inte END_TAG manuellt
                                            }
                                        }
                                    } else if (nextType == XmlPullParser.END_TAG) {
                                        depth--
                                    } else if (nextType == XmlPullParser.END_DOCUMENT) {
                                        break
                                    }
                                }
                                if (id != null) onChannelParsed?.invoke(id, displayName, icon)
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
        onChannelParsed: (suspend (String, String?, String?) -> Unit)? = null,
        acceptChannel: (String) -> Boolean = { true },
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
                                var displayName: String? = null
                                
                                var depth = 1
                                while (depth > 0) {
                                    val nextType = parser.next()
                                    if (nextType == XmlPullParser.START_TAG) {
                                        depth++
                                        when (parser.name) {
                                            "icon" -> icon = parser.getAttributeValue(null, "src")
                                            "display-name" -> {
                                                if (parser.next() == XmlPullParser.TEXT) {
                                                    displayName = parser.text
                                                }
                                                // depth stannar kvar här för vi konsumerar inte END_TAG manuellt
                                            }
                                        }
                                    } else if (nextType == XmlPullParser.END_TAG) {
                                        depth--
                                    } else if (nextType == XmlPullParser.END_DOCUMENT) {
                                        break
                                    }
                                }
                                if (id != null) onChannelParsed?.invoke(id, displayName, icon)
                            }
                            "programme" -> {
                                currentChannel = parser.getAttributeValue(null, "channel")
                                if (currentChannel == null || !acceptChannel(currentChannel)) {
                                    var depth = 1
                                    while (depth > 0) {
                                        when (parser.next()) {
                                            XmlPullParser.START_TAG -> depth++
                                            XmlPullParser.END_TAG -> depth--
                                            XmlPullParser.END_DOCUMENT -> throw java.io.IOException("Ofullständig EPG")
                                        }
                                    }
                                    currentChannel = null
                                    currentTitle = null
                                    currentDesc = null
                                } else {
                                startTime = parseDate(parser.getAttributeValue(null, "start"))
                                stopTime = parseDate(parser.getAttributeValue(null, "stop"))
                                }
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
            throw e
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0
        val cleanStr = dateStr.trim()
        
        // Testa olika format
        val formats = listOf(dateFormat, simpleFormat, altFormat)
        for (format in formats) {
            try {
                val date = format.parse(cleanStr)
                if (date != null) return date.time / 1000
            } catch (e: Exception) { }
        }
        
        // Sista försök: Bara siffror
        return try {
            val justDigits = cleanStr.replace(Regex("[^0-9]"), "")
            if (justDigits.length >= 14) {
                simpleFormat.parse(justDigits.take(14))?.time?.div(1000) ?: 0
            } else 0
        } catch (e: Exception) { 0 }
    }
}
