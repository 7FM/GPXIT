# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

GPXIT is an Android app for cyclists who plan one-way bike routes (e.g. via Komoot) and need to take a train home. The app imports a GPX route, discovers train stations along it, and shows live train connections home — helping decide whether to stop now or ride to the next station.

Transit data comes from several backends (`data/transit/`): Deutsche Bahn (`DbProvider`) everywhere it covers, national PTE providers (DSB, Resrobot, Traveline) where DB is weak, and optionally Transitous (own MOTIS client, opt-in setting). `TransitBackendRegistry` picks the backends per place / trip from KPublicTransport's coverage data (`assets/transit/coverage.json`, refreshed weekly by `.github/workflows/update-transit-coverage.yml`, or manually with `scripts/import_kpt_coverage.py`); results are merged and de-duplicated. `CoverageCache` remembers per ~500 m cell which backends had stations during route discovery and skips one that had none where another had some.

Package: `dev.gpxit.app` | Min SDK 26 | Target SDK 35 | Kotlin + Jetpack Compose

## Development Environment

Nix flake provides a reproducible dev shell. Enter via `nix develop` or automatically via direnv (`.envrc`).

Toolchain: JDK 21, Gradle 9.6.1, Android SDK platform 37.0, build-tools 37.0.0.

`compileSdk`/`buildToolsVersion` (`app/build.gradle.kts`), `androidSdk` (`flake.nix`),
and the SDK install steps in
`.github/workflows/{build,release,update-transit-coverage}.yml` must be kept in
sync. From API 37 on, Google only publishes minor-versioned platforms, so the SDK
package is `platforms;android-37.0` — there is no bare `android-37`.

## Build Commands

```sh
./gradlew assembleFossDebug       # FOSS debug (no Google Play Services)
./gradlew assembleFossRelease     # FOSS release (F-Droid compatible)
./gradlew assembleFullDebug       # Full debug (Google Play Services location)
./gradlew assembleFullRelease     # Full release
./gradlew compileDebugKotlin      # Compile only (faster feedback)
./gradlew lint                    # Android lint
```

## Build Flavors

- **foss**: Uses Android's built-in `LocationManager`. No proprietary dependencies. F-Droid compatible.
- **full**: Uses Google Play Services `FusedLocationProviderClient` for better location accuracy.

Flavor-specific source: `app/src/foss/` and `app/src/full/` (only `LocationService.kt` differs).

## Architecture

Single-module app, MVVM with ViewModels and Compose. No DI framework — manual construction.

### Key libraries
- **public-transport-enabler** (JitPack) — queries Deutsche Bahn (`DbProvider`) and the national providers for nearby stations and connections, wrapped in `PteBackend`.
- **android-gpx-parser** (JitPack) — parses GPX 1.1 files.
- **osm-opening-hours** (`de.westnordost`, the StreetComplete parser) — parses OSM `opening_hours`; evaluation is our own (`data/openinghours/`).
- **osmdroid** — OpenStreetMap tiles, wrapped in `AndroidView` for Compose. Custom `OsmTileSource` (in `data/OsmTileSource.kt`) used for both display and offline download.
- **DataStore Preferences** — persists user settings.

### Data flow
1. **Import**: GPX file → `GpxParser.parse()` → `RouteInfo` with cumulative Haversine distances
2. **Station precomputation**: Sample route every 2km → `TransitRepository.discoverStationsAlongRoute()` → stations stored in `RouteInfo.stations` and persisted to disk
3. **Decision time**: Filter stations ahead → estimate cycling time → `queryTrips()` for each → show in route order with recommended option
4. **Search nearby**: Query stations in current map viewport, shown as teal markers
5. **Offline tiles**: `MapTileDownloader` downloads route corridor tiles (zoom 10–16) directly to osmdroid's `SqlTileWriter` cache
6. **Offline POIs**: one SQLite dataset per country (Geofabrik extract, listed in `scripts/poi_datasets.json`), rebuilt monthly by `.github/workflows/build-poi-dataset.yml` and published with `pois-index.json` in the `poi-data` release → `PoiDatasetManager` downloads the countries picked in Settings (default: locale / home station; route import offers the countries a route enters) → `PoiDatabase` queries all installed files and merges border duplicates by OSM id

### Package layout
```
data/gpx/           — GpxParser + haversine/geo utilities
data/transit/        — TransitRepository (facade), TransitBackendRegistry, PteBackend, TransitousBackend, station/trip matching
data/prefs/          — PrefsRepository (DataStore)
data/poi/            — PoiDatabase (per-country SQLite datasets from scripts/build_poi_db.py), PoiDatasetManager (selection, index, downloads), PoiOpeningHours
data/openinghours/   — OpeningHoursEvaluator (rule semantics follow opening_hours.js), HolidayCalendar, SunTimes
data/                — RouteStorage, MapTileDownloader, OsmTileSource
domain/              — RoutePoint, RouteInfo, StationCandidate, ConnectionOption
ui/import_route/     — GPX file import screen
ui/map/              — osmdroid map with route polyline + station markers
ui/decision/         — "Take me home" screen: ranked stations with connections
ui/settings/         — Home station, cycling speed, transport types, wait times
ui/components/       — StationCard, ConnectionRow (reusable)
```
