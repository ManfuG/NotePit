# NotePit 0.2

Offline-first Android document manager. Native **Kotlin + Jetpack Compose / Material 3** UI and SAF storage; maintained **PDF.js** and **CodeMirror 6** document engines run in restricted, locally bundled WebViews. No Capacitor, account, proprietary sync protocol, AI or Anki integration.

## Install on OnePlus 15

1. Download [`app-release.apk`](https://github.com/ManfuG/NotePit/releases/latest/download/app-release.apk) from the latest GitHub release.
2. Allow installation from the browser/file manager when Android asks, then install. Keep Android System WebView updated.
3. Open **Connect folder**, select a subfolder such as `Documents/University`, and grant access. Repeat for additional folders. Android restricts storage roots, Download root and `Android/data`.
4. Open documents, edit and explicitly **Save** to their original locations. Closing an edited document offers saving, retaining a draft or discarding changes.

The signed release uses a persistent private signing key for subsequent updates. **The earlier GitHub debug prototype used a different key**: Android may require uninstalling that prototype once. Save/export all unsaved work first; uninstalling removes app-private drafts, preferences and SAF grants, not your original shared-storage documents. Reconnect folders afterward. Do not uninstall a release build merely to upgrade it.

## Implemented

### Files

- Persistent read/write SAF access to multiple folders, direct-child progressive loading and bounded directory caching.
- Filename search in the current folder, subfolders or all roots; name/date/size sorting, direction and type/conflict filters.
- Favorites, recent documents, long-press multiselect, contextual menus and bottom actions.
- Create, rename, copy, move and delete files/folders. Recursive transfers verify bytes and membership before removing a source; collisions and partial failures are reported, not silently overwritten.
- Syncthing internal/temporary files are excluded from browsing/search; conflict copies remain visible.

### PDFs

- Continuous scrolling, pinch zoom, width fit, page jumps, text search, selection/copy and embedded outlines when the PDF contains one.
- Highlights, text/drawing annotations and explicit saves to the original PDF, subject to document permissions.
- Reversible, content-detected margin cropping of visible pages; no PDF content is modified by cropping.
- Persistent page/position, zoom, rotation, crop setting and page bookmarks; multiple document tabs and immersive reading.
- Range-backed private snapshots, on-demand page rendering, bounded crop metadata and at most two live document renderers.

### Text/code

TXT, Markdown, LaTeX, Python, R, C/C++, JS/TS, HTML/CSS, JSON, YAML and Bash; syntax highlighting, line numbers, inferred indentation, search/replace, undo/redo and a language-specific toolbar above the keyboard.

Markdown previews render mathematical expressions with bundled KaTeX. LaTeX previews render formulas, **not a compiled LaTeX document**. Scripts, external resources and document-driven navigation are disabled in previews. KaTeX supports a mathematical subset of TeX, not arbitrary packages/macros.

UTF-8, UTF-8 BOM and BOM-marked UTF-16LE/BE retain their encoding. Existing mixed LF/CRLF/CR separators and indentation survive edits/undo. Unsupported encodings/binary data are rejected rather than silently converted.

## Synchronize computer ↔ phone

Syncthing performs synchronization independently. NotePit neither downloads/imports a directory tree nor runs a second sync engine.

1. **Computer:** install [official Syncthing](https://syncthing.net/downloads/) for Linux or Windows. Start it and open its local Web GUI, normally `http://127.0.0.1:8384`. On Linux, a distribution-provided user service can be enabled with `systemctl --user enable --now syncthing.service`; on Windows, run the downloaded executable and keep it running.
2. **Phone:** install the maintained [researchxxl Syncthing-Fork](https://github.com/researchxxl/syncthing-android/releases), package `com.github.catfriend1.syncthingfork`. Complete its onboarding and storage permission setup. Other Play forks and the discontinued official wrapper are not assumed equivalent.
3. **Pair:** initially put both devices on the same network. Add the other device's Device ID on each side and accept pairing. Share the computer notes folder with the phone; accept it in the Fork, using an ordinary phone directory such as `Documents/University`. Use the same **Folder ID** and **Send & Receive** on both devices.
4. **Protect:** configure receiving-side file versioning in Syncthing and keep independent backups. In OnePlus app information/battery controls, allow the Fork's background operation if desired; wording varies with OxygenOS. Review the Fork's run conditions. Background exemptions can increase battery consumption.
5. **NotePit:** open **Folder actions → Sync**, follow the four-step guide and select the exact same phone directory through SAF. Associate each granted directory with its Syncthing Folder ID after checking both apps. This is your confirmation, not automatic path verification.
6. **Check:** create a disposable text file on the computer and verify it reaches the phone. Edit/save it in NotePit and verify the computer receives it. Then test an offline edit and reconnection before relying on the setup.

Both peers, their sync engines and a usable network path must be available for transfer. Offline NotePit editing does not depend on the companion or API being reachable. Concurrent edits can generate `.sync-conflict-…` copies; preserve both until you choose the correct contents. Syncthing is synchronization, not a substitute for backups.

### Optional local status monitoring

The sync screen can read authenticated snapshots from the Fork's REST API. It never assumes installation means the service is running.

- In the Fork, use **Settings → Syncthing Options → API key** to copy the key; confirm the GUI port. Use the same Android profile for both apps.
- In NotePit, expand **Set up / replace API connection**, enter `https://127.0.0.1:8384` (or the configured port) and paste the key.
- The first HTTPS connection displays its SHA-256 certificate fingerprint for explicit trust. Compare it with the intended local service when possible. This is trust-on-first-use, not independently authenticated discovery. Later connections pin the exact certificate; a changed certificate requires reconnection and explicit trust.
- The administrative API key is encrypted with AndroidKeyStore and never exposed to the document WebViews. NotePit only reads status/configuration. HTTP is an explicit loopback-only opt-in with a warning; prefer HTTPS.
- Reconfirm folder links against the authenticated local Device ID. Generic SAF URIs cannot reliably be translated into Syncthing filesystem paths.
- **Refresh folders and status** obtains a timestamped snapshot. Errors/unavailable service are explicit. Local index current and peer connected are **not proof of remote receipt** or global convergence. There is no background polling.

Only the companion's public launcher is used. No undocumented pairing/configuration intents, private API-key access or automatic device/folder provisioning are assumed. [REST API reference](https://docs.syncthing.net/dev/rest).

## Integrity and lifecycle

- File operations, hashing, transfer verification and network requests run off the UI thread. Stale listings/searches are cancellable.
- An immutable private snapshot and SHA-256 revision protect an open file. External metadata changes are reported; saves recheck content and refuse stale overwrites. **Reload original** and **Save a separate copy** let you resolve the situation explicitly.
- Saves retain a persistent original backup and verify the written bytes; failed writes trigger best-effort verified restoration. Failed recovery reports the retained original's private path.
- Text changes are journaled incrementally to private storage; PDF annotation changes produce debounced private drafts without blocking typing. Reading state and open-document identities are durable. Original files change only on explicit save.
- SAF has no transactional compare-and-swap against another writer. An external writer can still race a check; provider/device failures and sudden power loss are not atomic-save guarantees. Avoid editing the same document simultaneously.
- Draft recovery protects completed journal/draft writes, not an uncommitted keystroke killed before it reaches persistent storage. Keep backups and save important work. Renderer memory/file capacity still depends on the device.

## Build and tests

Requires Node 22.12+, **JDK 21** and Android SDK 36. Set `JAVA_HOME` and `ANDROID_HOME`, or keep the SDK location in ignored `android/local.properties`.

```sh
npm ci
npm test
npm run android:build
npm run android:test
# With an Android emulator/device connected:
cd android
./gradlew connectedDebugAndroidTest
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

```sh
npm run android:release
```

Signed APK: `android/app/build/outputs/apk/release/app-release.apk`. The first explicit local release creates private `.signing/keystore.jks` and `config.json`; back up that directory securely. Losing the key prevents compatible future updates. CI uses repository secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`; keys/passwords are never committed.

Builds provision the pinned official PDF.js 6.4.299 legacy distribution with its published SHA-256, local worker/fonts/CMaps/WASM, and offline math assets. A guarded FreeText snapshot patch keeps Android's keyboard/caret intact during drafts; builds reject an unexpected upstream implementation. Third-party notices are generated from locked runtime dependencies. GitHub Actions builds APKs, runs editor tests and invokes the JVM test task; device instrumentation was run locally, not claimed as CI emulator coverage.

### Exercised verification

- **12 Node tests:** encoding/BOM, mixed newline edits/undo/redo, inferred indentation, malformed journals and replay.
- **26 Android instrumentation tests:** real resolver/provider streams via an isolated test provider, transfer integrity/collisions/cancellation/partial failures, save conflicts/restoration/recovery, metadata/permissions/cache behavior, loopback authentication, encrypted credentials, service identity and status parsing.
- **Running API 36 emulator:** real SAF authorization, code edit/save to original bytes, persistent grant and unsaved-text recovery after force-stop/restart, native multi-document UI. PDF single/multiline typing survived idle native drafts with zero focus losses; the complete saved annotation was independently parsed from the original shared-storage PDF.
- **Browser document engines:** sanitized Markdown/math preview; 120-page PDF on-demand rendering, crop navigation and reading-state/bookmark restoration; five-entry PDF outline. A crop-induced page-jump regression was reproduced and fixed.
- **Two isolated real Syncthing 2.1.7 engines:** verified A→B transfer, B→A reconciliation after pausing/reconnecting peers, and preservation of both concurrent edits as conflict copies.

No physical OnePlus 15 benchmark, OxygenOS background-policy test, Windows↔OnePlus transfer or installed Syncthing-Fork pairing is claimed as verified. Those remain device-specific setup checks, not simulated successes.
