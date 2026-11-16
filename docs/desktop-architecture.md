# Skein desktop (Windows and macOS): architecture

Status: **foundation stage.** The shared protocol, Noise, mesh, Nostr-crypto, linked-device and
backup cores compile and run on the desktop JVM. A minimal Compose window and a headless smoke
mode exist. There is no real radio yet: nodes talk over an in-process loopback transport. Later
stages add the Bluetooth helpers, UI parity, tray/menu bar, autostart, crash reporting and
packaging.

## Module layout

```
desktop/
  build.gradle.kts            Kotlin JVM + Compose Multiplatform; mirror tasks; include lists
  gradle.lockfile             strict dependency lock (same policy as app/ and wear/)
  src/androidshim/kotlin/     JVM stand-ins for the android.* / androidx.* subset the core uses
  src/androidshim/java/       android.content.SharedPreferences (Java, for Kotlin property syntax)
  src/main/kotlin/
    com/bitchat/desktop/
      Main.kt                 entry point; `--smoke` headless self-check
      platform/               DesktopPlatform interfaces + no-op/fake implementations
      core/                   DesktopIdentity, DesktopMeshNode (MeshCore host), LoopbackTransport
      ui/                     the "Skein" Compose window
    com/bitchat/android/...   desktop variants of a few app classes, same FQN (see below)
  src/test/kotlin/            desktop JUnit tests + test-only RobolectricTestRunner stand-in
  build/sharedSrc/            GENERATED mirror of app sources (never edit)
  build/sharedTestSrc/        GENERATED mirror of reusable app unit tests (never edit)
```

`app/` stays the single source of truth for shared code. `:desktop:syncSharedAppSources` copies a
filtered set of `app/src/main/java` into `desktop/build/sharedSrc`, which is compiled as a source
root, exactly like `:wear`. To share more code, extend `sharedSourceIncludes` in
`desktop/build.gradle.kts`; never copy Kotlin into `desktop/src`.

