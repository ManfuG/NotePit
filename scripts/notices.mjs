import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(await readFile(join(root, 'package-lock.json'), 'utf8'));
const heading = 'NotePit - third-party software notices\n\nGenerated from package-lock.json and the pinned offline PDF.js distribution.\nBuild-only npm dependencies are excluded.\n';
const sections = [heading];
const licenseFile = /^(?:licen[cs]e|copying|notice)(?:[._-]|$)/i;
const text = source => source.replace(/\r\n?/g, '\n').trim();
const section = (title, body) => sections.push(`${'='.repeat(72)}\n${title}\n${'='.repeat(72)}\n\n${text(body)}\n`);

// Lock paths preserve nested versions; sorting never depends on locale or mtimes.
for (const path of Object.keys(lock.packages).sort()) {
  const entry = lock.packages[path];
  if (!path.startsWith('node_modules/') || entry.dev) continue;
  const directory = join(root, path);
  const installed = JSON.parse(await readFile(join(directory, 'package.json'), 'utf8'));
  if (installed.version !== entry.version) throw new Error(`Run npm ci: locked version mismatch for ${path}.`);
  const files = (await readdir(directory, { withFileTypes: true }))
    .filter(file => file.isFile() && licenseFile.test(file.name)).map(file => file.name).sort();
  if (!files.length) throw new Error(`No license text found for ${installed.name}; add its upstream license before shipping.`);
  const body = [];
  for (const file of files) body.push(`--- ${file} ---\n\n${text(await readFile(join(directory, file), 'utf8'))}`);
  section(`${installed.name} ${entry.version}\nLicense: ${entry.license ?? installed.license ?? 'See included text'}\nLocked package: ${path}`, body.join('\n\n'));
}

const pdfRoot = join(root, 'public/pdfjs');
const pdfSource = JSON.parse(await readFile(join(pdfRoot, '.notepit-source'), 'utf8'));
async function pdfLicenses(directory, relative = '') {
  const entries = (await readdir(directory, { withFileTypes: true }))
    .sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  for (const entry of entries) {
    const { name } = entry;
    const path = relative ? `${relative}/${name}` : name;
    if (entry.isDirectory()) await pdfLicenses(join(directory, name), path);
    else if (entry.isFile() && licenseFile.test(name)) section(`PDF.js ${pdfSource.version} - ${path}`, await readFile(join(directory, name), 'utf8'));
  }
}
await pdfLicenses(pdfRoot);
const apache = await readFile(join(pdfRoot, 'LICENSE'), 'utf8');
section('Native Android runtime - AndroidX / Jetpack Compose', `Copyright The Android Open Source Project contributors.\n\nAndroidX Activity, Core Splashscreen, Lifecycle, WebKit and Jetpack Compose\n(UI, Runtime, Foundation, Material / Material 3 and icons), including their\nAndroidX runtime dependencies, are licensed under the Apache License 2.0.\nhttps://android.googlesource.com/platform/frameworks/support/\n\n${apache}`);
section('Native Android runtime - Kotlin and kotlinx.coroutines', `Copyright JetBrains s.r.o. and Kotlin contributors.\n\nThe Kotlin standard library and kotlinx.coroutines (core and Android) are\nlicensed under the Apache License 2.0.\nhttps://github.com/JetBrains/kotlin\nhttps://github.com/Kotlin/kotlinx.coroutines\n\n${apache}`);
await writeFile(join(root, 'public/THIRD_PARTY_NOTICES.txt'), `${sections.join('\n')}\n`);
console.log('Generated public/THIRD_PARTY_NOTICES.txt from locked runtime packages and offline licenses.');
