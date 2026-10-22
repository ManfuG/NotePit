# NotePit — agent memory

## Scope and architecture

NotePit 0.2: Kotlin + Jetpack Compose Material3 Android shell, native SAF operations, offline PDF/code engines and optional Syncthing-Fork setup/status workflow. English UI/code/docs. No AI, Anki, proprietary sync protocol, account or mandatory cloud. Markdown/math and LaTeX formula previews are now in scope; full LaTeX compilation is not.

Capacitor, Java plugin and the JS file-manager shell were removed. Do not reintroduce parallel backends or compatibility shims. Existing CodeMirror6 and official PDF.js were retained in origin-restricted local WebViews: the app UI/storage are native; document engines are not claimed to be Kotlin renderers.

## Implementation map

- `MainActivity.kt`, `Theme.kt`, `FilesScreen.kt`, `FilesViewModel.kt`: native UI, native tabs, Material3 cobalt/raspberry light/dark themes, bulk actions, one-handed controls and dialogs. Theme controls belong to the native shell.
- `DocumentModels.kt`, `DocumentsRepository.kt`: persistent multi-root SAF, migrated old `folders/roots` preferences, library metadata, cancellable/progressive lists, filename search, bounded cache, verified recursive transfers and hash-guarded saves. Persistent recovery originals are exposed through `retainedRecoveryFiles`.
- `DocumentSessions.kt`: private immutable snapshots, incremental text journals, PDF drafts, reading metadata, open tabs and original revisions. Disk work is serialized off main; UI state survives activity changes and completed journals survive process death.
- `DocumentWebView.kt`: WebViewAssetLoader assets, origin/main-frame restricted binary WebMessage bridge, private snapshot range responses; no Base64 expansion. Reject external document requests/navigation. At most two live renderers are retained.
- `src/main.ts`, `src/document.ts`: document-only engine host and typed contract; serialize transfers until native acknowledgement. Only call `markSaved()` after disk success; do not let newer draft serialization replace the snapshot awaiting acknowledgement.
- `src/editor.ts`, `editor-document.ts`, `preview.ts`: CodeMirror languages, persistent mixed-newline state/undo, UTF8 and BOM-marked UTF16, incremental recovery journals, sanitized Markdown/KaTeX and formula previews. Static imports for known modules, top-level type imports, Promise.withResolvers; no concrete ReturnType<typeof fn> contracts.
- `src/pdf.ts`: official PDF.js iframe, range-backed sources, continuous reading, annotations, persistent viewport/bookmarks and lazy content-bound crop. Crop must not change saved annotation coordinates. Capture a fresh PDF-coordinate viewport anchor immediately around each layout mutation: stale throttled reading state caused page117→119 jumps and was fixed. Preserve this invariant.
- PDF annotation focus must lock fit presets to the identical numeric scale before Android IME resize: upstream preset resize commits active FreeText and drops subsequent keystrokes. Draft `commit(true)` retains contenteditable and refreshes dimensions without repositioning the live editor. Editing toggles and `fixAndSetPosition()` asynchronously reset Android's multiline caret; restoring a DOM Range is insufficient. Normal explicit commits still constrain position. Never make the iframe inert or end editing on save acknowledgement.
- `scripts/setup-pdf.mjs`: pinned official PDF.js6.4.299 legacy archive and published SHA256; ignored generated assets include fonts/CMaps/WASM. A uniquely matched FreeText commit patch adds a keep-editing flag; fail builds on upstream mismatch. `scripts/notices.mjs` maintains bundled notices.
- `SyncthingClient.kt`, `SyncScreen.kt`: user-assisted companion setup; native loopback REST snapshots, explicit first-use TLS fingerprint trust, per-connection certificate pin, AndroidKeyStore AES-GCM credentials and Device-ID-bound folder associations. Credentials never enter WebViews/logs.

## Invariants

- Work on granted original files, not imported folder copies; no broad storage permission. Unknown MIME types use application/octet-stream so Android does not append .txt.
- All SAF/network/hash operations off main. Never delete a transfer source until full byte/membership verification succeeds. Report partial batches and retained destinations.
- Never silently overwrite external edits. Preserve originals on failure and unsaved edits during a save. SAF cannot provide atomic external-writer CAS; do not claim transactional crash safety.
- UTF8/BOM and mixed LF/CRLF/CR survive edit/save/undo. BOM-marked UTF16LE/BE must retain byte order. Reject unknown encodings rather than guess.
- User content/names never enter unsafe innerHTML. Preview rendering must stay sanitized and offline. PDF scripting/external navigation stay disabled.
- Android API26 minimum; avoid unsupported APIs such as InputStream.transferTo. Runtime WebView must support Promise.withResolvers and binary web messages; feature checks must fail clearly.
- INTERNET exists only for native optional loopback API monitoring; document WebViews remain origin restricted. HTTP cleartext is allowed only for loopback; no global TLS bypass.
- Native cache/drafts are private app data; uninstalling removes them. Keep signed-release key private and stable.

## Syncthing contract

Maintained documented companion: researchxxl/syncthing-android, package com.github.catfriend1.syncthingfork. Resolve its public launcher; do not hardcode unexported activities or assume private provisioning/key intents. Setup-folder equality requires user confirmation: generic SAF URI ≠ physical Syncthing path. Local idle index/connected peer does not prove remote receipt. Snapshot timestamp/staleness/errors must remain explicit; no background polling.

## Build, release and evidence

Node22.12+, JDK21, SDK36. Default workstation Java may be too new: explicitly set JAVA_HOME. `npm test`; `npm run android:build`; `npm run android:test`; `cd android && ./gradlew connectedDebugAndroidTest`.

`npm run android:release` creates/reuses ignored private .signing/keystore.jks + config.json (alias notepit), or reads NOTEPIT_KEYSTORE/NOTEPIT_STORE_PASSWORD. CI secrets: ANDROID_KEYSTORE_BASE64 and ANDROID_KEYSTORE_PASSWORD. Keep a private backup. Old ephemeral GitHub debug builds used a different signature and may require one-time uninstall after preserving unsaved work.

CI setup-android must explicitly install platform-tools; the legacy tools package was removed. Native assets come directly from dist through Gradle sourceSets, not Capacitor sync. APKs: android/app/build/outputs/apk/{debug,release}/app-{debug,release}.apk.

Verified: 12 Node invariants; 26 Android instrumentation tests (19 SAF/storage, 7 sync); real API36 SAF authorization/edit/save; unsaved text journal replay and persisted grant after force-stop; multi-document native UI; PDF single/multiline typing across idle native drafts with zero focus losses and complete annotation parsed from the original shared-storage PDF; sanitized math preview; 120-page PDF bounded/on-demand rendering, crop/viewport/bookmark restoration and five-entry outline; two isolated real Syncthing2.1.7 engines bidirectional/offline reconciliation and conflict-copy preservation. Instrumentation uses isolated real resolver/provider streams and real loopback sockets, not a physical Fork installation.

Do not claim physical OnePlus/OxygenOS background behavior, Windows-phone transfer or actual maintained-Fork pairing as verified. GitHub issues were checked: none open required closing. Repository: https://github.com/ManfuG/NotePit.
