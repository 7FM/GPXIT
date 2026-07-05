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
          version = "9.5.1";
          src = pkgs.fetchurl {
            url = "https://services.gradle.org/distributions/gradle-${version}-bin.zip";
            sha256 = "sha256-uvwUG2Ga1jUP2XX8kDFW3VwVGZjMiwWOjBBEq197Ax8=";
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

        android = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "35.0.0" ];
          platformVersions = [ "35" ];
          abiVersions = [ "armeabi-v7a" "arm64-v8a" ];
        };

        # Separate, heavier composition for the screenshot pipeline:
        # adds the (NixOS-patched) emulator plus an x86_64 system image
        # (~1.5 GB) that the default dev shell shouldn't have to pay for.
        # Used by scripts/generate-screenshots.sh via `nix develop
        # .#screenshots`.
        androidEmu = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "35.0.0" ];
          platformVersions = [ "35" ];
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "default" ];
          abiVersions = [ "x86_64" ];
        };
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