Toolchain: JDK 21 toolchain, bytecode target 17 (Compose Desktop's minimum), Kotlin 2.4.10
(the repo's version), Compose Multiplatform Gradle plugin **1.12.2** (latest stable on the Gradle
plugin portal; Compose runtime/foundation/ui 1.12.2, material3 1.9.0, which is the current stable
CMP material3 line), skiko 0.150.2. Other new libraries: `org.jetbrains.kotlinx:kotlinx-coroutines-swing`
1.11.0, `com.google.crypto.tink:tink` 1.23.0 (JVM build of the tink-android the phone uses),
`org.json:json` 20260814 (Android ships org.json in the platform).

All four skiko/desktop native runtimes (macOS arm64/x64, Windows x64/arm64) are declared as
`runtimeOnly` instead of `compose.desktop.currentOs`. That keeps `desktop/gradle.lockfile` and
`gradle/verification-metadata.xml` identical on every build host (strict locking would otherwise
fail on the other OS). The cost is about 4x native payload in dev runs; packaging will keep only
the target's runtime.

The Compose plugin also brings its hot-reload tooling (`composeHotReload*` configurations: ktor,
an MCP SDK, etc.). Those are developer-tooling configurations only; none of it is on the app's
`runtimeClasspath`.

## Shared-source mirror

### Included (compiled on desktop)

| Area | Files |
|---|---|
| Wire format | `protocol/**` (BinaryProtocol, compression, padding, payload limits) |
| Noise + crypto | `noise/**` (incl. vendored `southernstorm` Java), `crypto/EncryptionService`, `identity/SecureIdentityStateManager` |
| Models | `model/**` except `FileSharingManager` |
| Mesh core | `MeshCore`, `MessageHandler`, `PacketProcessor`, `PacketRelayManager`, `PeerManager`, `SecurityManager`, `StoreForwardManager`, `FragmentManager`, `FragmentingPacketSender`, `RetryingControlPacketSender`, `PrivateMedia*`, `AuthenticatedPeerStateCoordinator`, `AnnouncementIdentityValidator`, `DirectLinkAnnouncementPolicy`, `BLEPacketPaddingPolicy`, `MeshConnectionTracker`, `PeerFingerprintManager`, `MeshTransport`, `MeshDelegate`, `MeshService`, `MeshPacketUtils`, `TransferProgressManager`, `service/TransportBridgeService` |
| Sync | `sync/**` (gossip, GCS filter, internet sync protocol/bridge/publisher/receiver) |
| Contacts/favorites | `favorites/**`, `services/ContactDirectory`, `ContactIdentityResolver`, `PeerConversationReconciler`, `PrivateMessageArrivalOrder`, `SeenMessageStore`, `VerificationService`, `services/meshgraph/**`, `spam/**` |
| Nostr | `Bech32`, `NostrCrypto` (NIP-44/XChaCha), `NostrEvent`, `NostrFilter`, `NostrRequest`, `NostrProtocol`, `NostrProofOfWork`, `PoWPreferenceManager`, `NostrEmbeddedBitChat`, `NostrIdentity`, `NostrEventDeduplicator`, `NostrLiveSubscriptionPrivacy`, `NostrPendingEventQueue`, `RelayReconnectPolicy`, `VerifiedEventFilter`, `GeohashAliasRegistry`; `geohash/Geohash`, `LocationChannel` |
| Linked devices | `linked/LinkCoordinator`, `LinkCrypto`, `LinkHandshake`, `LinkLogic`, `LinkProtocol`, `LinkQr`, `LinkSession`, `LinkStore`, `LinkedAppBridge`, `PasskeyPairing` |
| Backup / move | `backup/BackupChats`, `BackupImporter`, `SkeinArchive`, `SkeinCrypto`, `TransferProtocol`; `wearlink/IdentityBundle` |
| Misc | `ui/debug/DebugSettingsManager` + `DebugPreferenceManager`, `features/voice/VoiceBurstPacket`, `util/AppConstants`, `ByteArrayExtensions`, `ByteArrayWrapper`, `BinaryEncodingUtils` |

### Excluded, and why

| Excluded | Reason | Plan |
|---|---|---|
| `mesh/Bluetooth*`, `PowerManager`, `RadioPower`, `BluetoothMeshService`, `UnifiedMeshService` | Android BLE stack (GATT, advertiser, scanner), battery/lifecycle receivers | Replaced by `BleTransport` helper processes (stage 2) feeding `DesktopMeshNode.receive` |
| `services/AppStateStore`, `ConversationRepository`, `ConversationStorageCipher`, `MessageHistory` | Conversation persistence is `android.database.sqlite` + Android Keystore; shimming SQLite is a large framework | Desktop variants (same FQN) keep the API the core calls, in memory only. UI-parity stage: either JDBC SQLite (`xerial sqlite-jdbc`) behind a small `SQLiteDatabase`/`Cursor` shim plus a Keychain/DPAPI-keyed cipher, or extract a repository interface in app/ |
| `nostr/NostrRelayManager`, `NostrClient`, `NostrTransport`, `RelayDirectory`, geohash/notes managers, background runtime | Relay client is wired to `mesh.PowerManager`, the Tor `net.OkHttpProvider`, `RelayDirectory` (Application + assets) and `LiveLocationPrivacyGate`; managers need `Application` | Needs small seams in app/ (inject an OkHttp provider and a power-profile flow) before mirroring; then plain OkHttp websockets on desktop |
| `linked/LinkTransport`, `AndroidLinkedAppBridge`, `LinkController` | `NsdManager` discovery and Android process/intent glue | Desktop `LinkTransport` over `LocalDiscovery` (mDNS via JmDNS or the native helpers) reusing `LinkCoordinator`/`LinkSession` |
| `backup/TransferCode`, `TransferNetwork`, `DeviceBackup`, `AndroidBackup`, `SkeinBackupAgent`, `BackupController`, dialogs | NSD, `ConnectivityManager`, Android Backup Service, SAF `Uri`, Compose-Android UI | Desktop file dialogs + `LocalDiscovery` |
| `wearlink/*` except `IdentityBundle` | Wearable Data Layer (Play services) | Not applicable on desktop |
| `features/file/FileUtils`, `features/voice/*` except `VoiceBurstPacket`, `features/media` | ContentResolver/FileProvider/Environment; `android.media` codecs | Desktop variants of `FileUtils` (incoming-file save) and `LiveVoiceManager` (frames validated and dropped, as with live voice off) |
| `model/FileSharingManager` | `Uri` + ContentResolver file picking | Desktop picker in UI stage |
| `ui/**` (Compose-Android), `onboarding`, `hotspot`, `wifi-aware`, `lan` (NsdManager / ConnectivityManager), `net` (Tor/Arti AAR), `util/Apk*`, `GitHubReleaseClient`, `UniversalApkManager`, `DistributionInfoProvider` | Android UI, radios, Android-only native libs, APK self-update | Desktop UI is new code; LAN mesh reuses `lan/LanTransport` once its NSD/Connectivity parts are split |

### Desktop variants (same FQN, in `desktop/src/main/kotlin/com/bitchat/android`)

Same pattern as the watch's `BluetoothPermissionManager`/`MeshServiceHolder`: a small class with
the exact package and the members the mirrored code calls.

- `services.AppStateStore`: in-memory peers, direct peers, public/private messages, read state.
- `services.MessageHistory`: same preference file/key as the phone; nothing to attach or erase yet.
- `features.file.FileUtils`: `saveIncomingFile`, `messageTypeForMime` (same paths/naming rules).
- `features.voice.LiveVoiceManager` / `LiveVoiceScope`: decode-validate and drop live frames.
- `service.MeshServiceHolder`, `wifiaware.WifiAwareController`: debug-toggle targets, as on wear.

### App source seam

One behaviour-preserving edit in app/: the unused `import com.bitchat.android.mesh.BluetoothPacketBroadcaster`
was removed from `sync/GossipSyncManager.kt` (an unresolved unused import is a compile error when
the BLE files are not mirrored).

## android.* shim inventory (`desktop/src/androidshim`)

Only what the mirrored code uses. Anything else fails to compile, which is the signal to either
exclude the caller or extend the shim deliberately.

| Shim | JVM implementation |
|---|---|
| `android.util.Log` | `v/d/i/w/e`, `isLoggable`; routed to `ShimRuntime.logSink` (stderr), default level WARN |
| `android.util.Base64` | Android-exact output (`DEFAULT` wraps at 76 with `\n` and a trailing newline; `NO_WRAP`, `URL_SAFE`, `NO_PADDING`, `CRLF`); lenient decoding |
| `android.content.Context` | `applicationContext`, `getSharedPreferences`, `filesDir`, `cacheDir`, `packageName`, `MODE_PRIVATE`; rooted at `ShimRuntime.dataDir` or an explicit directory (one per simulated device in tests) |
| `android.content.SharedPreferences` (Java) | `FileSharedPreferences`: one typed JSON file per store, atomic temp-file + move writes, owner-only permissions, change listeners |
| `androidx.core.content.edit`, `androidx.core.net.toUri` | same as core-ktx |
| `androidx.security.crypto.EncryptedSharedPreferences` / `MasterKey` | whole store sealed with AES-256-GCM (random nonce, store name as AAD); key from `ShimRuntime.masterKeyProvider` = `DesktopPlatform.keyStore`; a file that fails authentication reads as empty (fail closed) |
| `android.net.Uri` | hierarchical URIs: scheme, authority, host, path, query parameters; Android `Uri.encode` rules; `+` stays literal on decode |
| `android.os.Parcelable`, `kotlinx.parcelize.Parcelize` / `IgnoredOnParcel`, `android.annotation.SuppressLint` | markers / no-ops |

Not shimmed on purpose: `Build` (unused by the mirror), `NsdManager`, `ConnectivityManager`, BLE,
SQLite/Room, Android Keystore, `android.media`, Compose-Android UI.

## Platform interfaces (`com.bitchat.desktop.platform`)

`DesktopPlatform` bundles the host integrations; `install()` points the shims at its data
directory and key store.

| Interface | Now | Target |
|---|---|---|
| `AppPaths` | macOS `~/Library/Application Support/Skein`, Windows `%APPDATA%\Skein`, else XDG; `-Dskein.dataDir` override | secrets under `%LOCALAPPDATA%` on Windows |
| `SecureKeyStore` | `FileKeyStore` (**placeholder**: random key in an owner-only file, so obfuscation only), `InMemoryKeyStore` (tests, smoke) | macOS Keychain, Windows DPAPI, via the helpers or JNA |
| `BleTransport` (central + peripheral) | `NoopBleTransport` | Swift helper (macOS), .NET WinRT helper (Windows) |
| `LocalDiscovery` (mDNS) | no-op | JmDNS or the native helpers; used by the LAN mesh and linked devices |
| `Notifier` | no-op | `java.awt.SystemTray` / native notifications |
| `TrayController` | no-op | menu-bar extra (macOS), notification-area icon (Windows) |
| `AutostartController` | no-op | LaunchAgent / `SMAppService` (macOS), Run key or Startup shortcut (Windows) |
| `CrashReporter` | no-op | opt-in, scrubbed (no peer IDs, keys, paths, message text) |

The mesh host is `core.DesktopMeshNode`: the shared `MeshCore` over any `MeshTransport`, built
like the phone's `LanMeshService`. Transports call `DesktopMeshNode.receive(packet, senderPeerID,
linkAddress)`. `core.LoopbackTransport` is the reference transport: every packet crosses as
`BinaryProtocol` bytes, with one ordered receive thread per endpoint.

## Verification

```sh
./gradlew :desktop:test
./gradlew :desktop:run --args=--smoke      # headless; prints the throwaway peer ID, exits 0
./gradlew :app:compileDebugKotlin :wear:compileDebugKotlin :app:testDebugUnitTest
```

`--smoke` creates an identity in a temporary directory with an in-memory key store, reloads it,
checks the peer ID survived, prints it and deletes the directory. It never touches the real
app-data directory or OS key store.

Desktop tests: desktop-specific tests (identity round trip and peer-ID derivation, encrypted-at-rest
check, Noise XX between two `EncryptionService`s with tamper rejection, two `MeshCore` nodes over
loopback exchanging signed announces, a public message and a Noise-encrypted private message,
`PasskeyPairing`, shim behaviour) plus the app's own pure-JVM tests mirrored by
`:desktop:syncSharedAppTests` (BinaryProtocol and wire-contract golden vectors, Noise external
vectors and session-manager tests, fragmentation, GCS filter, linked-device handshake/QR/coordinator,
`PasskeyPairing`, `SkeinBackup`). App tests that use Robolectric only as a runner run under a
test-only plain-JUnit `org.robolectric.RobolectricTestRunner`; tests needing Robolectric's Android
runtime or Mockito are not mirrored.

Dependency changes follow [reproducible-builds.md](reproducible-builds.md); include the desktop
tasks when regenerating:

```sh
./gradlew :desktop:dependencies :desktop:test :desktop:run --args=--smoke buildEnvironment \
  --write-locks --write-verification-metadata sha256
