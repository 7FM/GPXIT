package dev.gpxit.app.data.poi

import android.content.Context
import android.util.Log
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.domain.RoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * Which POI datasets (countries) are on the device: the user's selection,
 * the published index, and a download queue that fetches one dataset at a
 * time. Lives as long as the app process, so downloads continue across
 * screens.
 */
class PoiDatasetManager private constructor(context: Context) {

    data class Download(
        val fraction: Float = 0f,
        val label: String = "Waiting…",
        val running: Boolean = false,
    )

    data class State(
        /** The published datasets; the cached copy until a fetch succeeds. */
        val index: PoiDatasetIndex? = null,
        val loadingIndex: Boolean = false,
        val indexError: String? = null,
        /** Datasets the user keeps on the device. */
        val selected: Set<String> = emptySet(),
        val installed: Map<String, PoiDatabase.DatasetInfo> = emptyMap(),
        /** Queued and running downloads. */
        val downloads: Map<String, Download> = emptyMap(),
        /** Why the last download of a dataset failed. */
        val errors: Map<String, String> = emptyMap(),
        /** Epoch ms of the last completed update check; 0 if none. */
        val lastUpdateCheckMs: Long = 0L,
    ) {
        /** Whether the index lists a different build than the installed file of [id]. */
        fun hasUpdate(id: String): Boolean {
            val published = index?.byId(id) ?: return false
            val local = installed[id] ?: return false
            return local.builtAt != published.builtAt
        }
    }

