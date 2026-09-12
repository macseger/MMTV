package com.example.mmtv.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonParseException
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val releaseName: String?,
    val releaseNotes: String?,
    val apkUrl: String,
    val assetName: String,
    val assetContentType: String?,
    val assetSize: Long
)

data class UpdateCheckResult(
    val updateInfo: UpdateInfo? = null,
    val isUpToDate: Boolean = false,
    val errorMessage: String? = null
)

private data class GithubRelease(
    val tag_name: String?,
    val name: String?,
    val body: String?,
    val assets: List<GithubAsset>?
)

private data class GithubAsset(
    val name: String?,
    val browser_download_url: String?,
    val content_type: String?,
    val size: Long?
)

class UpdateManager(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var downloadId: Long = -1
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(currentVersionName: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/macseger/MMTV/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MMTV/$currentVersionName")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = if (response.code == 404) {
                        "Ingen publicerad release hittades"
                    } else {
                        "Kunde inte kontrollera uppdateringar"
                    }
                    return@withContext UpdateCheckResult(errorMessage = message)
                }

                val json = response.body?.string()
                    ?: return@withContext UpdateCheckResult(errorMessage = "Senaste releasen kunde inte läsas")
                val release = try {
                    Gson().fromJson(json, GithubRelease::class.java)
                } catch (e: JsonParseException) {
                    return@withContext UpdateCheckResult(errorMessage = "Senaste releasen kunde inte läsas")
                }
                val releaseVersion = parseVersion(release?.tag_name)
                    ?: return@withContext UpdateCheckResult(errorMessage = "Releaseversionen kunde inte läsas")

                val apkAssets = release.assets.orEmpty().filter { asset ->
                    val name = asset.name.orEmpty()
                    name.endsWith(".apk", ignoreCase = true) &&
                        !asset.browser_download_url.isNullOrBlank()
                }
                if (apkAssets.isEmpty()) {
                    return@withContext UpdateCheckResult(errorMessage = "Ingen APK hittades i senaste releasen")
                }

                val preferredAssets = apkAssets.filter { it.name.orEmpty().contains("MMTV", ignoreCase = true) }
                val selectedAsset = when {
                    preferredAssets.size == 1 -> preferredAssets.single()
                    preferredAssets.size > 1 -> null
                    apkAssets.size == 1 -> apkAssets.single()
                    else -> null
                } ?: return@withContext UpdateCheckResult(errorMessage = "Flera APK-filer hittades i senaste releasen")

                val availableVersion = releaseVersion.toVersionString()
                if (compareVersions(releaseVersion, parseVersion(currentVersionName) ?: Version(0, 0, 0)) <= 0) {
                    UpdateCheckResult(isUpToDate = true)
                } else {
                    UpdateCheckResult(
                        updateInfo = UpdateInfo(
                            versionName = availableVersion,
                            releaseName = release.name,
                            releaseNotes = release.body,
                            apkUrl = selectedAsset.browser_download_url!!,
                            assetName = selectedAsset.name!!,
                            assetContentType = selectedAsset.content_type,
                            assetSize = selectedAsset.size ?: 0L
                        )
                    )
                }
            }
        } catch (e: IOException) {
            Log.e("UpdateManager", "Error checking GitHub releases", e)
            UpdateCheckResult(errorMessage = "Kunde inte kontrollera uppdateringar")
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error parsing GitHub release", e)
            UpdateCheckResult(errorMessage = "Senaste releasen kunde inte läsas")
        }
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int)

    private fun parseVersion(value: String?): Version? {
        val normalized = value?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        val parts = normalized.split('.')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return Version(numbers.getOrElse(0) { 0 }, numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
    }

    private fun compareVersions(left: Version, right: Version): Int = when {
        left.major != right.major -> left.major.compareTo(right.major)
        left.minor != right.minor -> left.minor.compareTo(right.minor)
        else -> left.patch.compareTo(right.patch)
    }

    private fun Version.toVersionString(): String = "$major.$minor.$patch"

    fun downloadAndInstall(apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("MMTV Uppdatering")
            .setDescription("Laddar ner ny version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "mmtv_update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        // Ta bort gammal fil om den finns
        val oldFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "mmtv_update.apk")
        if (oldFile.exists()) oldFile.delete()

        downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk()
                    context.unregisterReceiver(this)
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk() {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mmtv_update.apk")
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
