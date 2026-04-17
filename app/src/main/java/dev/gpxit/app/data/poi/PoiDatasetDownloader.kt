package dev.gpxit.app.data.poi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads the filtered SQLite POI dataset from the GitHub Release
 * published by the `build-poi-dataset` workflow, gunzips it, and hands
 * the finished file to [PoiDatabase.replaceWith].
 *
 * The release tag `poi-data` is kept stable across monthly rebuilds so
 * the asset URL below doesn't need to change.
 */
class PoiDatasetDownloader(private val context: Context) {

    data class Progress(
        val active: Boolean = false,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = 0,
        val label: String = "",
        val isComplete: Boolean = false,
        val failed: Boolean = false,
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) else 0f
    }

    /**
     * Fetch + unpack the dataset. On success, atomically swaps the file
     * in behind [database] and returns true. Progress is reported via
     * the suspending callback on [Dispatchers.IO].
     */
    suspend fun download(
        database: PoiDatabase,
        onProgress: suspend (Progress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val gzFile = File(context.filesDir, "pois.db.gz.download")
        val dbStaging = File(context.filesDir, "pois.db.staging")
        try {
            onProgress(Progress(active = true, label = "Connecting…"))
            val conn = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GPXIT/1.0")
            }
            val code = conn.responseCode
            if (code != 200) {
                onProgress(
                    Progress(failed = true, label = "HTTP $code from release")
                )
                return@withContext false
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)

            if (gzFile.exists()) gzFile.delete()
            var downloaded = 0L
            conn.inputStream.use { inp ->
                gzFile.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport >= 256 * 1024) {
                            lastReport = downloaded
                            onProgress(
                                Progress(
                                    active = true,
                                    bytesDownloaded = downloaded,
                                    totalBytes = total,
                                    label = formatLabel(downloaded, total, "Downloading")
                                )
                            )
                        }
                    }
                }
            }

            onProgress(Progress(active = true, label = "Unpacking…"))
            if (dbStaging.exists()) dbStaging.delete()
            GZIPInputStream(gzFile.inputStream()).use { gz ->
                dbStaging.outputStream().use { out -> gz.copyTo(out) }
            }
            gzFile.delete()

            // Smoke-test that the unpacked file is actually a SQLite DB
            // we can open before swapping it in.
            val ok = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbStaging.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { test ->
                try {
                    test.rawQuery("SELECT 1 FROM pois LIMIT 1", null).use { it.moveToFirst() }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "downloaded DB failed smoke test: ${e.message}")
                    false
                }
            }
            if (!ok) {
                dbStaging.delete()
                onProgress(Progress(failed = true, label = "Downloaded file is not a valid POI DB"))
                return@withContext false
            }

            database.replaceWith(dbStaging)
            onProgress(Progress(isComplete = true, label = "Done"))
            true
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            onProgress(Progress(failed = true, label = "Failed: ${e.message}"))
            false
        } finally {
            if (gzFile.exists()) gzFile.delete()
            if (dbStaging.exists()) dbStaging.delete()
        }
    }

    private fun formatLabel(downloaded: Long, total: Long, verb: String): String {
        val mb = downloaded / 1_000_000.0
        return if (total > 0) {
            val totalMb = total / 1_000_000.0
            "$verb %.1f / %.1f MB".format(mb, totalMb)
        } else {
            "$verb %.1f MB".format(mb)
        }
    }

    private companion object {
        const val DOWNLOAD_URL =
            "https://github.com/7FM/GPXIT/releases/download/poi-data/pois.db.gz"
        const val TAG = "PoiDatasetDownloader"
    }
}
