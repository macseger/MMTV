package com.example.mmtv.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.mmtv.model.MediaSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mmtv_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveLogin(host: String, user: String, pass: String) {
        prefs.edit {
            putString("host", host)
            putString("user", user)
            putString("pass", pass)
            putBoolean("is_logged_in", true)
        }
    }

    fun getLogin(): Triple<String, String, String>? {
        val host = prefs.getString("host", null)
        val user = prefs.getString("user", null)
        val pass = prefs.getString("pass", null)
        return if (host != null && user != null && pass != null) {
            Triple(host, user, pass)
        } else null
    }

    fun saveSubtitlePreference(streamId: Int, language: String?) {
        prefs.edit {
            if (language == null) {
                remove("sub_$streamId")
            } else {
                putString("sub_$streamId", language)
            }
        }
    }

    fun getSubtitlePreference(streamId: Int): String? {
        return prefs.getString("sub_$streamId", null)
    }

    fun savePlaybackPosition(mediaId: String, position: Long) {
        prefs.edit {
            putLong("pos_$mediaId", position)
        }
    }

    fun getPlaybackPosition(mediaId: String): Long {
        return prefs.getLong("pos_$mediaId", 0L)
    }

    fun clearPlaybackPosition(mediaId: String) {
        prefs.edit {
            remove("pos_$mediaId")
        }
    }

    fun saveCategoryOrder(type: String, order: List<String>) {
        prefs.edit {
            putString("order_$type", gson.toJson(order))
        }
    }

    fun getCategoryOrder(type: String): List<String> {
        val json = prefs.getString("order_$type", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun hideCategory(type: String, categoryTitle: String) {
        val hidden = getHiddenCategories(type).toMutableSet()
        hidden.add(categoryTitle)
        prefs.edit {
            putString("hidden_$type", gson.toJson(hidden))
        }
    }

    fun getHiddenCategories(type: String): Set<String> {
        val json = prefs.getString("hidden_$type", null) ?: return emptySet()
        return try {
            gson.fromJson(json, object : TypeToken<Set<String>>() {}.type)
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun clearHiddenCategories() {
        prefs.edit {
            remove("hidden_live")
            remove("hidden_movies")
            remove("hidden_series")
        }
    }

    fun setTunnelingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("tunneling_enabled", enabled) }
    }

    fun isTunnelingEnabled(): Boolean {
        return prefs.getBoolean("tunneling_enabled", false)
    }

    fun setBufferSize(ms: Int) {
        prefs.edit { putInt("buffer_size", ms) }
    }

    fun getBufferSize(): Int {
        return prefs.getInt("buffer_size", 5000)
    }

    // Historikhantering
    fun addToHistory(media: MediaSource) {
        val history = getHistory().toMutableList()
        history.removeAll { it.id == media.id && it.type == media.type }
        history.add(0, media)
        if (history.size > 50) history.removeAt(history.size - 1)
        prefs.edit {
            putString("watch_history", gson.toJson(history))
        }
    }

    fun getHistory(): List<MediaSource> {
        val json = prefs.getString("watch_history", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<MediaSource>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun logout() {
        prefs.edit { clear() }
    }
}
