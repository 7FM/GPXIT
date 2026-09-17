# GPXIT

An Android app for cyclists who plan one-way bike routes and need to take a train home.

Import your GPX route, and the app discovers train stations along your path. When you're ready to head home, it shows the best options: which station to ride to, how long you'll wait, and when you'll arrive home.

## Transit Provider

Stations and connections come from **Deutsche Bahn (DB)** via the [public-transport-enabler](https://github.com/schildbach/public-transport-enabler) library. DB covers German domestic and many international connections. Where DB's coverage is weak, the app also asks the national provider for that place — **Rejseplanen** (Denmark), **Resrobot** (Sweden) or **Traveline** (Great Britain) — and merges the results. Which providers to ask is decided from coverage data by [KPublicTransport](https://invent.kde.org/libraries/kpublictransport).

Optionally (Settings → Transit data), the community-run **[Transitous](https://transitous.org)** service adds local transport in many more countries, e.g. trams and buses in France.

## POI data

Shops, drinking water, toilets and bike repair come from OpenStreetMap, as one SQLite file per country or region (37 of them, where the transit providers have coverage). The [Build POI Dataset](.github/workflows/build-poi-dataset.yml) workflow rebuilds them monthly and publishes them in the [`poi-data`](https://github.com/7FM/GPXIT/releases/tag/poi-data) release; the app downloads the countries picked in Settings → Map & data and offers the missing ones when a route enters another country. Opening hours are evaluated on the device, including public and school holidays of the region.

## Features

- Import GPX routes (share from Komoot or any cycling app)
- Automatic discovery of train stations along your route
- **"Take me home"** — see upcoming stations with cycling time, wait time, and connections
- Search for nearby stations anywhere on the map
- Detailed connection info with intermediate stops and changes
- Navigate to any station via your preferred map app
- Download map tiles for offline use
- Shops, bakeries, drinking water, toilets and bike repair along your route, with opening hours — offline, for the countries you choose
- Configurable transport types (Deutschlandticket: regional trains, S-Bahn, U-Bahn, tram, bus)
- Optional ICE/IC/EC connections
- Configurable minimum wait buffer and maximum wait time filter

## Building

### Prerequisites

The project uses a Nix flake for a reproducible dev environment:

```sh
nix develop
```

Or if you have direnv: entering the directory auto-activates the shell.

The flake provides: JDK 21, Gradle, Android SDK (platform 37.0, build-tools 37.0.0).

### Build variants

| Variant | Description |
|---------|-------------|
| `fossDebug` / `fossRelease` | FOSS build — uses Android LocationManager, no Google Play Services. **F-Droid compatible.** |
| `fullDebug` / `fullRelease` | Full build — uses Google Play Services for better location accuracy. |

```sh
./gradlew assembleFossDebug       # FOSS debug APK
./gradlew assembleFossRelease     # FOSS release APK
./gradlew assembleFullDebug       # Full debug APK
./gradlew assembleFullRelease     # Full release APK
```

## Privacy

- No tracking, no analytics, no accounts
- All data stays on your device
- Location is used only to show your position on the map
- Internet is used for map tiles (OpenStreetMap), transit queries (Deutsche Bahn; Rejseplanen, Resrobot or Traveline for places they cover; Transitous only if enabled) and downloading the offline POI data (GitHub)

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
