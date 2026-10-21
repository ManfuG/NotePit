import { createElement, Folder, FileText, FileCode, File, ChevronRight, ArrowLeft, Plus, MoreVertical, RefreshCw, Search, FolderOpen, BookOpen } from 'lucide';
import { files, native, readFile, fingerprint, toBase64, isPdf, isText } from './files';
import type { Entry } from './files';
import { openEditor } from './editor';
import { openPdf } from './pdf';
import './style.css';

type DocumentView = { save(): Promise<Uint8Array>; destroy(): void; markSaved?(): void };
const app = document.querySelector<HTMLDivElement>('#app')!;
app.innerHTML = `<header><div class="brand"><span class="brand-icon"></span><span>NotePit</span></div><span class="offline">Offline files</span></header>
<section id="browser"><div class="heading"><h1>Files</h1><button id="add" class="primary">Add folder</button></div>
<nav id="path" aria-label="Folder path"></nav><div class="tools"><label class="filter"><span id="search-icon"></span><input id="filter" type="search" placeholder="Filter this folder" aria-label="Filter this folder"></label><button id="refresh" class="icon" aria-label="Refresh"></button><button id="create" class="icon" aria-label="Create file or folder"></button></div>
<div id="entries" role="list"></div><p id="count" class="muted"></p></section>
<section id="document" hidden><div class="document-bar"><button id="back" class="icon" aria-label="Back to files"></button><div class="document-title"><strong id="filename"></strong><span id="file-state"></span></div><button id="save" class="primary">Save</button></div><div id="document-host"></div></section>
<div id="status" role="status" hidden></div><dialog id="dialog"><form method="dialog"><h2 id="dialog-title"></h2><div id="dialog-body"></div><div id="dialog-actions"></div></form></dialog>`;
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const browserView = $('browser'), documentView = $('document'), host = $('document-host');
const filter = $<HTMLInputElement>('filter'), saveButton = $<HTMLButtonElement>('save');
const dialog = $<HTMLDialogElement>('dialog');
const path: Entry[] = [];
let entries: Entry[] = [], current: Entry | null = null, viewer: DocumentView | null = null;
let dirty = false, busy = false, revision = '', loading = false, loadId = 0;
let statusTimer: number;
app.querySelector('.brand-icon')!.append(createElement(BookOpen));
$('search-icon').append(createElement(Search));
$('refresh').append(createElement(RefreshCw));
$('create').append(createElement(Plus));
$('back').append(createElement(ArrowLeft));

