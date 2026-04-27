package dev.gpxit.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpxit.app.data.komoot.KomootApi
import dev.gpxit.app.data.komoot.KomootCredentialStore
import dev.gpxit.app.data.komoot.KomootError
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.data.transit.TransitRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepository = PrefsRepository(application)
    private val transitRepository = TransitRepository()
    private val komootCredentialStore = KomootCredentialStore(application)
    private val komootApi = KomootApi(userAgent = "GPXIT/0.1.0 (+https://github.com/7FM/GPXIT)")

    private val _komootState = MutableStateFlow(KomootSettingsState())
    val komootState: StateFlow<KomootSettingsState> = _komootState

    init {
        // Pull existing creds (if any) into the editable fields so the
        // user sees what's stored — easier to re-test or rotate.
        viewModelScope.launch {
            val existing = komootCredentialStore.load()
            if (existing != null) {
                _komootState.value = _komootState.value.copy(
                    email = existing.email,
                    password = existing.password,
                    userId = existing.userId.orEmpty(),
                    isConfigured = true,
                )
            }
        }
    }

    val preferences: StateFlow<PrefsRepository.UserPreferences> = prefsRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PrefsRepository.UserPreferences())

    private val _stationSuggestions = MutableStateFlow<List<TransitRepository.StationSuggestion>>(emptyList())
    val stationSuggestions: StateFlow<List<TransitRepository.StationSuggestion>> = _stationSuggestions

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            _stationSuggestions.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            _stationSuggestions.value = try {
                transitRepository.suggestLocations(query)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun setHomeStation(suggestion: TransitRepository.StationSuggestion) {
        viewModelScope.launch {
            prefsRepository.setHomeStation(suggestion.id, suggestion.name, suggestion.lat, suggestion.lon)
            _stationSuggestions.value = emptyList()
        }
    }

    fun setSpeed(kmh: Double) {
        viewModelScope.launch {
            prefsRepository.setAvgSpeed(kmh)
        }
    }

    fun setSearchRadius(meters: Int) {
        viewModelScope.launch {
            prefsRepository.setSearchRadius(meters)
        }
    }

    fun toggleProduct(product: String) {
        viewModelScope.launch {
            val current = preferences.value.enabledProducts
            val updated = if (product in current) current - product else current + product
            prefsRepository.setEnabledProducts(updated)
        }
    }

    fun setMinWaitBuffer(minutes: Int) {
        viewModelScope.launch { prefsRepository.setMinWaitBuffer(minutes) }
    }

    fun setMaxWaitMinutes(minutes: Int) {
        viewModelScope.launch { prefsRepository.setMaxWaitMinutes(minutes) }
    }

    fun setElevationAwareTime(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setElevationAwareTime(enabled) }
    }

    fun setShowElevationGraph(show: Boolean) {
        viewModelScope.launch { prefsRepository.setShowElevationGraph(show) }
    }

    fun setPoiGrocery(on: Boolean) {
        viewModelScope.launch { prefsRepository.setPoiGrocery(on) }
    }

    fun setPoiWater(on: Boolean) {
        viewModelScope.launch { prefsRepository.setPoiWater(on) }
    }

    fun setPoiToilet(on: Boolean) {
        viewModelScope.launch { prefsRepository.setPoiToilet(on) }
    }

    fun setPoiBikeRepair(on: Boolean) {
        viewModelScope.launch { prefsRepository.setPoiBikeRepair(on) }
    }

    fun setMaxStationsToCheck(n: Int) {
        viewModelScope.launch { prefsRepository.setMaxStationsToCheck(n) }
    }

    fun setPoiDbAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setPoiDbAutoUpdate(enabled) }
    }

    fun setTripTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setTripTrackingEnabled(enabled) }
    }

    fun setThemeMode(mode: dev.gpxit.app.ui.theme.ThemeMode) {
        viewModelScope.launch { prefsRepository.setThemeMode(mode) }
    }

    fun toggleConnectionProduct(product: String) {
        viewModelScope.launch {
            val current = preferences.value.connectionProducts
            val updated = if (product in current) current - product else current + product
            prefsRepository.setConnectionProducts(updated)
        }
    }

    fun setKomootEmail(value: String) {
        _komootState.value = _komootState.value.copy(email = value, statusMessage = null, isError = false)
    }

    fun setKomootPassword(value: String) {
        _komootState.value = _komootState.value.copy(password = value, statusMessage = null, isError = false)
    }

    /**
     * Accepts either a bare numeric ID or a `komoot.com/user/<id>` URL
     * (handy when the user just paste-and-grabs from their browser).
     * Garbage input is stored verbatim so the user sees their typo;
     * Save / Test will reject it.
     */
    fun setKomootUserId(value: String) {
        val cleaned = dev.gpxit.app.data.komoot.KomootUrlParser.parseUserId(value) ?: value
        _komootState.value = _komootState.value.copy(
            userId = cleaned, statusMessage = null, isError = false,
        )
    }

    /**
     * Persist the edited credentials. Empty values are rejected up front
     * so a stray Save tap doesn't wipe a working configuration.
     */
    fun saveKomootCredentials() {
        val s = _komootState.value
        if (s.email.isBlank() || s.password.isBlank()) {
            _komootState.value = s.copy(
                statusMessage = "Email and password are both required.",
                isError = true,
            )
            return
        }
        val userId = s.userId.trim().takeIf { it.isNotBlank() }
        if (userId != null && !userId.all { it.isDigit() }) {
            _komootState.value = s.copy(
                statusMessage = "User ID must be numeric (or a komoot.com/user/<id> URL).",
                isError = true,
            )
            return
        }
        viewModelScope.launch {
            _komootState.value = s.copy(isBusy = true, statusMessage = null, isError = false)
            try {
                komootCredentialStore.save(s.email.trim(), s.password, userId)
                _komootState.value = _komootState.value.copy(
                    isBusy = false,
                    isConfigured = true,
                    statusMessage = "Saved.",
                    isError = false,
                )
            } catch (e: Exception) {
                _komootState.value = _komootState.value.copy(
                    isBusy = false,
                    statusMessage = "Couldn't save: ${e.message}",
                    isError = true,
                )
            }
        }
    }

    /**
     * Round-trip the current creds against `users/me` to confirm Komoot
     * accepts them. Saves them first so the next Komoot import doesn't
     * silently use stale values.
     */
    fun testKomootConnection() {
        val s = _komootState.value
        if (s.email.isBlank() || s.password.isBlank()) {
            _komootState.value = s.copy(
                statusMessage = "Enter email and password first.",
                isError = true,
            )
            return
        }
        val userId = s.userId.trim().takeIf { it.isNotBlank() }
        if (userId != null && !userId.all { it.isDigit() }) {
            _komootState.value = s.copy(
                statusMessage = "User ID must be numeric (or a komoot.com/user/<id> URL).",
                isError = true,
            )
            return
        }
        viewModelScope.launch {
            _komootState.value = s.copy(isBusy = true, statusMessage = "Testing…", isError = false)
            try {
                komootCredentialStore.save(s.email.trim(), s.password, userId)
                val creds = komootCredentialStore.load() ?: run {
                    _komootState.value = _komootState.value.copy(
                        isBusy = false,
                        statusMessage = "Couldn't load saved credentials.",
                        isError = true,
                    )
                    return@launch
                }
                val result = komootApi.ping(creds)
                if (result.detectedUserId != null && userId == null) {
                    // Persist what /account/v1/account told us so
                    // Browse becomes available without the user
                    // touching the User ID field.
                    komootCredentialStore.save(
                        creds.email, creds.password, result.detectedUserId,
                    )
                    _komootState.value = _komootState.value.copy(
                        isBusy = false,
                        isConfigured = true,
                        userId = result.detectedUserId,
                        statusMessage = result.message,
                        isError = false,
                    )
                } else {
                    _komootState.value = _komootState.value.copy(
                        isBusy = false,
                        isConfigured = true,
                        statusMessage = result.message,
                        isError = false,
                    )
                }
            } catch (e: KomootError.Unauthorized) {
                _komootState.value = _komootState.value.copy(
                    isBusy = false,
                    statusMessage = "Komoot rejected those credentials.",
                    isError = true,
                )
            } catch (e: Exception) {
                _komootState.value = _komootState.value.copy(
                    isBusy = false,
                    statusMessage = "Couldn't reach Komoot: ${e.message}",
                    isError = true,
                )
            }
        }
    }

    fun clearKomootCredentials() {
        viewModelScope.launch {
            komootCredentialStore.clear()
            _komootState.value = KomootSettingsState()
        }
    }
}

data class KomootSettingsState(
    val email: String = "",
    val password: String = "",
    val userId: String = "",
    val isConfigured: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,
)
