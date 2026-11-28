# Changelog

All notable changes to Android-RootCore are documented in this file.

The format is based on Keep a Changelog.
This project uses semantic versioning.

## [0.9.1] - 2026-11-28

Initial documented pre-1.0 release.

### Included

- Typed Binder-based root execution bridge.
- Client-provided `RootCorePolicy` authorization.
- Protocol version 1 with ten operation types.
- Typed request and response values.
- Provider query and activity launch operations.
- Constrained object creation and reflection operations.
- Binder client authentication with reverse callback.
- Session lifecycle and bounded timeout handling.
- Private short-lived bootstrap configuration.
- Android 10 (API 29) and newer as the target platform.

### Notes

- This is a pre-1.0 release.
- Compatibility across Android versions, devices, and root managers
  requires device-level verification.
- The public API and protocol may evolve before 1.0.0.
