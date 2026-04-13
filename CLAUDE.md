# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

GPXIT is an Android app for cyclists who plan one-way bike routes (e.g. via Komoot) and need to take a train home. The app imports a GPX route, discovers train stations along it, and shows live train connections home — helping decide whether to stop now or ride to the next station.

Default transit provider: Deutsche Bahn (`DbProvider`) — covers German and many international connections. The `public-transport-enabler` library supports 50+ European providers; adding provider selection is a future task.

Package: `dev.gpxit.app` | Min SDK 26 | Target SDK 35 | Kotlin + Jetpack Compose

## Development Environment

Nix flake provides a reproducible dev shell. Enter via `nix develop` or automatically via direnv (`.envrc`).

Toolchain: JDK 21, Gradle 8.12.1, Android SDK platform 35, build-tools 35.0.0.

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
- **public-transport-enabler** (JitPack) — queries Deutsche Bahn for nearby stations and connections. Uses `DbProvider`.
- **android-gpx-parser** (JitPack) — parses GPX 1.1 files.
- **osmdroid** — OpenStreetMap tiles, wrapped in `AndroidView` for Compose. Custom `OsmTileSource` (in `data/OsmTileSource.kt`) used for both display and offline download.
- **DataStore Preferences** — persists user settings.

### Data flow
1. **Import**: GPX file → `GpxParser.parse()` → `RouteInfo` with cumulative Haversine distances
2. **Station precomputation**: Sample route every 2km → `TransitRepository.discoverStationsAlongRoute()` → stations stored in `RouteInfo.stations` and persisted to disk
3. **Decision time**: Filter stations ahead → estimate cycling time → `queryTrips()` for each → show in route order with recommended option
4. **Search nearby**: Query stations in current map viewport, shown as teal markers
5. **Offline tiles**: `MapTileDownloader` downloads route corridor tiles (zoom 10–16) directly to osmdroid's `SqlTileWriter` cache

### Package layout
```
data/gpx/           — GpxParser + haversine/geo utilities
data/transit/        — TransitRepository (public-transport-enabler wrapper)
data/prefs/          — PrefsRepository (DataStore)
data/                — RouteStorage, MapTileDownloader, OsmTileSource
domain/              — RoutePoint, RouteInfo, StationCandidate, ConnectionOption
ui/import_route/     — GPX file import screen
ui/map/              — osmdroid map with route polyline + station markers
ui/decision/         — "Take me home" screen: ranked stations with connections
ui/settings/         — Home station, cycling speed, transport types, wait times
ui/components/       — StationCard, ConnectionRow (reusable)
```
