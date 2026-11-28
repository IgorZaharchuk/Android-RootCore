# Android-RootCore

**Android-RootCore** is a typed root execution bridge for Android applications.

It provides a controlled Binder-based communication channel between an Android
client application and a separate process running with root privileges.

RootCore is designed as a passive executor: the client decides when to perform
an operation and supplies the policy that authorizes the requested capabilities.

## Project status

- Version: `0.9.1`
- Minimum target platform: Android 10 (API 29)
- Language of the public API: Java
- IPC: Android Binder
- Native code: not required

This is a pre-1.0 release. API and protocol details may evolve.

## Architecture

RootCore separates the application that requests an operation from the
privileged process that executes it.

The client:

1. Creates a `RootCorePolicy` describing permitted operations.
2. Opens a `RootCoreSession`.
3. Sends typed `RootCoreRequest` objects.
4. Receives typed `RootCoreResponse` objects.
5. Closes the session when it is no longer needed.

RootCore does not independently decide what an application should do.
It executes explicitly requested operations that pass the supplied policy.

**Important security model:** the policy is an authorization mechanism for
RootCore requests. It is not a sandbox for the client application. Code that
the client deliberately executes with root privileges runs with UID 0.

## Protocol

Protocol version 1 defines the following operations:

| Operation | Purpose |
|---|---|
| `PING` | Check communication |
| `QUERY_PROVIDER` | Read data from an authorized content provider |
| `LAUNCH_ACTIVITY` | Launch an authorized activity |
| `CREATE_OBJECT` | Create an object |
| `INVOKE_METHOD` | Invoke an allowed method |
| `RELEASE_OBJECT` | Release an object reference |
| `GET_FIELD` | Read an instance field |
| `SET_FIELD` | Write an instance field |
| `GET_STATIC_FIELD` | Read a static field |
| `SET_STATIC_FIELD` | Write a static field |

Requests and responses use typed values rather than an unrestricted shell
interface.

`RootCoreValue` supports null and void values, scalar values, strings,
byte arrays, collections, structured values, object references, and
reserved extension values.

The `EXTENSION` value type is reserved for protocol evolution. It is not
a general-purpose extension mechanism in this release.

## Policy

A client supplies a `RootCorePolicy` when opening a session.

The policy can authorize individual operation types, provider authorities,
activities, and supported value extensions.

The default policy is deny-all:

    RootCorePolicy policy = RootCorePolicy.denyAll();

A policy can be constructed with the builder:

    RootCorePolicy policy = new RootCorePolicy.Builder()
        .allowQueryProvider()
        .allowProviderAuthority("com.example.provider")
        .build();

Only grant capabilities that the client actually needs.

Different parts of an application can use different policies and sessions.
A single broad permission set is not required.

## Opening a session

`RootCore.open()` must not be called from the Android main thread.

The timeout must be at least 1000 milliseconds.

    RootCorePolicy policy = new RootCorePolicy.Builder()
        .allowOperation("PING")
        .build();

    RootCoreSession session =
        RootCore.open(context, policy, 5000L);

    try {
        // Send RootCoreRequest objects through session.execute().
    } finally {
        session.close();
    }

The example illustrates session creation and lifecycle. Construct requests
according to the public request API available in the library.

## Session API

The public session API includes:

- `execute(RootCoreRequest)` — execute a typed request.
- `close()` — close the session.
- `awaitTermination(long)` — wait for process termination.
- `isRootProcessAlive()` — check whether the root process is alive.
- `isConnected()` — check session connectivity.
- `getSessionId()` — obtain the session identifier.

A session should be closed when it is no longer needed.

## Security characteristics

RootCore includes the following safeguards:

- Deny-all policy by default.
- Per-operation authorization.
- Provider authority restrictions.
- Activity restrictions.
- Typed request and response values.
- Constrained object reflection.
- Binder-based client authentication with a reverse callback.
- Additional broadcast sender UID verification on Android 14 (API 34) and newer.
- Bounded timeouts and session lifecycle management.
- Private short-lived configuration transfer rather than passing session
  configuration through process command-line arguments.
- Provider projection validation.

Object reflection is constrained to classes available through the client
ClassLoader and to the relevant declaring-class origin.

These mechanisms restrict RootCore's own request interface. They do not make
arbitrary code running as UID 0 safe.

## Deliberate limitations

RootCore does not expose a general-purpose command execution interface.

The library does not provide built-in arbitrary:

- Shell commands or scripts.
- `exec` operations.
- System settings modification.
- System property modification.
- Service management.
- Broadcast sending.
- Shizuku or ADB fallback execution.

Applications should use explicit typed operations and narrowly scoped policies.

## Root environment

RootCore is intended to work with Android root environments that can start
a process with root privileges.

Compatibility with individual root managers and Android releases depends on
their behavior and must be verified on actual devices. The API 29+ target
does not imply that every Android version, device, or root manager has been
exhaustively tested.

## License

Android-RootCore is distributed under the Apache License, Version 2.0.
See [LICENSE](LICENSE).
