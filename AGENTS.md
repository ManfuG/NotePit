# NotePit — agent memory

## Scope and architecture

NotePit 0.4: Kotlin + Jetpack Compose Material3 Android shell, native SAF operations, offline PDF/code engines, global content search, optional on-device semantic retrieval and optional Syncthing-Fork setup/status workflow. English UI/code/docs. No generative answers, Anki, proprietary sync protocol, account or mandatory cloud. Markdown/math and LaTeX formula previews are in scope; full LaTeX compilation is not.

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
- `SearchIndex.kt`, `SearchScreen.kt`: native SQLite FTS4/unicode61, BM25 literal retrieval, coordinate-preserving passage chunks and streamed cosine/RRF hybrid ranking. Search is separate from filename filtering. Private index is disposable and excluded from backup. Refresh hashes bounded original snapshots to detect timestamp-preserving external edits; only changed revisions are re-extracted. Disk-spooled extraction and embeddings stay outside short SQL locks so published results remain queryable during scans. Foreground/provider changes trigger debounced refresh; no background polling.
- `SemanticEngine.kt`, `WordPieceTokenizer.kt`: optional English INT8 MiniLM-L3-v2, pinned revision and SHA-256 assets, 17,684,637-byte consent-only HTTPS download, 64 MiB installation free-space requirement, 128 tokens, 384-dimensional vectors, one CPU thread. Encoding never downloads or uploads. Remove-model action releases native state. Keep downloaded models private and outside backup.
- `SearchLocation` / `DocumentHandle.reveal`: transient search navigation, not document annotations. Verify indexed source before exact labels; literal matches use translucent rectangles and wrapping navigation. Semantic highlights identify a source passage, never a generated answer. Clean stale snapshots reload before reveal; dirty buffers remain intact. Preserve pending-reveal URI association across asynchronous open/recreation.
- Search extraction limits: 16 MiB text; 64 MiB/2,000-page PDF; 25,000 passages/document; 256-character queries; 100 results. Strict UTF-8/BOM UTF-16 and mixed line references. No OCR or full-document LaTeX compilation. Report exclusions and English-model uncertainty; do not silently claim full coverage.
- Native launch uses a shared orbital notebook vector and adaptive launcher resources, cobalt/raspberry palette, light/dark splash backgrounds and normal Android splash lifecycle. No extra launch page or artificial delay.
- Native FTS4 must use whitespace conjunction between quoted tokens, not explicit `AND`: legacy Android query syntax otherwise drops multi-token matches. Keep positive-BM25 regression coverage so literal fallback cannot hide this failure.
- PDF.js creates a highlighter before `textDivs`/`textContentItemsStr` exist. Both are nullable until the connected text layer renders; cold semantic reveals must wait for `textlayerrendered`, not report success without rectangles.

## Invariants

- Work on granted original files, not imported folder copies; no broad storage permission. Unknown MIME types use application/octet-stream so Android does not append .txt.
- All SAF/network/hash operations off main. Never delete a transfer source until full byte/membership verification succeeds. Report partial batches and retained destinations.
- Never silently overwrite external edits. Preserve originals on failure and unsaved edits during a save. SAF cannot provide atomic external-writer CAS; do not claim transactional crash safety.
- UTF8/BOM and mixed LF/CRLF/CR survive edit/save/undo. BOM-marked UTF16LE/BE must retain byte order. Reject unknown encodings rather than guess.
- User content/names never enter unsafe innerHTML. Preview rendering must stay sanitized and offline. PDF scripting/external navigation stay disabled.
- Android API26 minimum; avoid unsupported APIs such as InputStream.transferTo. Runtime WebView must support Promise.withResolvers and binary web messages; feature checks must fail clearly.
- INTERNET is used only by native optional loopback monitoring and explicitly consented HTTPS model downloads. Searching never requires network access. Document WebViews remain origin restricted. HTTP cleartext is allowed only for loopback; no global TLS bypass.
- Native cache/drafts are private app data; uninstalling removes them. Keep signed-release key private and stable.

## Syncthing contract

