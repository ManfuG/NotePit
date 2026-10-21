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
#attachmentsViewMenu, #viewsManagerStatusActionExport { display: none !important; }
html.notepit-readonly #editorModeButtons { display: none !important; }
#toolbarViewer > * { min-width: 0; }
@media (max-width: 900px) {
  #toolbarContainer { height: 88px; }
  #toolbarContainer #toolbarViewer { flex-wrap: wrap; align-content: center; }
  #toolbarContainer #toolbarViewerLeft { margin-inline-start: 0; }
  #toolbarViewerLeft, #toolbarViewerMiddle, #toolbarViewerRight { height: 42px; }
  #toolbarViewerRight { width: 100%; justify-content: center; }
  #viewerContainer { top: 88px; }
  #loadingBar { top: 88px !important; }
  #toolbarViewer .verticalToolbarSeparator { display: none; }
  #findbar { max-width: calc(100vw - 16px); inset-inline-start: 0; }
  #viewsManager { max-width: 100vw; }
  html.notepit-readonly #toolbarContainer { height: 44px; }
  html.notepit-readonly #toolbarViewerRight { width: auto; }
  html.notepit-readonly #viewerContainer { top: 44px; }
  html.notepit-readonly #loadingBar { top: 44px !important; }
}
`);
  await writeFile(join(staging, '.notepit-source'), stamp);
  await rm(target, { recursive: true, force: true });
  await rename(staging, target);
} catch (error) {
  await rm(staging, { recursive: true, force: true });
  throw error;
}
console.log(`Bundled PDF.js ${version} for offline use.`);
