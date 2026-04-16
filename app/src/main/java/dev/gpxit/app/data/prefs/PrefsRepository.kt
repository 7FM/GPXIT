package dev.gpxit.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gpxit_prefs")

class PrefsRepository(private val context: Context) {

    companion object {
        val HOME_STATION_ID = stringPreferencesKey("home_station_id")
        val HOME_STATION_NAME = stringPreferencesKey("home_station_name")
        val HOME_STATION_LAT = doublePreferencesKey("home_station_lat")
        val HOME_STATION_LON = doublePreferencesKey("home_station_lon")
        val AVG_SPEED_KMH = doublePreferencesKey("avg_speed_kmh")
        val SEARCH_RADIUS_METERS = intPreferencesKey("search_radius_meters")
        val SAMPLING_INTERVAL_METERS = intPreferencesKey("sampling_interval_meters")
        val ENABLED_PRODUCTS = stringSetPreferencesKey("enabled_products")
        val CONNECTION_PRODUCTS = stringSetPreferencesKey("connection_products")
        val MIN_WAIT_BUFFER_MINUTES = intPreferencesKey("min_wait_buffer_minutes")
        val MAX_WAIT_MINUTES = intPreferencesKey("max_wait_minutes")
        val USE_DARK_MAP = booleanPreferencesKey("use_dark_map")

        val DEFAULT_PRODUCTS = setOf("HIGH_SPEED_TRAIN", "REGIONAL_TRAIN", "SUBURBAN_TRAIN")
        val DEFAULT_CONNECTION_PRODUCTS = setOf(
            "REGIONAL_TRAIN", "SUBURBAN_TRAIN", "SUBWAY", "TRAM", "BUS", "FERRY"
        )
    }

    data class UserPreferences(
        val homeStationId: String? = null,
        val homeStationName: String? = null,
        val homeStationLat: Double? = null,
        val homeStationLon: Double? = null,
        val avgSpeedKmh: Double = 18.0,
        val searchRadiusMeters: Int = 2000,
        val samplingIntervalMeters: Int = 2000,
        val enabledProducts: Set<String> = DEFAULT_PRODUCTS,
        val connectionProducts: Set<String> = DEFAULT_CONNECTION_PRODUCTS,
        val minWaitBufferMinutes: Int = 0,
        val maxWaitMinutes: Int = 0,  // 0 = no limit
        val useDarkMap: Boolean = false
    )

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            homeStationId = prefs[HOME_STATION_ID],
            homeStationName = prefs[HOME_STATION_NAME],
            homeStationLat = prefs[HOME_STATION_LAT],
            homeStationLon = prefs[HOME_STATION_LON],
            avgSpeedKmh = prefs[AVG_SPEED_KMH] ?: 18.0,
            searchRadiusMeters = prefs[SEARCH_RADIUS_METERS] ?: 2000,
            samplingIntervalMeters = prefs[SAMPLING_INTERVAL_METERS] ?: 2000,
            enabledProducts = prefs[ENABLED_PRODUCTS] ?: DEFAULT_PRODUCTS,
            connectionProducts = prefs[CONNECTION_PRODUCTS] ?: DEFAULT_CONNECTION_PRODUCTS,
            minWaitBufferMinutes = prefs[MIN_WAIT_BUFFER_MINUTES] ?: 0,
            maxWaitMinutes = prefs[MAX_WAIT_MINUTES] ?: 0,
            useDarkMap = prefs[USE_DARK_MAP] ?: false
        )
    }

    suspend fun setHomeStation(id: String, name: String, lat: Double? = null, lon: Double? = null) {
        context.dataStore.edit { prefs ->
            prefs[HOME_STATION_ID] = id
            prefs[HOME_STATION_NAME] = name
            if (lat != null) prefs[HOME_STATION_LAT] = lat
            if (lon != null) prefs[HOME_STATION_LON] = lon
        }
    }

    suspend fun setAvgSpeed(kmh: Double) {
        context.dataStore.edit { it[AVG_SPEED_KMH] = kmh }
    }

    suspend fun setSearchRadius(meters: Int) {
        context.dataStore.edit { it[SEARCH_RADIUS_METERS] = meters }
    }

    suspend fun setSamplingInterval(meters: Int) {
        context.dataStore.edit { it[SAMPLING_INTERVAL_METERS] = meters }
    }

    suspend fun setEnabledProducts(products: Set<String>) {
        context.dataStore.edit { it[ENABLED_PRODUCTS] = products }
    }

    suspend fun setConnectionProducts(products: Set<String>) {
        context.dataStore.edit { it[CONNECTION_PRODUCTS] = products }
    }

    suspend fun setMinWaitBuffer(minutes: Int) {
        context.dataStore.edit { it[MIN_WAIT_BUFFER_MINUTES] = minutes }
    }

    suspend fun setMaxWaitMinutes(minutes: Int) {
        context.dataStore.edit { it[MAX_WAIT_MINUTES] = minutes }
    }

    suspend fun setUseDarkMap(use: Boolean) {
        context.dataStore.edit { it[USE_DARK_MAP] = use }
    }
}