    private val database = PoiDatabase.get(context)
    private val downloader = PoiDatasetDownloader(context)
    private val prefs = PrefsRepository(context)
    private val indexCache = File(context.filesDir, "pois-index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val runningLock = Any()
    private var running: Pair<String, Job>? = null
    private val selectionLock = Mutex()
    private var started = false

    /** Completed once [start] has loaded the stored selection. */
    private val selectionLoaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            for (id in queue) runQueued(id)
        }
    }

    /**
     * Loads the local state, picks the datasets to keep on first use, and
     * fetches missing or (once a month, if enabled) newer datasets.
     */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            downloader.deleteLeftovers()
            loadCachedIndex()
            refreshInstalled()
            val p = prefs.preferences.first()
            _state.update { it.copy(lastUpdateCheckMs = p.poiDbLastUpdateMs) }
            var indexFetched = false
            val selected = selectionLock.withLock {
                val ids = p.poiDatasets ?: chooseDefault(p) { indexFetched = true }
                    ?.also { prefs.setPoiDatasets(it) }
                ids?.let { _state.update { s -> s.copy(selected = it) } }
                selectionLoaded.complete(Unit)
                ids
            } ?: return@launch

            val due = System.currentTimeMillis() - p.poiDbLastUpdateMs > UPDATE_INTERVAL_MS
            // Files from before per-country datasets hold duplicate POIs.
            val legacy = _state.value.installed.values.any { it.datasetId == null }
            if (p.poiDbAutoUpdate && (due || legacy)) {
                checkForUpdates(fetch = !indexFetched)
            } else {
                downloadMissing()
            }
        }
    }

    /**
     * What to keep on first use: what an older app version downloaded,
     * else the country of the home station or of the device's locale.
     * Null if that can't be decided yet (no index).
     */
    private suspend fun chooseDefault(
        p: PrefsRepository.UserPreferences,
        onIndexFetched: () -> Unit,
    ): Set<String>? {
        val installed = database.installedIds()
        if (installed.isNotEmpty()) return installed.toSet()
        val index = _state.value.index
            ?: fetchIndex()?.also { onIndexFetched() }
            ?: return null
        val home = if (p.homeStationLat != null && p.homeStationLon != null) {
            index.forLocation(p.homeStationLat, p.homeStationLon)
        } else {
            null
        }
        val dataset = home ?: Locale.getDefault().country.takeIf { it.isNotEmpty() }?.let(index::forCountry)
        return setOfNotNull(dataset?.id)
    }

    fun refreshIndex() {
        scope.launch { fetchIndex() }
    }

    /** Fetches the index, then the selected datasets that are missing or outdated. */
    fun updateAll() {
        scope.launch { checkForUpdates() }
    }

    fun setSelected(id: String, selected: Boolean) {
        scope.launch {
            selectionLoaded.await()
            selectionLock.withLock {
                val ids = _state.updateAndGet { s ->
                    s.copy(selected = if (selected) s.selected + id else s.selected - id)
                }.selected
                prefs.setPoiDatasets(ids)
            }
            if (selected) {
                val s = _state.value
                if (id !in s.installed || s.hasUpdate(id)) enqueue(id)
            } else {
                val job = synchronized(runningLock) {
                    _state.update { it.copy(downloads = it.downloads - id, errors = it.errors - id) }
                    running?.takeIf { it.first == id }?.second
                }
                job?.cancelAndJoin()
                database.remove(id)
                refreshInstalled()
            }
        }
    }

    fun select(ids: Collection<String>) {
        ids.forEach { setSelected(it, true) }
    }

    /**
     * The datasets [points] lie in, preferring selected ones where outlines
     * overlap. Empty while the index is unknown.
     */
    fun datasetsFor(points: List<RoutePoint>, state: State = _state.value): List<PoiDataset> {
        val index = state.index ?: return emptyList()
        return index.datasetsFor(
            PoiDatasetIndex.routeSamples(points),
            preferred = state.selected + state.installed.keys,
        )
    }

    /** [fetch]: false if the index was fetched a moment ago. */
    private suspend fun checkForUpdates(fetch: Boolean = true) {
        if (fetch && fetchIndex() == null) {
            downloadMissing()
            return
        }
        val s = _state.value
        val ids = s.selected.filter { it !in s.installed || s.hasUpdate(it) }
        ids.forEach(::enqueue)
        // Wait for this round of downloads; the check only counts as done
        // if they all succeeded, so failures are retried on the next start.
        val done = _state.first { state -> ids.none { it in state.downloads } }
        if (ids.none { it in done.errors }) {
            val now = System.currentTimeMillis()
            prefs.setPoiDbLastUpdate(now)
            _state.update { it.copy(lastUpdateCheckMs = now) }
        }
    }

    private fun downloadMissing() {
        val s = _state.value
        s.selected.filter { it !in s.installed }.forEach(::enqueue)
    }

    private fun enqueue(id: String) {
        val before = _state.getAndUpdate { s ->
            if (id in s.downloads) s
            else s.copy(downloads = s.downloads + (id to Download()), errors = s.errors - id)
        }
        if (id !in before.downloads) queue.trySend(id)
    }

    private suspend fun runQueued(id: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) { download(id) }
        synchronized(runningLock) {
            // Deselected while waiting.
            if (id !in _state.value.downloads) return
            running = id to job
        }
        job.start()
        job.join()
        synchronized(runningLock) { running = null }
    }

    private suspend fun download(id: String) {
        var error: String? = "Download failed"
        try {
            val index = _state.value.index ?: fetchIndex()
            val dataset = index?.byId(id)
            if (dataset == null) {
                error = if (index == null) "Couldn't load the list of countries" else "No longer offered"
                return
            }
            updateDownload(id, Download(label = "Connecting…", running = true))
            val onProgress: suspend (PoiDatasetDownloader.Progress) -> Unit = { p ->
                if (p.failed) error = p.label
                if (p.active) updateDownload(id, Download(p.fraction, p.label, running = true))
            }
            var outcome = downloader.download(dataset, database, onProgress)
            if (outcome == PoiDatasetDownloader.Outcome.CHECKSUM_MISMATCH) {
                // A rebuild was published after the index was fetched.
                val republished = fetchIndex()?.byId(id)
                if (republished != null && republished.sha256 != dataset.sha256) {
                    outcome = downloader.download(republished, database, onProgress)
                }
            }
            if (outcome == PoiDatasetDownloader.Outcome.INSTALLED) error = null
        } finally {
            refreshInstalled()
            _state.update { s ->
                if (id !in s.downloads) {
                    s // deselected
                } else {
                    s.copy(
                        downloads = s.downloads - id,
                        errors = error?.let { s.errors + (id to it) } ?: (s.errors - id),
                    )
                }
            }
        }
    }

    private fun updateDownload(id: String, download: Download) {
        _state.update { s ->
            if (id in s.downloads) s.copy(downloads = s.downloads + (id to download)) else s
        }
    }

    /** Fetches and caches the index; null (with [State.indexError] set) if that fails. */
    private suspend fun fetchIndex(): PoiDatasetIndex? {
        _state.update { it.copy(loadingIndex = true, indexError = null) }
        return try {
            val json = downloader.fetchIndex()
            val index = PoiDatasetIndex.parse(json)
            val tmp = File(indexCache.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(indexCache)) {
                indexCache.writeText(json)
                tmp.delete()
            }
            _state.update { it.copy(index = index, loadingIndex = false) }
            index
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetching the dataset index failed: ${e.message}")
            _state.update {
                it.copy(loadingIndex = false, indexError = e.message ?: e.javaClass.simpleName)
            }
            null
        }
    }

    private fun loadCachedIndex() {
        if (!indexCache.exists()) return
        try {
            val index = PoiDatasetIndex.parse(indexCache.readText())
            _state.update { if (it.index == null) it.copy(index = index) else it }
        } catch (e: Exception) {
            Log.w(TAG, "cached dataset index unreadable: ${e.message}")
            indexCache.delete()
        }
    }

    private fun refreshInstalled() {
        val installed = LinkedHashMap<String, PoiDatabase.DatasetInfo>()
        for (id in database.installedIds()) {
            database.info(id)?.let { installed[id] = it }
        }
        _state.update { it.copy(installed = installed) }
    }

    companion object {
        private const val TAG = "PoiDatasetManager"

        /** The datasets are rebuilt monthly. */
        private const val UPDATE_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: PoiDatasetManager? = null

        fun get(context: Context): PoiDatasetManager =
            instance ?: synchronized(this) {
                instance ?: PoiDatasetManager(context.applicationContext).also { instance = it }
            }
    }
}
