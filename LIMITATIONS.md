# Known limitations

These are deliberate, documented boundaries. They are not hidden failures.

## Build and verification

- Android SDK Platform 35 and Build Tools 35.0.0 were assembled manually on the ARM/Termux development host. With that SDK the debug APK, 26 core tests, 3 Android unit tests and lint all pass with zero lint errors.
- The instrumented test APK compiles, but instrumented/UI tests were not executed: APK installation through `pm`/`adb` is blocked by SELinux/shell permissions on this host and no emulator is available. Run `:app:connectedDebugAndroidTest` on a connected device or in CI.
- No test Discord application, token or server was provided. Gateway/REST behaviour has not been verified against live Discord endpoints from this environment.

## Discord hosting

- Android may kill or suspend background services under battery optimisation, Doze or low memory. The foreground service and boot receiver improve reliability but cannot guarantee 24/7 uptime on a phone.
- Gateway reconnect is bounded and exponential; Discord server outages, invalid tokens and disabled privileged intents will surface as errors in the live console.
- Message content intent must be enabled in the Developer Portal for prefix commands that read message text.
- Slash command registration is global (or guild-scoped if a guild id is supplied to the REST adapter); propagation can take time.

## Script sandbox

- QuickJS runs in-process. The SDK surface, script size and execution time are bounded, but a native parser vulnerability could still escape. This is not OS-level isolation.
- The SDK is synchronous. `async`/`await` keywords are normalised away outside string literals; promises and timers are not supported.
- `fetch`, `require`, `import`, filesystem access, process execution and reflection are not exposed.
- External HTTPS calls use an unauthenticated OkHttp client and reject localhost/private addresses. DNS rebinding is not fully prevented.

## TypeScript and BDScript

- TypeScript support is an explicit supported-subset type stripper, not a bundled `tsc`. Unsupported constructs are reported by `TypeScriptStripper.unsupportedConstructs`.
- BDScript is compiled to the shared execution plan and does not execute arbitrary BDScript plugins.

## AI builder

- Requires a user-supplied OpenAI-compatible endpoint and key.
- Generated commands are unapproved and cannot execute until reviewed and approved.
- Model output quality is outside the app's control; validation is enforced by the shared compiler after generation.

## Data

- Uninstalling the app or clearing app data erases local projects, backups and logs.
- Backups are sanitized JSON, not encrypted archives. Store exported ZIPs securely.
- Room version 1 has no released migration path yet. Future schema changes must add explicit `Migration` objects; destructive migration fallback is intentionally disabled.

## UI

- The UI is Hindi by default with English via Android locale configuration. Some technical labels (SDK names, enum values, intents) remain in English by design.
- The visual editor is a functional node list with connections and parameters, not a free-form drag-and-drop canvas with zoom/pan gesture support.