Maintained documented companion: researchxxl/syncthing-android, package com.github.catfriend1.syncthingfork. Resolve its public launcher; do not hardcode unexported activities or assume private provisioning/key intents. Setup-folder equality requires user confirmation: generic SAF URI ≠ physical Syncthing path. Local idle index/connected peer does not prove remote receipt. Snapshot timestamp/staleness/errors must remain explicit; no background polling.

## Build, release and evidence

Node22.12+, JDK21, SDK36. Default workstation Java may be too new: explicitly set JAVA_HOME. `npm test`; `npm run android:build`; `npm run android:test`; `cd android && ./gradlew connectedDebugAndroidTest`.

`npm run android:release` creates/reuses ignored private .signing/keystore.jks + config.json (alias notepit), or reads NOTEPIT_KEYSTORE/NOTEPIT_STORE_PASSWORD. CI secrets: ANDROID_KEYSTORE_BASE64 and ANDROID_KEYSTORE_PASSWORD. Keep a private backup. Old ephemeral GitHub debug builds used a different signature and may require one-time uninstall after preserving unsaved work.
Local releases require `CI` unset when using `.signing/`; real CI must provide both signing environment variables. Never print credentials or replace an existing key to bypass a signing failure. PDFBox's optional JPX linkage needs `jp2-android:1.0.5` (OpenJPEG 2.5.4) for R8; version 1.1.0 requires SDK37. Do not hide missing decoder classes with warning suppression. Native/model attribution is maintained in `scripts/search-dependency-notices.txt`.
ONNX Runtime's Java package must remain intact under R8 (`-keep class ai.onnxruntime.** { *; }`): its JNI bridge resolves classes, constructors and fields by name. Debug inference alone does not verify this; exercise the optimized signed APK offline before publishing.

CI setup-android must explicitly install platform-tools; the legacy tools package was removed. Native assets come directly from dist through Gradle sourceSets, not Capacitor sync. APKs: android/app/build/outputs/apk/{debug,release}/app-{debug,release}.apk.

Current verification: 12 Node invariants, 10 tokenizer JVM tests and all 39 Android instrumentation tests (20 SAF/storage, 11 index, 7 sync, 1 real-model smoke with networking disabled). Actual API36 UI: exact PDF/text highlighting and next/previous navigation; cold PDF semantic rectangles; consent/cancel/removal with vectors cleared; externally changed clean snapshots automatically reloaded at their result; dirty text preserved through result navigation, rotation and force-stop; multiline PDF annotations across idle drafts, explicit save and reopen.
Signed-release smoke: R8/JNI inference and cold PDF semantic rectangles work with Wi-Fi/mobile data disabled. Upgrade from the published signed 0.2.0 APK retained the SAF grant and an unsaved text draft; its signing certificate is unchanged. Light/dark launcher splash visuals were observed on API36.

Measured emulator fixture: 100 documents/1,091 passages/292,300 source bytes; 1,736,704-byte lexical index; initial index627.5ms, one-edit refresh213.4ms, keyword median33.9ms/p9537.3ms, literal source recall100/100. Isolated real model: cold247.6ms, warm median27.0ms, 4/4 non-identical questions selected intended passages, process PSS increase54.2MiB. These are synthetic emulator measurements, not general accuracy, peak memory or physical-phone guarantees.

Earlier verified document/sync evidence: 120-page PDF on-demand rendering, crop/viewport/bookmark restoration and outline; sanitized math previews; two isolated real Syncthing engines with bidirectional/offline reconciliation and conflict-copy preservation. Loopback instrumentation is not physical companion pairing.

Do not claim physical-device background behavior, cross-device transfer or actual maintained-Fork pairing as verified without new evidence. Published historical author/committer identities and older documentation retain personal references; current-tree cleanup does not erase history. Never rewrite published history automatically. Use privacy-safe author and committer identities for new commits.
Audited historical commits retaining personal metadata/content: `b1d4159670fd103b9ad0442982ba8d71d8888e70`, `64c60c2354ee4ace8dd355ea1f0b14f57666dfb4`, `d2bbfb1a9e0c35599fb08c3ac7060684421ae804`, `c158bc2f1328db66fd72dc0bd36516b7743b600b`. No probable live secrets/private signing keys found. Signing files remain ignored/private; required third-party names and license notices stay intact.
