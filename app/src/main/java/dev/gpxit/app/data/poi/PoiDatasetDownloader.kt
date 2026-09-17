package dev.gpxit.app.data.poi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads the POI dataset index and the per-country SQLite datasets
 * from the GitHub Release published by the `build-poi-dataset` workflow,
 * checks and unpacks them, and hands each finished file to
 * [PoiDatabase.install].
 *
 * The release tag `poi-data` is kept stable across monthly rebuilds so
 * the asset URLs don't change.
 */
class PoiDatasetDownloader(
    private val context: Context,
    private val baseUrl: String = BASE_URL,
) {

    enum class Outcome {
        INSTALLED,

        /** The file isn't the one the index describes — the index is older than the file. */
        CHECKSUM_MISMATCH,
        FAILED,
    }

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

    private val userAgent: String by lazy {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        "GPXIT/$version (https://github.com/7FM/GPXIT)"
    }

    private val stagingDir = File(context.filesDir, "pois-download")

    /** Removes what interrupted downloads left behind; call while none runs. */
    fun deleteLeftovers() {
        stagingDir.deleteRecursively()
    }

    /** The current `pois-index.json`. */
    suspend fun fetchIndex(): String = withContext(Dispatchers.IO) {
        val conn = connect(baseUrl + INDEX_FILE)
        try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Fetch, verify and unpack [dataset]. On success, swaps the file in
     * behind [database]. Progress is reported via the suspending callback
     * on [Dispatchers.IO]. Cancellable.
     */
    suspend fun download(
        dataset: PoiDataset,
        database: PoiDatabase,
        onProgress: suspend (Progress) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        // In filesDir rather than cacheDir: files created in the cache
        // directory keep its cache group when moved, and would be counted
        // as cache.
        stagingDir.mkdirs()
        val gzFile = File(stagingDir, "${dataset.id}.db.gz")
        val dbStaging = File(stagingDir, "${dataset.id}.db")
        try {
            onProgress(Progress(active = true, label = "Connecting…"))
            val conn = connect(baseUrl + dataset.file)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: dataset.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            try {
                conn.inputStream.use { inp ->
                    gzFile.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
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
            } finally {
                conn.disconnect()
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha256.equals(dataset.sha256, ignoreCase = true)) {
                // Most likely the monthly rebuild replaced the file after
                // the index was fetched.
                Log.w(TAG, "${dataset.id}: checksum $sha256, index says ${dataset.sha256}")
                onProgress(Progress(failed = true, label = "Download didn't match the index — try again"))
                return@withContext Outcome.CHECKSUM_MISMATCH
            }

            onProgress(Progress(active = true, label = "Unpacking…"))
            GZIPInputStream(gzFile.inputStream()).use { gz ->
                dbStaging.outputStream().use { out ->
                    gz.copyTo(out)
                    // On disk before it's renamed into place: after a power
                    // loss the rename can survive while the data doesn't.
                    out.fd.sync()
                }
            }
            gzFile.delete()
            ensureActive()

            // Smoke-test that the unpacked file is actually a SQLite DB
            // we can open before swapping it in.
            val ok = SQLiteDatabase.openDatabase(
                dbStaging.absolutePath, null, SQLiteDatabase.OPEN_READONLY
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
                onProgress(Progress(failed = true, label = "Downloaded file is not a valid POI DB"))
                return@withContext Outcome.FAILED
            }

            database.install(dataset.id, dbStaging)
            onProgress(Progress(isComplete = true, label = "Done"))
            Outcome.INSTALLED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download of ${dataset.id} failed: ${e.message}")
            onProgress(Progress(failed = true, label = "Failed: ${e.message}"))
            Outcome.FAILED
        } finally {
            gzFile.delete()
            dbStaging.delete()
        }
    }

    private fun connect(url: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw IOException("HTTP $code from release")
        }
        return conn
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
        const val BASE_URL = "https://github.com/7FM/GPXIT/releases/download/poi-data/"
        const val INDEX_FILE = "pois-index.json"
        const val TAG = "PoiDatasetDownloader"
    }
}
