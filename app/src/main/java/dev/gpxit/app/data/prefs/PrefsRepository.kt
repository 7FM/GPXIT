package dev.gpxit.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
        val ELEVATION_AWARE_TIME = booleanPreferencesKey("elevation_aware_time")
        val SHOW_ELEVATION_GRAPH = booleanPreferencesKey("show_elevation_graph")
        val POI_GROCERY = booleanPreferencesKey("poi_grocery")
        val POI_WATER = booleanPreferencesKey("poi_water")
        val POI_TOILET = booleanPreferencesKey("poi_toilet")
        val MAX_STATIONS_TO_CHECK = intPreferencesKey("max_stations_to_check")
        val POI_DB_LAST_UPDATE_MS = longPreferencesKey("poi_db_last_update_ms")
        val POI_DB_AUTO_UPDATE = booleanPreferencesKey("poi_db_auto_update")

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
        val elevationAwareTime: Boolean = true,
        val showElevationGraph: Boolean = true,
        val poiGrocery: Boolean = false,
        val poiWater: Boolean = false,
        val poiToilet: Boolean = false,
        val maxStationsToCheck: Int = 8,
        /** Epoch ms of the last successful POI database download; 0 if none. */
        val poiDbLastUpdateMs: Long = 0L,
        /** Auto-refresh the POI DB once it's older than 30 days. */
        val poiDbAutoUpdate: Boolean = true
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
            elevationAwareTime = prefs[ELEVATION_AWARE_TIME] ?: true,
            showElevationGraph = prefs[SHOW_ELEVATION_GRAPH] ?: true,
            poiGrocery = prefs[POI_GROCERY] ?: false,
            poiWater = prefs[POI_WATER] ?: false,
            poiToilet = prefs[POI_TOILET] ?: false,
            maxStationsToCheck = prefs[MAX_STATIONS_TO_CHECK] ?: 8,
            poiDbLastUpdateMs = prefs[POI_DB_LAST_UPDATE_MS] ?: 0L,
            poiDbAutoUpdate = prefs[POI_DB_AUTO_UPDATE] ?: true
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

    suspend fun setElevationAwareTime(enabled: Boolean) {
        context.dataStore.edit { it[ELEVATION_AWARE_TIME] = enabled }
    }

    suspend fun setShowElevationGraph(show: Boolean) {
        context.dataStore.edit { it[SHOW_ELEVATION_GRAPH] = show }
    }

    suspend fun setPoiGrocery(on: Boolean) {
        context.dataStore.edit { it[POI_GROCERY] = on }
    }

    suspend fun setPoiWater(on: Boolean) {
        context.dataStore.edit { it[POI_WATER] = on }
    }

    suspend fun setPoiToilet(on: Boolean) {
        context.dataStore.edit { it[POI_TOILET] = on }
    }

    suspend fun setMaxStationsToCheck(n: Int) {
        context.dataStore.edit { it[MAX_STATIONS_TO_CHECK] = n }
    }

    suspend fun setPoiDbLastUpdate(epochMs: Long) {
        context.dataStore.edit { it[POI_DB_LAST_UPDATE_MS] = epochMs }
    }

    suspend fun setPoiDbAutoUpdate(enabled: Boolean) {
        context.dataStore.edit { it[POI_DB_AUTO_UPDATE] = enabled }
    }
}
