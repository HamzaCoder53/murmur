# Skein desktop build plan (Windows + macOS)

Living plan for building the desktop apps. Whoever continues the work (Claude or Gemini) reads
this first and updates the **Status** section after every step.

## Goal

A Skein desktop app for macOS (Apple Silicon + Intel) and Windows (x64 + ARM64) with the phone's
features, published on GitHub Releases.

- Same protocol code as the phone (mirrored from `app/`, never copied), compatible with Bitchat.
- Link with phones and watches (linked devices over Wi-Fi, QR or 6-digit code).
- macOS: Settings toggle "Show in Dock"; off = menu-bar only (activation policy accessory).
- Windows: tray icon; closing the window keeps it running; a small watcher restarts it after a crash.
- Both: ask before enabling start-at-login (macOS SMAppService / Windows StartupTask); when started
  that way, start hidden in the menu bar / tray with no window.
- Crash reports to Sentry (free plan), opt-in on first launch, scrubbed of message text, nicknames,
  peer IDs, keys and locations. DSN from the Gradle property `skein.sentryDsn` / env
  `SKEIN_SENTRY_DSN`; crash reporting off when unset.
- Unsigned builds for now (no Apple Developer / code-signing certificate).

## Stages

1. **Foundation** — `desktop/` Compose Desktop module, Android shims, shared core compiling on the
   JVM, platform interfaces, smoke test. Branch `desktop/foundation`. See
   `docs/desktop-architecture.md` (written by this stage).
2. **Features** (parallel, one branch each, all based on `desktop/foundation`):
   - `desktop/ble-mac` — Swift BLE helper process (central + peripheral) + JVM bridge.
   - `desktop/ble-windows` — .NET WinRT BLE helper (win-x64, win-arm64) + JVM bridge.
   - `desktop/ui` — screens at phone parity (chat, DMs, people, location chats, settings, backups,
     linked devices, history, broadcast power, Arabic).
   - `desktop/platform` — tray/menu bar + Dock toggle, autostart prompt, hidden start, crash watchdog.
   - `desktop/link` — linked devices, LAN mesh, Nostr internet DMs/sync, backups on desktop.
   - `desktop/sentry` — opt-in Sentry crash reporting with scrubbing.
   - `desktop/ci` — GitHub Actions: macOS universal DMG, Windows x64 + ARM64 MSI, attached to releases.
3. **Review** — every branch reviewed independently by Gemini 4 Argon and Claude Fable 5.2; only
   verified findings are fixed.
4. **Integrate** — merge branches into `desktop/main`, all tests green.
5. **Test on devices** — macOS here; Windows via agy on the Windows PC; phone/watch linking.
6. **Release** — GitHub Release with desktop installers (separate from Play).

## Rules for any agent continuing this

- Work on the `desktop/*` branches. **Never merge into `murmur` and never publish a release** until
  every branch is reviewed and `./gradlew :desktop:test :app:testDebugUnitTest :wear:testDebugUnitTest`
  passes. Merging to `murmur` and releasing need the developer's go-ahead.
- Read `AGENTS.md` and follow its privacy rules (synthetic test data; no device logs in the repo).
- Shared code comes from `app/src/main/java` through the desktop mirror task; do not copy it.
- Build with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. New
  dependencies need `--write-locks --write-verification-metadata sha256`.
- Commit after each finished piece with a clear message; update **Status** below.

## Status

- [x] 1 Foundation — done (`desktop/foundation`).
- [~] 2 Features — integrated on `desktop/main` (checked 2026-11-16). `desktop/c-internet` was
  never created; the Nostr location channels came in a direct `desktop/main` commit.
  - Real and wired into the running app:
    - Shared mesh core on the JVM; macOS BLE helper (builds, packaged in the DMG); BLE state
      banner (off / not allowed / unavailable / helper restarting).
    - Encrypted history (`storage.EncryptedHistoryStore`, AES-GCM keyed from Keychain/DPAPI),
      "Save message history" switch; favorites and verified fingerprints
      (`storage.DesktopContactsStore`); settings persisted (`PrefsSettingsPort`).
    - Private messages wait for a Noise session (marked failed after 30 s); delivery and read
      receipts update status; nickname changes are announced at once.
    - Nostr location channels (kind 20000) over OkHttp websockets (`nostr/`).
    - Linked devices: JmDNS discovery, QR shown with ZXing, 6-digit code, pasted QR text
      (`DesktopLinkController` over the shared `LinkSocketEngine`); encrypted `.skein` backup
      export/import screens.
    - Dock toggle (applies at next launch, `apple.awt.UIElement`); first-launch prompts for start
      at login and crash reports; start at login runs `--watchdog --autostart` from the packaged
      launcher (macOS LaunchAgent / Windows Run key); hidden start; crash watchdog; tray.
    - Notifications for new incoming private messages while the window is hidden or unfocused
      (sender name only); tray unread badge.
    - Broadcast power is sent to the BLE helpers (`power` op): an RSSI floor for new connections
      and a cap on outgoing links, as in the phone's PowerManager. CoreBluetooth and WinRT give no
      TX-power control, so it does not change transmit power.
    - Opt-in scrubbed Sentry; DSN baked as a resource only when `skein.sentryDsn` /
      `SKEIN_SENTRY_DSN` is set.
  - Still missing or unverified:
    - Nostr private messages (NIP-17), internet delivery/read receipts and internet sync.
    - LAN (Wi-Fi) mesh with phones and "move to a new device" on desktop.
    - Photos and voice notes in the desktop UI.
    - Windows BLE helper and the `power` op on Windows: written, never built or run.
    - CI workflow (incl. draft-release upload on `desktop-v*` tags): never run.
    - Nothing tested on real devices yet: BLE with a phone, linking a phone, Windows, ARM64.
    - Local DMG builds need `-Pcompose.desktop.packaging.checkJdkVendor=false` with the
      Homebrew JDK.
- [~] 3 Review — each branch reviewed once; integration merges not yet reviewed.
- [x] 4 Integrate — `desktop/main`: `:desktop:test`, `:desktop:run --args=--smoke`,
  `:app:testDebugUnitTest`, `:wear:testDebugUnitTest`, `:desktop:buildMacBleHelper` and
  `:desktop:packageDmg -PskeinTarget=macos_arm64` pass on macOS; the packaged app passes `--smoke`.
- [ ] 5 Device tests
- [ ] 6 Release