function notify(message: string, error = false) {
  const status = $('status');
  status.textContent = message;
  status.classList.toggle('error', error);
  status.hidden = false;
  clearTimeout(statusTimer);
  statusTimer = window.setTimeout(() => { status.hidden = true; }, error ? 10000 : 3000);
}
function failure(error: unknown) {
  if (error && typeof error === 'object' && 'code' in error && error.code === 'CANCELLED') return;
  notify(error instanceof Error ? error.message : String(error), true);
}
function updateDirty(value: boolean) {
  dirty = value;
  $('file-state').textContent = !current?.writable ? 'Read only' : dirty ? 'Unsaved changes' : 'Saved';
  saveButton.disabled = !dirty || !current?.writable || busy;
}
function ask(title: string, options: { label: string; value: string; danger?: boolean }[], initial?: string): Promise<string | null> {
  $('dialog-title').textContent = title;
  $('dialog-body').replaceChildren();
  let input: HTMLInputElement | undefined;
  if (initial !== undefined) {
    input = document.createElement('input'); input.value = initial; input.required = true;
    input.autocomplete = 'off'; input.setAttribute('aria-label', 'Name');
    $('dialog-body').append(input);
  }
  const actions = $('dialog-actions'); actions.replaceChildren();
  for (const option of [...options, { label: 'Cancel', value: '' }]) {
    const button = document.createElement('button');
    button.textContent = option.label; button.value = option.value;
    if (option.danger) button.className = 'danger';
    if (!option.value) button.formNoValidate = true;
    actions.append(button);
  }
  dialog.returnValue = '';
  dialog.showModal();
  input?.select();
  const { promise, resolve } = Promise.withResolvers<string | null>();
  dialog.addEventListener('close', () => resolve(dialog.returnValue ? input?.value.trim() ?? dialog.returnValue : null), { once: true });
  return promise;
}
async function refresh() {
  const id = ++loadId;
  loading = true;
  $('entries').textContent = 'Loading…';
  try {
    const result = !native ? { entries: [] } : path.length ? await files.list({ uri: path.at(-1)!.uri }) : await files.roots();
    if (id !== loadId) return;
    entries = result.entries.sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name, undefined, { numeric: true }));
    loading = false;
    render();
  } catch (error) {
    if (id !== loadId) return;
    entries = []; loading = false; render(); failure(error);
  }
}
function render() {
  const breadcrumbs = $('path'); breadcrumbs.replaceChildren();
  const root = document.createElement('button'); root.textContent = 'Folders';
  root.onclick = () => { path.length = 0; filter.value = ''; void refresh(); };
  breadcrumbs.append(root);
  path.forEach((entry, index) => {
    breadcrumbs.append(createElement(ChevronRight));
    const button = document.createElement('button'); button.textContent = entry.name;
    button.onclick = () => { path.length = index + 1; filter.value = ''; void refresh(); };
    breadcrumbs.append(button);
  });
  $<HTMLButtonElement>('create').disabled = !path.at(-1)?.writable || !native;
  const list = $('entries'); list.replaceChildren();
  const visible = entries.filter(entry => entry.name.toLocaleLowerCase().includes(filter.value.toLocaleLowerCase()));
  for (const entry of visible) {
    const row = document.createElement('div'); row.className = 'entry'; row.setAttribute('role', 'listitem');
    const open = document.createElement('button'); open.className = 'entry-open';
    const icon = createElement(entry.directory ? Folder : isPdf(entry.name) ? FileText : isText(entry.name) ? FileCode : File);
    icon.classList.add(entry.directory ? 'folder-icon' : isPdf(entry.name) ? 'pdf-icon' : 'text-icon');
    const text = document.createElement('span'); text.className = 'entry-text';
    const name = document.createElement('strong'); name.textContent = entry.name;
    const detail = document.createElement('span');
    detail.textContent = entry.directory ? 'Folder' : `${entry.size < 1024 ? `${entry.size} B` : entry.size < 1048576 ? `${(entry.size / 1024).toFixed(1)} KB` : `${(entry.size / 1048576).toFixed(1)} MB`}${entry.writable ? '' : ' · Read only'}`;
    text.append(name, detail); open.append(icon, text);
    open.onclick = () => {
      if (loading) return;
      if (entry.directory) { path.push(entry); filter.value = ''; void refresh(); }
      else void openDocument(entry);
    };
    const menu = document.createElement('button'); menu.className = 'icon'; menu.setAttribute('aria-label', `Options for ${entry.name}`);
    menu.append(createElement(MoreVertical)); menu.onclick = () => { void entryMenu(entry); };
    row.append(open, menu); list.append(row);
  }
  if (!visible.length) {
    const empty = document.createElement('div'); empty.className = 'empty';
    empty.append(createElement(FolderOpen));
    const title = document.createElement('h2');
    title.textContent = filter.value ? 'No matches' : path.length ? 'Empty folder' : 'Your folders, in one place';
    const description = document.createElement('p');
    description.textContent = filter.value ? 'Try a different filename.' : path.length ? 'Create a file or folder with +.' : native ? 'Add a local folder to open and edit its files.' : 'Install the Android app to connect local folders.';
    empty.append(title, description); list.append(empty);
  }
  $('count').textContent = entries.length ? `${visible.length} ${path.length ? 'items' : 'folders'}` : '';
}
async function entryMenu(entry: Entry) {
  const atRoot = path.length === 0;
  const action = await ask(entry.name, atRoot ? [{ label: 'Disconnect folder', value: 'forget' }] : [
    { label: 'Rename', value: 'rename' }, { label: 'Delete', value: 'delete', danger: true }
  ]);
  try {
    if (action === 'forget') await files.forget({ uri: entry.uri });
    if (action === 'rename') {
      const name = await ask('Rename', [{ label: 'Rename', value: 'ok' }], entry.name);
      if (!name || name === entry.name) return;
      await files.rename({ uri: entry.uri, name });
    }
    if (action === 'delete') {
      if (await ask(`Delete ${entry.name}${entry.directory ? ' and its contents' : ''}?`, [{ label: 'Delete permanently', value: 'delete', danger: true }]) !== 'delete') return;
      await files.remove({ uri: entry.uri });
    }
    if (action) await refresh();
  } catch (error) { failure(error); }
}
async function openDocument(entry: Entry) {
  if (busy || current) return;
  if (!isPdf(entry.name) && !isText(entry.name)) { notify('This file type is not supported by the PDF reader or text editor.', true); return; }
  current = entry; busy = true;
  browserView.hidden = true; documentView.hidden = false;
  $('filename').textContent = entry.name;
  updateDirty(false); host.textContent = 'Opening…';
  try {
    const bytes = await readFile(entry);
    revision = await fingerprint(bytes);
    host.replaceChildren();
    const options = { name: entry.name, bytes, readOnly: !entry.writable, onDirty: updateDirty };
    viewer = isPdf(entry.name) ? await openPdf(host, options) : await openEditor(host, options);
  } catch (error) {
    viewer?.destroy(); viewer = null; current = null;
    documentView.hidden = true; browserView.hidden = false; host.replaceChildren(); failure(error);
  } finally { busy = false; updateDirty(dirty); }
}
async function save() {
  if (!current || !viewer || !dirty || busy || !current.writable) return;
  busy = true; updateDirty(dirty); saveButton.textContent = 'Saving…';
  try {
    const bytes = await viewer.save();
    await files.save({ uri: current.uri, data: toBase64(bytes), revision });
    revision = await fingerprint(bytes);
    if (viewer.markSaved) viewer.markSaved();
    else updateDirty(false);
    notify('Saved');
  } catch (error) { failure(error); }
  finally { busy = false; saveButton.textContent = 'Save'; updateDirty(dirty); }
}
async function back() {
  if (busy || dialog.open) return;
  if (current) {
    if (dirty && await ask('Discard unsaved changes?', [{ label: 'Discard', value: 'discard', danger: true }]) !== 'discard') return;
    viewer?.destroy(); viewer = null; current = null; dirty = false;
    host.replaceChildren(); documentView.hidden = true; browserView.hidden = false; await refresh();
  } else if (path.length) { path.pop(); filter.value = ''; await refresh(); }
}
$('add').onclick = async () => {
  if (!native) { notify('Folder access is available in the Android app.'); return; }
  try { await files.pickFolder(); path.length = 0; filter.value = ''; await refresh(); } catch (error) { failure(error); }
};
$('refresh').onclick = () => { void refresh(); };
$('create').onclick = async () => {
  const parent = path.at(-1); if (!parent?.writable) return;
  const kind = await ask('Create', [{ label: 'Text file', value: 'file' }, { label: 'Folder', value: 'folder' }]);
  if (!kind) return;
  const name = await ask(kind === 'folder' ? 'New folder' : 'New file', [{ label: 'Create', value: 'create' }], kind === 'folder' ? '' : 'untitled.txt');
  if (!name) return;
  try {
    const { entry } = await files.create({ uri: parent.uri, name, directory: kind === 'folder' });
    await refresh(); if (!entry.directory) await openDocument(entry);
  } catch (error) { failure(error); }
};
filter.oninput = render;
$('back').onclick = () => { void back(); };
saveButton.onclick = () => { void save(); };
window.addEventListener('notepit-back', event => {
  if (dialog.open) { event.preventDefault(); dialog.close(); return; }
  if (busy || current || path.length) { event.preventDefault(); void back(); }
});
window.addEventListener('beforeunload', event => { if (dirty) event.preventDefault(); });
window.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); void save(); }
});
void refresh();
