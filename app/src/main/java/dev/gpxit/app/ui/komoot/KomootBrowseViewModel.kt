package dev.gpxit.app.ui.komoot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpxit.app.data.komoot.KomootApi
import dev.gpxit.app.data.komoot.KomootCredentialStore
import dev.gpxit.app.data.komoot.KomootError
import dev.gpxit.app.data.komoot.KomootTourSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the "Browse my Komoot tours" picker. Loads pages on demand
 * (initial load + tap-to-load-more) and surfaces auth / network errors
 * via [KomootBrowseUiState.error].
 */
class KomootBrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val credentialStore = KomootCredentialStore(application)
    private val api = KomootApi(userAgent = USER_AGENT)

    private val _state = MutableStateFlow(KomootBrowseUiState())
    val state: StateFlow<KomootBrowseUiState> = _state

    /**
     * Load (or reload) the first page. Replaces any existing tours.
     * Called from a `LaunchedEffect(Unit)` on the screen.
     */
    fun loadInitial() {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            _state.value = KomootBrowseUiState(isLoading = true)
            fetchPage(0, replace = true)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || !current.hasMore) return
        viewModelScope.launch {
            _state.value = current.copy(isLoading = true)
            fetchPage(current.nextPage, replace = false)
        }
    }

    private suspend fun fetchPage(page: Int, replace: Boolean) {
        try {
            val creds = credentialStore.load()
                ?: throw KomootError.MissingCredentials
            val result = api.listPlannedTours(creds, page)
            val merged = if (replace) result.tours else _state.value.tours + result.tours
            _state.value = KomootBrowseUiState(
                tours = merged,
                isLoading = false,
                hasMore = result.hasMore,
                nextPage = page + 1,
                error = null,
            )
        } catch (e: KomootError.MissingCredentials) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Sign in to Komoot in Settings first.",
                missingCredentials = true,
            )
        } catch (e: KomootError) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = e.message,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Couldn't reach Komoot: ${e.message}",
            )
        }
    }

    private companion object {
        const val USER_AGENT = "GPXIT/0.1.0 (+https://github.com/7FM/GPXIT)"
    }
}

data class KomootBrowseUiState(
    val tours: List<KomootTourSummary> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 0,
    val error: String? = null,
    val missingCredentials: Boolean = false,
)
