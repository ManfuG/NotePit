# NotePit — agent memory

## Scope

Android app: file manager, PDF reader, text/code editor only. English UI, code and documentation. No Search/Library/Settings placeholders, sync protocol, AI models, Markdown preview or LaTeX compilation. Keep UI and implementation small; prefer maintained engines over custom parsers/renderers.

## Implementation map

- `src/main.ts`: framework-free navigation, folder management, document lifecycle, dirty/save state and dialogs.
- `src/files.ts`: typed Capacitor bridge, content URI fetch, SHA-256 fingerprints and save encoding.
- `src/editor.ts`: CodeMirror 6 languages, persistent newline metadata, UTF-8 safety and exact saved-snapshot tracking. `markSaved()` must run only after native disk success; edits during a save must remain dirty.
- `src/pdf.ts`: same-origin sandboxed official PDF.js iframe. Scripting and external navigation disabled. Annotation generation tracking protects edits during a save. Fresh upstream `open()` assigns `pdfLoadingTask` synchronously; only an already-open task causes a preceding await.
- `scripts/setup-pdf.mjs`: pinned official PDF.js legacy release + published SHA-256; builds provision ignored `public/pdfjs/`. Preserve offline fonts/CMaps/WASM and third-party notices.
- `android/app/src/main/java/io/notepit/app/LocalFilesPlugin.java`: persisted SAF tree grants and single background I/O executor. Query direct children only. Save checks the original hash, keeps a temporary original, and attempts restoration on write failure. SAF is not transactional.
- `MainActivity.java`: registers the plugin and delegates native Back to the cancelable `notepit-back` event. Unhandled root Back backgrounds the task.

## Invariants

- Operate on granted original files; no folder import/copy or broad storage permission.
- Unknown text-file MIME types use `application/octet-stream`; hardcoding `text/plain` causes Android to append `.txt` to names such as `notes.md`.
- No file content or filenames interpolated into `innerHTML`.
- UTF-8 BOM and mixed LF/CRLF/CR survive edit/save and undo. Never clear dirty state before disk success.
- Android minimum API 26: do not use newer Java/Android APIs such as `InputStream.transferTo` without desugaring/support checks.
- No network permission or runtime downloads. Keep dependency/license notices current when changing runtime dependencies.
- Static imports for known modules, top-level type imports, `Promise.withResolvers`, no concrete `ReturnType<typeof fn>` contracts.

## Build and verification

Node 22.12+, JDK 21, SDK 36. `npm ci`; `npm run android:build`. Default workstation Java can be newer than Gradle supports: explicitly set `JAVA_HOME` to JDK 21. Local SDK configuration stays ignored.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. CI: `.github/workflows/android.yml` uploads `NotePit-debug`.

CI must explicitly set `android-actions/setup-android` packages to `platform-tools`: the action's default `tools` package has been removed from Google's repository.

Verified on API 36 emulator: real SAF picker/grant persistence, nested folders, Markdown filenames, text save/reopen, rename/filter/delete, conflict refusal preserving disk contents, PDF search and saved annotations. Browser smoke verified undo/redo, encoding rejection and mixed newline/BOM preservation. Android WebView debugging is available in debug builds through adb forwarding its `webview_devtools_remote_<pid>` socket; reattach after reinstall. No physical-device benchmark or unexercised API-level compatibility claim.

GitHub repository: https://github.com/ManfuG/NotePit. It was empty on initialization; no existing issues required closing.
