package dev.gpxit.app.data

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

/**
 * Standard OSM tile source (light mode).
 */
val OsmTileSource = object : OnlineTileSourceBase(
    "Mapnik",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/"
    ),
    "© OpenStreetMap contributors",
    TileSourcePolicy(2, 0)
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y$mImageFilenameEnding"
    }
}

/**
 * CartoDB Dark Matter tile source (dark mode).
 * Free, no API key required. Attribution: © OpenStreetMap contributors, © CARTO
 */
val DarkOsmTileSource = object : OnlineTileSourceBase(
    "CartoDB_Dark",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/",
        "https://b.basemaps.cartocdn.com/",
        "https://c.basemaps.cartocdn.com/",
        "https://d.basemaps.cartocdn.com/"
    ),
    "© OpenStreetMap contributors, © CARTO",
    TileSourcePolicy(2, 0)
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}dark_all/$zoom/$x/$y$mImageFilenameEnding"
    }
}

fun getActiveTileSource(useDark: Boolean) = if (useDark) DarkOsmTileSource else OsmTileSource
