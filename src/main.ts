import { openEditor } from './editor';
import { openPdf } from './pdf';
import type { DocumentHandle } from './document';
import './style.css';

interface EngineConfig {
  name: string;
  pdf: boolean;
  readOnly: boolean;
  dark: boolean;
  state?: unknown;
  journal?: unknown[];
  sequence: number;
  recoveredDirty: boolean;
  url: string;
}
interface TransferAck { resolve: () => void; reject: (error: Error) => void }
interface EngineHost {
  open(config: EngineConfig): Promise<void>;
  requestSave(copyName?: string | null): void;
  flushDraft(request?: string): void;
  acknowledge(purpose: string, sequence: number, success: boolean, error: string): void;
  command(command: string): void;
  setTheme(dark: boolean): void;
}
declare global {
  interface Window {
    NotePitNative?: { postMessage(message: string | ArrayBuffer): void };
    NotePit: EngineHost;
  }
}

const host = document.querySelector<HTMLElement>('#app')!;
let handle: DocumentHandle | undefined;
let config: EngineConfig;
let sequence = 0;
let dirty = false;
let recoveredDirty = false;
let pdfNeedsDraft = false;
let draftScheduled = false;
let transfers: Promise<void> = Promise.resolve();
const acknowledgements = new Map<string, TransferAck>();

function post(event: Record<string, unknown>) {
  window.NotePitNative?.postMessage(JSON.stringify(event));
}
function report(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  post({ type: 'error', message });
  if (!handle) host.textContent = message;
}
function theme(dark: boolean) {
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
}
function schedulePdfDraft() {
  if (draftScheduled) return;
  draftScheduled = true;
  queueMicrotask(() => {
    transfer('draft').then(() => {
      draftScheduled = false;
      if (pdfNeedsDraft) schedulePdfDraft();
    }, error => { draftScheduled = false; report(error); });
  });
}
function transfer(purpose: string, request = '', copyName?: string) {
  const operation = transfers.catch(() => {}).then(async () => {
    if (!handle) return;
    handle.command?.('commit');
    post({ type: 'state', state: handle.getState() });
    if (purpose === 'draft' && (!config.pdf || !pdfNeedsDraft)) {
      post({ type: 'checkpoint', request });
      return;
    }
    const seq = sequence;
    const bytes = await handle.save();
    const ack = Promise.withResolvers<void>();
    acknowledgements.set(`${purpose}:${seq}`, ack);
    post({ type: 'transfer', purpose, seq, request, name: copyName });
    // ArrayBuffer messaging avoids Base64 expansion/copying across the native boundary.
    const buffer = bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength
      ? bytes.buffer : bytes.slice().buffer;
    window.NotePitNative?.postMessage(buffer as ArrayBuffer);
    if (!window.NotePitNative) {
      acknowledgements.delete(`${purpose}:${seq}`);
      if (purpose === 'save') { handle.markSaved(); recoveredDirty = false; }
      pdfNeedsDraft = sequence > seq;
      return;
    }
    await ack.promise;
  });
  transfers = operation;
  return operation;
}

window.NotePit = {
  async open(value) {
    handle?.destroy(); handle = undefined; host.replaceChildren();
    config = value; sequence = value.sequence;
    recoveredDirty = value.pdf && value.recoveredDirty;
    pdfNeedsDraft = false; dirty = value.recoveredDirty;
    theme(value.dark);
    const options = {
      name: value.name, readOnly: value.readOnly, dark: value.dark, state: value.state, journal: value.journal,
      onDirty(changed: boolean) { dirty = changed || recoveredDirty; post({ type: 'dirty', dirty }); },
      onState(state: unknown) { post({ type: 'state', state }); },
      onJournal(data: unknown) {
        sequence++;
        post({ type: 'journal', seq: sequence, data });
        if (value.pdf) { pdfNeedsDraft = true; schedulePdfDraft(); }
      },
    };
    if (value.pdf) handle = await openPdf(host, { ...options, url: value.url });
    else {
      const response = await fetch(value.url);
      if (!response.ok) throw new Error('The document is temporarily unavailable. Its recovery files are retained.');
      handle = await openEditor(host, { ...options, bytes: new Uint8Array(await response.arrayBuffer()) });
    }
    post({ type: 'ready' });
    post({ type: 'dirty', dirty });
  },
  requestSave(copyName) { void transfer(copyName ? 'copy' : 'save', '', copyName ?? undefined).catch(report); },
  flushDraft(request = '') { void transfer('draft', request).catch(error => { report(error); post({ type: 'checkpoint', request, error: error instanceof Error ? error.message : String(error) }); }); },
  acknowledge(purpose, seq, success, error) {
    const ack = acknowledgements.get(`${purpose}:${seq}`);
    acknowledgements.delete(`${purpose}:${seq}`);
    if (!success) { ack?.reject(new Error(error || 'Save failed. Edits are retained.')); return; }
    if (purpose === 'save') {
      recoveredDirty = false;
      handle?.markSaved();
    }
    if (purpose === 'save' || purpose === 'draft') pdfNeedsDraft = sequence > seq;
    ack?.resolve();
  },
  command(command) { handle?.command?.(command); },
  setTheme(dark) { theme(dark); config.dark = dark; handle?.command?.(`theme:${dark ? 'dark' : 'light'}`); },
};

window.addEventListener('visibilitychange', () => { if (document.hidden) window.NotePit.flushDraft(); });
window.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); window.NotePit.requestSave(); }
});
const session = new URLSearchParams(location.search).get('session');
if (session && window.NotePitNative) {
  fetch(`/session/${encodeURIComponent(session)}/config`).then(async response => {
    if (!response.ok) throw new Error('Unable to recover this document session.');
    await window.NotePit.open(await response.json());
  }).catch(report);
} else host.textContent = 'Open a document in the Android app.';
