package com.example.mmtv.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.mmtv.model.MediaSource
import com.example.mmtv.model.Episode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SessionManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "mmtv_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val legacyPrefs: SharedPreferences = context.getSharedPreferences("mmtv_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        migrateLegacyPrefs()
    }

    private fun migrateLegacyPrefs() {
        if (legacyPrefs.all.isNotEmpty() && !prefs.contains("is_logged_in")) {
            prefs.edit {
                legacyPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value as Set<String>)
                    }
                }
            }
            legacyPrefs.edit { clear() }
        }
    }

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

    fun setBufferSize(ms: Int) {
        prefs.edit { putInt("buffer_size", ms) }
    }

    fun getBufferSize(): Int {
        return prefs.getInt("buffer_size", 5000)
    }

    fun setAutoPlayNext(enabled: Boolean) {
        prefs.edit { putBoolean("auto_play_next", enabled) }
    }

    fun getAutoPlayNext(): Boolean {
        // Vi sätter standardvärdet till true om inget är sparat
        if (!prefs.contains("auto_play_next")) {
            prefs.edit { putBoolean("auto_play_next", true) }
            return true
        }
        return prefs.getBoolean("auto_play_next", true)
    }

    fun setUseExternalSwedishEpg(enabled: Boolean) {
        prefs.edit { putBoolean("use_external_se_epg", enabled) }
    }

    fun getUseExternalSwedishEpg(): Boolean {
        return prefs.getBoolean("use_external_se_epg", false)
    }

    fun setUseTunneling(enabled: Boolean) {
        prefs.edit { putBoolean("use_tunneling", enabled) }
    }

    fun getUseTunneling(): Boolean {
        return prefs.getBoolean("use_tunneling", false)
    }

    fun setSyncOnlyLive(enabled: Boolean) {
        prefs.edit { putBoolean("sync_only_live", enabled) }
    }

    fun getSyncOnlyLive(): Boolean {
        return prefs.getBoolean("sync_only_live", true)
    }

    // Serie-specifikt: Spara sista spelade avsnittet
    fun saveLastEpisodeId(seriesId: Int, episodeId: String) {
        prefs.edit { putString("last_ep_id_$seriesId", episodeId) }
    }

    fun getLastEpisodeId(seriesId: Int): String? {
        return prefs.getString("last_ep_id_$seriesId", null)
    }

    // Historikhantering
    fun addToHistory(media: MediaSource, episode: Episode? = null) {
        val history = getHistory().toMutableList()
        // If it's an episode, we might want to store it differently, 
        // but for now let's just use the media as before or a slightly modified version
        val item = if (episode != null) {
            media.copy(title = "${media.title} - ${episode.title}") 
        } else media

        history.removeAll { it.id == item.id && it.type == item.type }
        history.add(0, item)
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

    fun clearHistory() {
        prefs.edit {
            remove("watch_history")
        }
    }

    fun logout() {
        prefs.edit(commit = true) { clear() }
    }
}
