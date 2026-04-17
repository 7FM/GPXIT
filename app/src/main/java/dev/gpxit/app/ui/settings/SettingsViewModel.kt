package dev.gpxit.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    fun setMaxStationsToCheck(n: Int) {
        viewModelScope.launch { prefsRepository.setMaxStationsToCheck(n) }
    }

    fun setPoiDbAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setPoiDbAutoUpdate(enabled) }
    }

    fun setTripTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setTripTrackingEnabled(enabled) }
    }

    fun toggleConnectionProduct(product: String) {
        viewModelScope.launch {
            val current = preferences.value.connectionProducts
            val updated = if (product in current) current - product else current + product
            prefsRepository.setConnectionProducts(updated)
        }
    }
}
