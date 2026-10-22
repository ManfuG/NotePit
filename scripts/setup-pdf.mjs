import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, rm, rename } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { unzipSync } from 'fflate';

// Official Mozilla generic viewer, including its worker, fonts, CMaps and WASM.
// Source and publisher-provided digest:
// https://github.com/mozilla/pdf.js/releases/tag/v6.4.299
// https://api.github.com/repos/mozilla/pdf.js/releases/assets/608139980
const version = '6.4.299';
const digest = 'c894bce53b2f4c3e13141e088c89cc051a1f7118ba4a2c02ec058798ae1303d1';
const source = `https://github.com/mozilla/pdf.js/releases/download/v${version}/pdfjs-${version}-legacy-dist.zip`;
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const target = join(root, 'public/pdfjs');
const scriptDigest = createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex');
const stamp = JSON.stringify({ version, digest, scriptDigest });
if (await readFile(join(target, '.notepit-source'), 'utf8').catch(() => '') === stamp) {
  process.exit(0);
}

// Cache only the verified upstream archive; runtime never contacts this URL.
const cache = join(root, 'node_modules/.cache/notepit');
const archive = join(cache, `pdfjs-${version}-${digest}.zip`);
let zip = await readFile(archive).catch(() => null);
if (!zip) {
  console.log(`Downloading official PDF.js ${version} viewer...`);
  const response = await fetch(source);
  if (!response.ok) throw new Error(`PDF.js download failed: HTTP ${response.status}`);
  zip = Buffer.from(await response.arrayBuffer());
}
if (createHash('sha256').update(zip).digest('hex') !== digest) {
  throw new Error('PDF.js archive checksum does not match the pinned Mozilla release.');
}
await mkdir(cache, { recursive: true });
await writeFile(archive, zip);
const staging = `${target}.tmp`;
await rm(staging, { recursive: true, force: true });
await mkdir(staging, { recursive: true });
try {
  const entries = unzipSync(zip);
  for (const [name, bytes] of Object.entries(entries)) {
    if (name.endsWith('/') || name.endsWith('.map') || name.includes('..') || name.startsWith('/')) continue;
    if (name.startsWith('web/locale/') && name !== 'web/locale/locale.json' && !name.startsWith('web/locale/en-US/')) continue;
    if (name === 'web/compressed.tracemonkey-pldi-09.pdf' || name.startsWith('web/debugger.')) continue;
    const destination = join(staging, name);
    await mkdir(dirname(destination), { recursive: true });
    await writeFile(destination, bytes);
  }
  // NotePit modification: snapshot FreeText without disabling contenteditable.
  // Toggling it closes Android's IME and asynchronously resets multiline carets.
  const enginePath = join(staging, 'build/pdf.mjs');
  let engine = await readFile(enginePath, 'utf8');
  const commit = `  commit() {
    if (!this.isInEditMode()) {
      return;
    }
    super.commit();
    this.disableEditMode();
    const savedText = this.#content;`;
  if (engine.indexOf(commit) < 0 || engine.indexOf(commit) !== engine.lastIndexOf(commit)) {
    throw new Error('Unexpected upstream PDF.js FreeText commit implementation.');
  }
  engine = engine.replace(commit, commit.replace('commit()', 'commit(keepEditing = false)')
    .replace('    this.disableEditMode();', '    // NotePit: retain Android IME and caret while snapshotting drafts.\n    if (keepEditing !== true) this.disableEditMode();'));
  // Repositioning a live multiline editor also resets Android's native caret.
  // Refresh its serialized dimensions now; constrain its position on real commit.
  const geometryEdits = [
    ['  #setEditorDimensions() {', '  #setEditorDimensions(keepPosition = false) {'],
    ['    this.fixAndSetPosition();\n  }\n  commit(keepEditing = false) {',
      '    if (!keepPosition) this.fixAndSetPosition();\n  }\n  commit(keepEditing = false) {'],
    ['      mustExec: false\n    });\n    this.#setEditorDimensions();\n  }\n  shouldGetKeyboardEvents() {',
      '      mustExec: false\n    });\n    this.#setEditorDimensions(keepEditing === true);\n  }\n  shouldGetKeyboardEvents() {'],
  ];
  for (const [before, after] of geometryEdits) {
    if (engine.indexOf(before) < 0 || engine.indexOf(before) !== engine.lastIndexOf(before)) {
      throw new Error('Unexpected upstream PDF.js FreeText geometry implementation.');
    }
    engine = engine.replace(before, after);
  }
  await writeFile(enginePath, engine);
  const htmlPath = join(staging, 'web/viewer.html');
  let html = await readFile(htmlPath, 'utf8');
  const stylesheet = '<link rel="stylesheet" href="viewer.css" />';
  if (!html.includes(stylesheet)) throw new Error('Unexpected upstream PDF.js viewer markup.');
  html = html.replace(stylesheet, `${stylesheet}\n    <link rel="stylesheet" href="notepit.css" />`)
    .replace('connect-src * blob: data:;', "connect-src 'self' blob: data:;");
  await writeFile(htmlPath, html);
  await writeFile(join(staging, 'web/locale/locale.json'), JSON.stringify({ 'en-us': 'en-US/viewer.ftl' }));
  await writeFile(join(staging, 'web/notepit.css'), `/* NotePit shell owns opening, saving and navigation. */
#secondaryOpenFile, #printButton, #secondaryPrint, #downloadButton,
#secondaryDownload, #presentationMode, #viewBookmark, #editorModeSeparator,
#attachmentsViewMenu, #viewsManagerAddFileButton, #viewsManagerStatusActionExport { display: none !important; }
html.notepit-readonly #editorModeButtons { display: none !important; }
/* Keep PDF.js 6's viewsManager selector and outlinesViewMenu visible: they
   provide the actual embedded document outline, not NotePit page bookmarks. */
#toolbarViewer > * { min-width: 0; }
#toolbarContainer { height: 84px; padding: 0; }
#toolbarContainer #toolbarViewer { height: 44px; }
#viewerContainer { top: 84px; }
#loadingBar { top: 84px !important; }
.notepit-reader-tools {
  box-sizing: border-box; height: 40px; display: flex; align-items: center;
  gap: 6px; padding: 3px 8px; overflow-x: auto; white-space: nowrap;
  border-top: 1px solid light-dark(#d9ddeb, #343c52);
  background: light-dark(#fafaff, #1b2030); color: light-dark(#22263a, #e7e9f5);
}
.notepit-reader-tools button, .notepit-reader-tools select {
  flex-shrink: 0; min-height: 32px; border: 1px solid light-dark(#c2c9df, #48516d);
  border-radius: 6px; padding: 4px 9px; font: inherit; font-size: 12px;
  background: transparent; color: inherit;
}
.notepit-reader-tools button[aria-pressed="true"] {
  background: light-dark(#e3e9ff, #31416e); border-color: light-dark(#3157d5, #b9c4ff);
}
.notepit-reader-tools :focus-visible { outline: 2px solid light-dark(#3157d5, #b9c4ff); }
html.notepit-dark { color-scheme: dark; }
html:not(.notepit-dark) { color-scheme: light; }
.pdfViewer .page.notepit-cropped {
  overflow: clip;
  width: calc(var(--total-scale-factor) * var(--notepit-crop-width)) !important;
  height: calc(var(--total-scale-factor) * var(--notepit-crop-height)) !important;
}
.pdfViewer .page.notepit-cropped > :is(.canvasWrapper, .textLayer, .annotationLayer, .annotationEditorLayer, .drawLayer, .xfaLayer) {
  translate: calc(-1 * var(--total-scale-factor) * var(--notepit-crop-left))
    calc(-1 * var(--total-scale-factor) * var(--notepit-crop-top));
}
.pdfViewer .page.notepit-cropped > .canvasWrapper {
  width: calc(var(--total-scale-factor) * var(--notepit-full-width));
  height: calc(var(--total-scale-factor) * var(--notepit-full-height));
}
@media (max-width: 900px) {
  #toolbarContainer { height: 128px; }
  #toolbarContainer #toolbarViewer { height: 88px; flex-wrap: wrap; align-content: center; }
  #toolbarContainer #toolbarViewerLeft { margin-inline-start: 0; }
  #toolbarViewerLeft, #toolbarViewerMiddle, #toolbarViewerRight { height: 42px; }
  #toolbarViewerRight { width: 100%; justify-content: center; }
  #viewerContainer { top: 128px; }
  #loadingBar { top: 128px !important; }
  #toolbarViewer .verticalToolbarSeparator { display: none; }
  #findbar { max-width: calc(100vw - 16px); inset-inline-start: 0; }
  #viewsManager { max-width: 100vw; }
}
html.notepit-immersive #toolbarContainer { display: none !important; }
html.notepit-immersive #viewerContainer { top: 0 !important; }
html.notepit-immersive #loadingBar { top: 0 !important; }
`);
  await writeFile(join(staging, '.notepit-source'), stamp);
  await rm(target, { recursive: true, force: true });
  await rename(staging, target);
} catch (error) {
  await rm(staging, { recursive: true, force: true });
  throw error;
}
console.log(`Bundled PDF.js ${version} for offline use.`);
