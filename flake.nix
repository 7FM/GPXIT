{
  description = "GPXIT - Bike route train connection finder";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import "${nixpkgs}" {
          inherit system;
          config.allowUnfree = true;
          config.android_sdk.accept_license = true;
        };

        gradle = pkgs.stdenv.mkDerivation rec {
          pname = "gradle";
          # Keep in sync with gradle/wrapper/gradle-wrapper.properties
          version = "9.6.1";
          src = pkgs.fetchurl {
            url = "https://services.gradle.org/distributions/gradle-${version}-bin.zip";
            sha256 = "sha256-nA9/ruswbLFOQnmj4ITKa1lolAiaBjjmigfJRaMsnhQ=";
          };
          nativeBuildInputs = [ pkgs.unzip pkgs.makeWrapper ];
          unpackPhase = "unzip $src";
          installPhase = ''
            mkdir -p $out
            cp -r gradle-${version}/* $out/
            wrapProgram $out/bin/gradle \
              --set JAVA_HOME "${pkgs.jdk21}/lib/openjdk"
          '';
        };

        # Keep in sync with compileSdk in app/build.gradle.kts. From API 37 on,
        # Google only publishes minor-versioned platforms (android-37.0), so
        # there is no bare "37" package to ask for.
        androidSdk = {
          buildToolsVersions = [ "37.0.0" ];
          platformVersions = [ "37.0" ];
        };

        android = pkgs.androidenv.composeAndroidPackages (
          androidSdk
          // {
            abiVersions = [ "armeabi-v7a" "arm64-v8a" ];
          }
        );

        # Separate, heavier composition for the screenshot pipeline:
        # adds the (NixOS-patched) emulator plus an x86_64 system image
        # (~1.5 GB) that the default dev shell shouldn't have to pay for.
        # Used by scripts/generate-screenshots.sh via `nix develop
        # .#screenshots`.
        # API 37 ships no "default" system image, only google_apis variants.
        androidEmu = pkgs.androidenv.composeAndroidPackages (
          androidSdk
          // {
            includeEmulator = true;
            includeSystemImages = true;
            systemImageTypes = [ "google_apis" ];
            abiVersions = [ "x86_64" ];
          }
        );
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.jdk21
            gradle
            pkgs.android-tools
            android.androidsdk
          ];
          ANDROID_SDK_ROOT = "${android.androidsdk}/libexec/android-sdk";
          ANDROID_HOME = "${android.androidsdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk21}/lib/openjdk";
        };

        devShells.screenshots = pkgs.mkShell {
          packages = [
            pkgs.jdk21
            gradle
            pkgs.android-tools # host adb
            androidEmu.androidsdk # emulator + system image + avdmanager
            pkgs.curl
          ];
          ANDROID_SDK_ROOT = "${androidEmu.androidsdk}/libexec/android-sdk";
          ANDROID_HOME = "${androidEmu.androidsdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk21}/lib/openjdk";
        };
      }
    );
}
