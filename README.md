# KARAL BOT STUDIO

An Android-first, local-first workbench for building, testing and hosting Discord bots from a phone. Built with Kotlin, Jetpack Compose, Material 3, Room, Android Keystore, WorkManager and a foreground service.

## Status

This repository contains a complete application source tree and a pure-Kotlin `core` engine that is tested locally without an Android SDK.

**Verified locally**
- `core` JVM tests: 26 tests passing.
- Android debug APK assembled successfully with SDK Platform 35 / Build Tools 35.0.0.
- Android unit tests: 3 tests passing.
- Android lint: 0 errors.
- Instrumented test APK assembles successfully.
- JSON, security redaction, compiler gating, permission checks and AI approval contracts.

**Not verified locally**
- Instrumented/UI tests were not executed: this host cannot install APKs through `pm`/`adb` (SELinux and shell permissions) and has no emulator. The test APK builds, and CI or a connected device can run it.
- Real Discord Gateway/REST behaviour requires a real bot token and a test server. No live Discord test was run in this environment.

See [SETUP.md](SETUP.md) for build instructions and [LIMITATIONS.md](LIMITATIONS.md) for the honest boundary of the implementation.

## Architecture

```
app/                    Android application (Compose UI, Room, services, Discord client)
core/                   Pure Kotlin shared engine (no Android dependencies)
  model/                CommandDefinition, ExecutionPlan, workflow graph
  compiler/             single compiler for visual, script, AI and template sources
  engine/               shared executor, permissions, rate limits, variables, metrics
  templates/            50+ built-in commands across 10 categories
  security/             secret detection/redaction used by exports, logs and AI prompts
```

Every editor compiles to the same `CommandDefinition` and then to one `ExecutionPlan`. Script-backed plans use QuickJS through a restricted SDK; visual plans use the shared interpreter. AI-generated and imported commands are stored with `approved = false` and cannot compile or execute until the user explicitly approves them.

## Features

- Unlimited local projects with create, open, edit, duplicate, rename, archive, delete, export and import.
- Visual node builder, JavaScript/TypeScript/BDScript editor, and optional AI builder.
- 50+ built-in templates across moderation, utility, economy, leveling, tickets, welcome, reaction roles, logging, music helpers and giveaways.
- Discord Gateway v10 client with heartbeat, identify/resume and bounded reconnect.
- Discord REST v10 adapter with local rate limiting and 429 retry.
- AES-GCM Keystore encryption for bot tokens and the optional AI provider key.
- Foreground hosting service, boot recovery via WorkManager, live console and runtime cards.
- Hindi default UI with English locale support, dark/light/system themes.
- Local backups with SHA-256 verification and sanitized exports.

## Security model

- Bot tokens are stored only in Android Keystore-backed encrypted blobs. Room stores only an alias.
- Exports and backups are sanitized; credential-looking keys/values are removed or redacted.
- Logs and AI prompts pass through `SecretPolicy.redact`.
- Scripts receive a synchronous, documented SDK only: no filesystem, process, reflection, token or raw HTTP client with credentials.
- External HTTPS calls from scripts use a separate unauthenticated client and reject local/private addresses.

This is defence in depth, not a hardened multi-tenant sandbox. See [LIMITATIONS.md](LIMITATIONS.md).

## Build

```bash
./gradlew :core:test
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

CI: `.github/workflows/android-apk.yml` builds the debug APK, runs unit tests and lint, and conditionally builds a signed release when the four upload secrets are configured.

## Data safety

KARAL stores everything locally. Uninstalling the app or clearing app data removes projects, bots, logs and backups. Export regularly. The app never uploads project data unless you configure an AI provider and generate a command.

## Licence

Source is provided as-is for local development and evaluation.
