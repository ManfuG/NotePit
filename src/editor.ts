import { basicSetup } from 'codemirror';
import { EditorState, StateEffect, StateField, Text } from '@codemirror/state';
import type { Extension } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { StreamLanguage } from '@codemirror/language';
import { indentLess, indentMore, indentWithTab, invertedEffects, redo, redoDepth, undo, undoDepth } from '@codemirror/commands';
import { openSearchPanel } from '@codemirror/search';
import { python } from '@codemirror/lang-python';
import { r } from '@codemirror/legacy-modes/mode/r';
import { cpp } from '@codemirror/lang-cpp';
import { stex } from '@codemirror/legacy-modes/mode/stex';
import { markdown } from '@codemirror/lang-markdown';
import { javascript } from '@codemirror/lang-javascript';
import { oneDark } from '@codemirror/theme-one-dark';
import './editor.css';

type Options = {
  name: string;
  bytes: Uint8Array;
  readOnly: boolean;
  onDirty: (dirty: boolean) => void;
};

type Snapshot = { doc: Text; endings: Text };

export async function openEditor(host: HTMLElement, options: Options): Promise<{
  save: () => Promise<Uint8Array>;
  markSaved: () => void;
  destroy: () => void;
}> {
  const bytes = options.bytes;
  if ((bytes[0] === 0xff && bytes[1] === 0xfe) || (bytes[0] === 0xfe && bytes[1] === 0xff)) {
    throw new Error('This file uses an unsupported encoding. Only UTF-8 text is supported.');
  }
  const bom = bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;
  let source: string;
  try {
    source = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bom ? bytes.subarray(3) : bytes);
  } catch {
    throw new Error('This file is not valid UTF-8 text. Opening it could corrupt its contents.');
  }
  if (/[\u0000-\u0008\u000b\u000e-\u001f\u007f]/.test(source)) {
    throw new Error('This appears to be a binary file and cannot be edited as text.');
  }

  // Keep separator metadata separate from CodeMirror's normalized document.
  // Text is persistent, so edits and undo do not copy a file-sized string.
  const lines: string[] = [];
  const separators: string[] = [];
  const counts = { n: 0, c: 0, r: 0 };
  const breaks = /\r\n|\r|\n/g;
  let start = 0;
  for (const match of source.matchAll(breaks)) {
    lines.push(source.slice(start, match.index));
    const marker = match[0] === '\r\n' ? 'c' : match[0] === '\r' ? 'r' : 'n';
    separators.push(marker);
    counts[marker]++;
    start = match.index + match[0].length;
  }
  lines.push(source.slice(start));
  separators.push('');
  const preferred = counts.c > counts.n && counts.c >= counts.r ? 'c' : counts.r > counts.n ? 'r' : 'n';
  const original: Snapshot = { doc: Text.of(lines), endings: Text.of(separators) };
  const restoreEndings = StateEffect.define<Text>();
  const endingsField = StateField.define<Text>({
    create: () => original.endings,
    update(endings, transaction) {
      let restored: Text | undefined;
      for (const effect of transaction.effects) {
        if (effect.is(restoreEndings)) restored = effect.value;
      }
      if (restored) return restored;
      if (!transaction.docChanged) return endings;
      const replacements: { from: number; to: number; inserted: number }[] = [];
      transaction.changes.iterChanges((from, to, _fromNew, _toNew, inserted) => {
        const first = transaction.startState.doc.lineAt(from).number;
        const last = transaction.startState.doc.lineAt(to).number;
        if (first !== last || inserted.lines > 1) {
          replacements.push({ from: endings.line(first).from, to: endings.line(last).from, inserted: inserted.lines - 1 });
        }
      });
      for (let index = replacements.length - 1; index >= 0; index--) {
        const replacement = replacements[index]!;
        const markers: string[] = Array<string>(replacement.inserted).fill(preferred);
        markers.push('');
        endings = endings.replace(replacement.from, replacement.to, Text.of(markers));
      }
      return endings;
    },
  });

  let language: Extension = [];
  const extension = options.name.split('.').pop()?.toLowerCase();
  switch (extension) {
    case 'py': case 'pyw': case 'pyi':
      language = python();
      break;
    case 'r': case 'rprofile': case 'rhistory':
      language = StreamLanguage.define(r);
      break;
    case 'c': case 'h': case 'cc': case 'cpp': case 'cxx': case 'hpp': case 'hxx': case 'ino':
      language = cpp();
      break;
    case 'tex': case 'latex': case 'sty': case 'cls': case 'ltx':
      language = StreamLanguage.define(stex);
      break;
    case 'md': case 'markdown': case 'mdown':
      language = markdown();
      break;
    case 'js': case 'mjs': case 'cjs': case 'jsx': case 'ts': case 'mts': case 'cts': case 'tsx':
      language = javascript({
        typescript: extension === 'ts' || extension === 'mts' || extension === 'cts' || extension === 'tsx',
        jsx: extension === 'jsx' || extension === 'tsx',
      });
      break;
  }

  const pane = document.createElement('section');
  pane.className = 'text-editor';
  pane.setAttribute('aria-label', 'Text editor');
  const toolbar = document.createElement('div');
  toolbar.className = 'text-editor-toolbar';
  toolbar.setAttribute('role', 'toolbar');
  toolbar.setAttribute('aria-label', 'Editor tools');
  const surface = document.createElement('div');
  surface.className = 'text-editor-surface';
  pane.append(toolbar, surface);
  let view: EditorView;
  const undoButton = document.createElement('button');
  const redoButton = document.createElement('button');
  const findButton = document.createElement('button');
  const indentButton = document.createElement('button');
  const outdentButton = document.createElement('button');
  const commands = [
    { button: undoButton, label: 'Undo', command: undo },
    { button: redoButton, label: 'Redo', command: redo },
    { button: findButton, label: 'Find', command: openSearchPanel },
    { button: indentButton, label: 'Indent', command: indentMore },
    { button: outdentButton, label: 'Outdent', command: indentLess },
  ];
  for (const { button, label, command } of commands) {
    button.type = 'button';
    button.textContent = label;
    button.addEventListener('mousedown', event => event.preventDefault());
    button.addEventListener('click', () => {
      // Search must retain focus in its own input, not the document.
      if (button !== findButton) view.focus();
      command(view);
    });
    toolbar.append(button);
  }
  indentButton.disabled = outdentButton.disabled = options.readOnly;

  let baseline = original;
  let serialized: Snapshot | undefined;
  let dirty = false;
  const updateStatus = () => {
    const currentDirty = !baseline.doc.eq(view.state.doc) || !baseline.endings.eq(view.state.field(endingsField));
    if (dirty !== currentDirty) {
      dirty = currentDirty;
      options.onDirty(dirty);
    }
    undoButton.disabled = options.readOnly || undoDepth(view.state) === 0;
    redoButton.disabled = options.readOnly || redoDepth(view.state) === 0;
  };
  view = new EditorView({
    parent: surface,
    state: EditorState.create({
      doc: original.doc,
      extensions: [
        basicSetup,
        oneDark,
        language,
        endingsField,
        invertedEffects.of(transaction => transaction.docChanged
          ? [restoreEndings.of(transaction.startState.field(endingsField))] : []),
        keymap.of([indentWithTab]),
        EditorState.readOnly.of(options.readOnly),
        EditorView.editable.of(!options.readOnly),
        EditorView.contentAttributes.of({ 'aria-label': options.name, spellcheck: 'false', autocapitalize: 'off', autocorrect: 'off' }),
        EditorView.updateListener.of(update => {
          if (update.docChanged) updateStatus();
        }),
        EditorView.theme({
          '&': { height: '100%', fontSize: '14px' },
          '.cm-scroller': { overflow: 'auto', fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace', lineHeight: '1.6' },
          '.cm-content': { padding: '12px 0' },
          '.cm-line': { padding: '0 12px' },
          '.cm-gutters': { backgroundColor: 'var(--surface, #19231e)', border: 'none' },
          '.cm-search': { padding: '8px', fontFamily: 'system-ui, sans-serif' },
          '.cm-search input': { maxWidth: 'min(170px, 42vw)' },
          '.cm-panels': { maxHeight: '45%', overflow: 'auto' },
          '&.cm-focused': { outline: 'none' },
        }),
      ],
    }),
  });
  host.append(pane);
  updateStatus();

  return {
    async save() {
      const snapshot = { doc: view.state.doc, endings: view.state.field(endingsField) };
      let result: Uint8Array;
      if (snapshot.doc.eq(original.doc) && snapshot.endings.eq(original.endings)) {
        result = bytes;
      } else {
        const content: string[] = bom ? ['\ufeff'] : [];
        const documentLines = snapshot.doc.iterLines();
        const endingLines = snapshot.endings.iterLines();
        while (!documentLines.next().done) {
          const marker = endingLines.next().value;
          content.push(documentLines.value, marker === 'c' ? '\r\n' : marker === 'r' ? '\r' : marker === 'n' ? '\n' : '');
        }
        result = new TextEncoder().encode(content.join(''));
      }
      serialized = snapshot;
      return result;
    },
    markSaved() {
      if (!serialized) return;
      baseline = serialized;
      serialized = undefined;
      updateStatus();
    },
    destroy() {
      view.destroy();
      pane.remove();
    },
  };
}
