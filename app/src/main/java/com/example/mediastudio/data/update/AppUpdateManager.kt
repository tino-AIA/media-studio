package com.example.mediastudio.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.mediastudio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String = ""
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: AppReleaseInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {
    private const val PREFS_NAME = "mediastudio_prefs"
    private const val KEY_SHOW_18_PLUS = "show_18_plus_content"

    // Zamijeni ovaj URL stvarnim URL-om svog version.json dokumenta
    const val DEFAULT_VERSION_JSON_URL =
        "https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/version.json"

    fun is18PlusEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SHOW_18_PLUS, false)
    }

    fun set18PlusEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHOW_18_PLUS, enabled).apply()
    }

    fun getCurrentVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getCurrentVersionName(): String = BuildConfig.VERSION_NAME

    suspend fun checkForUpdate(
        versionJsonUrl: String = DEFAULT_VERSION_JSON_URL
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(versionJsonUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext UpdateCheckResult.Error("Nije moguće dohvatiti podatke o verziji (HTTP $code).")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val remoteCode = json.optInt("versionCode", 0)
            val remoteName = json.optString("versionName", "").ifBlank { "v$remoteCode" }
            val apkUrl = json.optString("apkUrl", "").trim()
            val changelog = json.optString("changelog", json.optString("notes", "")).trim()

            if (remoteCode > BuildConfig.VERSION_CODE && apkUrl.startsWith("http")) {
                UpdateCheckResult.UpdateAvailable(
                    AppReleaseInfo(
                        versionCode = remoteCode,
                        versionName = remoteName,
                        apkUrl = apkUrl,
                        changelog = changelog
                    )
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error("Greška pri provjeri ažuriranja: ${e.localizedMessage ?: "nema veze"}")
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "mediastudio_update.apk")
            if (apkFile.exists()) apkFile.delete()

            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
            }
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Preuzimanje APK-a nije uspjelo (HTTP $responseCode)."))
            }

            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0L) {
                            val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                    output.flush()
                }
            }

            if (!apkFile.exists() || apkFile.length() < 100_000L) {
                return@withContext Result.failure(IllegalStateException("Preuzeta APK datoteka je nepotpuna."))
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
                launchInstaller(context, apkFile)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
