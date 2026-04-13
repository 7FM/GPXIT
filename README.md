# GPXIT

An Android app for cyclists who plan one-way bike routes and need to take a train home.

Import your GPX route, and the app discovers train stations along your path. When you're ready to head home, it shows the best options: which station to ride to, how long you'll wait, and when you'll arrive home.

## Transit Provider

The app currently uses **Deutsche Bahn (DB)** as the default transit provider via the [public-transport-enabler](https://github.com/schildbach/public-transport-enabler) library. DB covers German domestic and many international connections. The library supports 50+ European transit providers — adding a provider selection is planned for future versions.

## Features

- Import GPX routes (share from Komoot or any cycling app)
- Automatic discovery of train stations along your route
- **"Take me home"** — see upcoming stations with cycling time, wait time, and connections
- Search for nearby stations anywhere on the map
- Detailed connection info with intermediate stops and changes
- Navigate to any station via your preferred map app
- Download map tiles for offline use
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

The flake provides: JDK 21, Gradle 8.12.1, Android SDK (platform 35, build-tools 35.0.0).

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
- Internet is used for map tiles (OpenStreetMap) and transit queries (Deutsche Bahn)

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
