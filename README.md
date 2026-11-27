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


## Session lifecycle and security

- Supported Android versions: API 29 and newer.
- The session timeout includes waiting for root-manager authorization,
  bootstrap startup and Binder connection. The first root invocation may
  require a user confirmation in the installed root manager.
- The Binder peer is authenticated through a reverse callback on all
  supported API levels. On API 34 and newer, broadcast sender UID is also
  checked before accepting the Binder.
- Session ID and policy payload are passed to the root bootstrap through a
  short-lived file in the application's private files directory, not as
  `su` command-line arguments. The bootstrap deletes that file after reading.
- A Binder timeout is terminal for that session. RootCore closes the session,
  requests server shutdown and starts bounded root-process reaping.
- Provider projections accept plain column identifiers (optionally qualified
  by one table prefix) or `*`; SQL expressions and duplicate columns are
  rejected.
- CI runs protocol unit tests and builds a minified consumer application to
  verify the published consumer R8 rules.
