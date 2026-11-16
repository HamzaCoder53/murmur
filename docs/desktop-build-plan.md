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

- [ ] 1 Foundation — in progress (started 2026-11-16).
- [ ] 2 Features
- [ ] 3 Review
- [ ] 4 Integrate
- [ ] 5 Device tests
- [ ] 6 Release
