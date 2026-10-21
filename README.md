# NotePit

Offline Android file manager, PDF reader and text/code editor.

## Features

- Connect multiple local folders through Android’s system picker. Access persists after restarting the app; the existing directory structure stays unchanged.
- Browse folders, filter filenames, create files/folders, rename and delete. Disconnecting a root does not delete its files.
- Read PDFs with page navigation, zoom, text search, highlights and annotations. **Save** writes annotations back to the original PDF.
- Edit UTF-8 text, Markdown, LaTeX, Python, R and C/C++ with syntax highlighting, line numbers, undo/redo, find/replace and indentation. JavaScript/TypeScript highlighting is also included.
- Preserve UTF-8 BOM and existing line endings. Reject binary files and unsupported encodings instead of rewriting them.
- Explicit saves, unsaved-change confirmation and SHA-256 checks against external modifications.

No account, cloud service, network permission, analytics or runtime model downloads. Search across documents, Library, synchronization, Markdown rendering and LaTeX compilation are not implemented.

## Use

1. Install the APK and choose **Add folder**.
2. Select a local directory and grant access. Repeat for additional directories.
3. Tap a PDF or text/code file. Use **Save** to write changes to the original document.

Android restricts selection of storage roots and some system directories. Choose a subfolder. A folder maintained by a separate synchronization app can be selected, but NotePit does not configure or run synchronization.

## Build

Requires Node.js 22.12+, JDK 21 and Android SDK 36. Set `JAVA_HOME` and `ANDROID_HOME`, or configure the SDK path in the ignored `android/local.properties`.

```sh
npm ci
npm run android:build
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds and uploads the same debug APK on pushes and pull requests. Debug APKs are for direct installation, not Play Store publication.

`npm run dev` previews the interface in a browser; persistent folder access requires the Android app. The browser preview is not a second file backend.

## Architecture

- **Capacitor 8 / Java:** Storage Access Framework tree grants, direct child queries and file operations on one background executor. No recursive startup scan or imported folder copy.
- **CodeMirror 6:** viewport-based editing and incremental syntax parsing; persistent document state avoids whole-file copies on each keystroke.
- **Mozilla PDF.js 6.4.299:** official legacy generic viewer, local worker, fonts, CMaps and WASM. Pages render on demand. The active PDF is loaded into memory; large files still depend on device capacity.
- **TypeScript / Vite:** small framework-free application shell. Document names and content are not interpolated into HTML.

`npm run setup:pdf` downloads a pinned official viewer archive and verifies its published SHA-256. Builds invoke it automatically; generated assets are ignored by Git. Runtime operation is offline. Third-party license texts ship in `public/THIRD_PARTY_NOTICES.txt` and the bundled PDF.js license.

## Boundaries

- Android 8.0+ with an up-to-date Android System WebView supporting `Promise.withResolvers` (Chromium 124+). Smoke verification used Android API 36.
- Text/code files must be UTF-8. PDF password prompts are not supported; use an unencrypted copy. PDF editing also respects document permissions.
- Provider capabilities determine whether files can be edited, renamed or deleted. Unsupported formats remain visible but are not opened by these readers.
- Saves check the current content hash before writing and retain a temporary original for best-effort recovery on write errors. SAF does not offer atomic compare-and-swap: another writer can still race the check. Avoid simultaneous edits. A provider/device crash is not a transactional save guarantee.
- Unsaved edits are in memory; save before terminating the app. Folder grants, not unsaved editor buffers, survive process termination.

## Verification

Exercised on the running API 36 Android emulator: system folder authorization, access after reinstall/restart, nested folder creation, exact Markdown filenames, text edit/save/reopen, rename/filter/delete, external-change rejection with the original file preserved, PDF search, and annotation save/reopen. Browser smoke checks also covered editor undo/redo, mixed line endings/BOM and invalid-encoding rejection. No physical-device performance benchmark is claimed.