```

## Next stages

1. **macOS BLE helper.** A small Swift command-line helper (CoreBluetooth `CBCentralManager` +
   `CBPeripheralManager`, Bitchat service/characteristic UUIDs) bundled inside the app and spoken
   to over stdio with newline-delimited JSON:
   `{"op":"start"}`, `{"ev":"linkUp","link":"<opaque>","rssi":-60}`, `{"ev":"frame","link":..,"b64":..}`,
   `{"op":"send","link":..,"b64":..}`, `{"ev":"linkDown",..}`, `{"ev":"state","powered":true}`.
   Links are opaque random IDs (never hardware addresses). The Kotlin side implements
   `BleTransport` + a `MeshTransport` that reuses `FragmentingPacketSender` and the BLE padding
   policy. Needs `NSBluetoothAlwaysUsageDescription`, hardened runtime, and notarization.
2. **Windows BLE helper.** .NET 8 self-contained helper using WinRT
   (`BluetoothLEAdvertisementWatcher`, `GattServiceProvider` for the peripheral role,
   `GattSession`), published for `win-x64` and `win-arm64`, same JSON/stdio protocol. Peripheral
   role requires Windows 10 1803+ and a radio that supports it; report capability via `state`.
3. **Persistence and UI parity.** Real conversation store (see AppStateStore row), Keychain/DPAPI
   `SecureKeyStore`, desktop Nostr relay client after the app/ seams, LAN mesh via
   `LocalDiscovery`, linked devices (pair a desktop with a phone using the 6-digit code), and the
   desktop UI (chats, peers, verification, settings).
4. **Tray / menu bar, autostart, notifications, crash reporting** through the platform interfaces.
5. **Packaging + CI.** Compose Desktop native distributions: DMG (signed + notarized; arm64 and
   x64 builds) and MSI (signed; x64 and arm64), each bundling only its own skiko runtime and BLE
   helper. GitHub Actions matrix on `macos-14` (arm64), `macos-13` (x64), `windows-latest` (x64)
   and `windows-11-arm` (arm64) running `:desktop:test` and the package tasks, with pinned action
   SHAs and the same lock/verification policy as the Android builds.
