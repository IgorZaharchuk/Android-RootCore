# Android RootCore

Standalone Android library for controlled root operations.

## Project structure

- `root-core` — Android library containing the RootCore API and implementation.
- `root-core-bootstrap` — bootstrap application module. Its DEX files are packaged into `root-core` as `assets/main.jar` during the build.

The AIDL interface is included directly in `root-core`. No separate shared module is required.

## Build

The project uses:

- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.9.2
- Android SDK 35
- Minimum Android SDK 29

Build the release AAR:

    ./gradlew :root-core:assembleRelease

The resulting artifact is:

    root-core/build/outputs/aar/root-core-release.aar

## Integration

Copy `root-core-release.aar` into the consuming Android application's `libs` directory and add:

    dependencies {
        implementation(files("libs/root-core-release.aar"))
    }

The AAR contains the RootCore classes, generated AIDL interface and bootstrap DEX asset.

## Continuous integration

GitHub Actions builds the release AAR on pushes to `main` and on manual workflow dispatch. The AAR is uploaded as a workflow artifact.
