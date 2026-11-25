# Setup guide

## 1. Requirements

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0+
- Android Studio Ladybug or newer (or command-line tools)
- A physical Android 8+ device or emulator with Google Play services not required

## 2. Build and install

```bash
git clone <your-repository-url>
cd karal-bot-studio
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

If using Android Studio, open the project root, let Gradle sync, then run the `app` configuration.

## 3. Create a Discord bot

1. Open the Discord Developer Portal and create an application.
2. Add a bot. Copy the bot token.
3. Copy the application ID.
4. Enable the intents your commands need. `MESSAGE CONTENT` and `GUILD MEMBERS` are privileged and must be enabled in the portal and in the app's intent settings.
5. Invite the bot to a test server with `bot` and `applications.commands` scopes.

In KARAL: create a project, store the token in project settings, enter the application ID, then start the bot from Hosting. Tokens are encrypted with Android Keystore and are never shown again.

## 4. Verify a command

1. Open Command Studio and create a slash command or use a template.
2. Validate it. Fix any compiler errors.
3. Start the bot and run the command in your test server.
4. Watch the live console for execution logs.

## 5. Backups and export

- **Backup** creates a sanitized JSON snapshot in app-private storage.
- **Export** writes a ZIP with `manifest.json` and `project.json` via the Android document picker.
- **Import/Restore** creates a new project. Imported commands are unapproved until you review them.

Uninstalling the app or clearing app data destroys all local data. Export before updating or uninstalling.

## 6. AI builder (optional)

The AI builder is disabled until you configure an OpenAI-compatible endpoint, model and API key in Settings. The key is stored in Android Keystore. Prompts are redacted before sending.

Generated commands are returned with `approved = false`. You must review, approve and save them explicitly. The model is instructed to emit only the documented KARAL SDK.

## 7. Testing

```bash
./gradlew :core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # requires a device/emulator
```

The pure-Kotlin `core` tests run on any JVM with Kotlin/Java installed; see `core/src/test/kotlin/com/karal/studio/core/CoreTests.kt`.

## 8. Signed release

Set these repository secrets (or environment variables):

- `KARAL_UPLOAD_KEYSTORE_BASE64` — base64-encoded JKS keystore
- `KARAL_UPLOAD_STORE_PASSWORD`
- `KARAL_UPLOAD_KEY_ALIAS`
- `KARAL_UPLOAD_KEY_PASSWORD`

The workflow decodes the keystore to a temporary file and builds `assembleRelease` only when all values are present. `app/build.gradle.kts` uses the same variables and skips release signing when they are absent.

## 9. Local limitations

On the authoring host, Android SDK Platform 35 and Build Tools 35.0.0 were installed manually. The debug APK, core tests, Android unit tests and lint pass locally. Instrumented tests only compile here because APK installation is blocked by device/SELinux permissions; run them on a connected device or in CI. Live Discord tests still require a real bot and test server.
