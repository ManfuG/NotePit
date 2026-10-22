import { basicSetup } from 'codemirror';
import { Compartment, EditorSelection, EditorState, StateEffect, StateField } from '@codemirror/state';
import type { Extension } from '@codemirror/state';
import { Decoration, EditorView, keymap } from '@codemirror/view';
import type { DecorationSet } from '@codemirror/view';
import { HighlightStyle, StreamLanguage, indentUnit, syntaxHighlighting } from '@codemirror/language';
import type { Language } from '@codemirror/language';
import { indentLess, indentMore, indentWithTab, redo, redoDepth, selectAll, toggleComment, undo, undoDepth } from '@codemirror/commands';
import { openSearchPanel, search } from '@codemirror/search';
import { tags } from '@lezer/highlight';
import { python } from '@codemirror/lang-python';
import { r } from '@codemirror/legacy-modes/mode/r';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { cpp } from '@codemirror/lang-cpp';
import { stex } from '@codemirror/legacy-modes/mode/stex';
import { markdown } from '@codemirror/lang-markdown';
import { javascript } from '@codemirror/lang-javascript';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { json } from '@codemirror/lang-json';
import { yaml } from '@codemirror/lang-yaml';
import type { DocumentHandle, DocumentOptions } from './document';
import { createEndingsField, decodeText, encodeText, endingsHistory, inferIndentation, journalEntry, replayJournal } from './editor-document';
import type { Snapshot } from './editor-document';
import { renderPreview } from './preview';
import type { PreviewKind } from './preview';
import './editor.css';

type LanguageKind = 'text' | 'python' | 'r' | 'cpp' | 'latex' | 'markdown' | 'javascript' | 'typescript' | 'html' | 'css' | 'json' | 'yaml' | 'bash';
type ViewState = { version: 1; selection: unknown; scrollTop: number; scrollLeft: number; preview: boolean; previewScrollTop: number };
type TouchAction = { label: string; before: string; after?: string; title?: string; cursor?: number };

type SearchRange = { from: number; to: number };
type RevealedSearch = { query: string; semantic: boolean; ranges: SearchRange[]; active: number; message?: string };
const revealSearch = StateEffect.define<RevealedSearch | null>();
const activateSearch = StateEffect.define<number>();

function literalRanges(text: string, query: string): SearchRange[] {
  if (!query) return [];
  const expression = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'giu');
  return Array.from(text.matchAll(expression), match => ({ from: match.index!, to: match.index! + match[0].length }));
}

function searchDecorations(search: RevealedSearch | null): DecorationSet {
  return Decoration.set(search?.ranges.filter(range => range.to > range.from).map((range, index) => Decoration.mark({
    class: `cm-revealed-match${search.semantic ? ' cm-revealed-passage' : ''}${index === search.active ? ' cm-revealed-active' : ''}`,
  }).range(range.from, range.to)) ?? [], true);
}

// View-only effects never enter history or the byte/newline journal.
const revealedSearchField = StateField.define<{ search: RevealedSearch | null; decorations: DecorationSet }>({
  create: () => ({ search: null, decorations: Decoration.none }),
  update(value, transaction) {
    let search = value.search;
    if (search && transaction.docChanged) {
      const anchor = transaction.changes.mapPos(search.ranges[search.active]?.from ?? 0);
      const ranges = search.semantic
        ? search.ranges.map(range => ({ from: transaction.changes.mapPos(range.from, 1), to: transaction.changes.mapPos(range.to, -1) })).filter(range => range.to >= range.from)
        : literalRanges(transaction.newDoc.toString(), search.query);
      let active = ranges.findIndex(range => range.from >= anchor);
      if (active < 0) active = Math.max(0, ranges.length - 1);
      search = { ...search, ranges, active };
    }
    for (const effect of transaction.effects) {
      if (effect.is(revealSearch)) search = effect.value;
      if (effect.is(activateSearch) && search) search = { ...search, active: effect.value };
    }
    return search === value.search ? value : { search, decorations: searchDecorations(search) };
  },
  provide: field => EditorView.decorations.from(field, value => value.decorations),
});

function languageKind(name: string): LanguageKind {
  const extension = name.toLowerCase().split('.').pop()!;
  switch (extension) {
    case 'py': case 'pyw': case 'pyi': case 'python': return 'python';
    case 'r': case 'rprofile': case 'rhistory': return 'r';
    case 'c': case 'h': case 'cc': case 'cpp': case 'cxx': case 'hpp': case 'hxx': case 'ino': case 'c++': case 'h++': return 'cpp';
    case 'tex': case 'latex': case 'sty': case 'cls': case 'ltx': return 'latex';
    case 'md': case 'markdown': case 'mdown': return 'markdown';
    case 'js': case 'mjs': case 'cjs': case 'jsx': case 'javascript': return 'javascript';
    case 'ts': case 'mts': case 'cts': case 'tsx': case 'typescript': return 'typescript';
    case 'html': case 'htm': case 'xhtml': return 'html';
    case 'css': return 'css';
    case 'json': return 'json';
    case 'yaml': case 'yml': return 'yaml';
    case 'sh': case 'bash': case 'zsh': case 'bashrc': case 'zshrc': case 'bash_profile': case 'bash_login': case 'profile': return 'bash';
    default: return 'text';
  }
}

function languageExtension(kind: LanguageKind, name: string): Extension {
  switch (kind) {
    case 'python': return python();
    case 'r': return StreamLanguage.define(r);
    case 'cpp': return cpp();
    case 'latex': return StreamLanguage.define(stex);
    case 'javascript': case 'typescript': return javascript({ typescript: kind === 'typescript', jsx: /\.[jt]sx$/i.test(name) || /^[jt]sx$/i.test(name) });
    case 'html': return html();
    case 'css': return css();
    case 'json': return json();
    case 'yaml': return yaml();
    case 'bash': return StreamLanguage.define(shell);
    case 'markdown': {
      const languages = new Map<string, Language>();
      return markdown({
        codeLanguages(info) {
          const name = info.trim().split(/\s+/)[0].toLowerCase();
          if (languages.has(name)) return languages.get(name)!;
          let language: Language;
          switch (languageKind(name)) {
            case 'python': language = python().language; break;
            case 'r': language = StreamLanguage.define(r); break;
            case 'cpp': language = cpp().language; break;
            case 'latex': language = StreamLanguage.define(stex); break;
            case 'javascript': case 'typescript': language = javascript({ typescript: languageKind(name) === 'typescript', jsx: name === 'jsx' || name === 'tsx' }).language; break;
            case 'html': language = html().language; break;
            case 'css': language = css().language; break;
            case 'json': language = json().language; break;
            case 'yaml': language = yaml().language; break;
            case 'bash': language = StreamLanguage.define(shell); break;
            default: return null;
          }
          languages.set(name, language);
          return language;
        },
      });
    }
    default: return [];
  }
}

function editorTheme(dark: boolean): Extension {
  return [
    EditorView.theme({
      '&': { height: '100%', fontSize: '14px', color: 'var(--editor-text)', backgroundColor: 'var(--editor-background)' },
      '.cm-scroller': { overflow: 'auto', fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace', lineHeight: '1.6' },
      '.cm-content': { padding: '12px 0', caretColor: 'var(--editor-primary)' },
      '.cm-line': { padding: '0 12px' },
      '.cm-gutters': { color: 'var(--editor-muted)', backgroundColor: 'var(--editor-surface)', border: 'none' },
      '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: dark ? '#242D47' : '#EDF0FF' },
      '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--editor-primary)' },
      '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': { backgroundColor: dark ? '#3D4B78' : '#CED8FF' },
      '.cm-panels, .cm-tooltip': { color: 'var(--editor-text)', backgroundColor: 'var(--editor-surface)', borderColor: 'var(--editor-border)' },
      '.cm-search': { padding: '6px', fontFamily: 'system-ui, sans-serif' },
      '.cm-search input': { maxWidth: 'min(190px, 43vw)' },
      '.cm-panels': { maxHeight: '48%', overflow: 'auto' },
      '.cm-searchMatch': { backgroundColor: dark ? '#665322' : '#FFECB0' },
      '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: dark ? '#7A472E' : '#FFD0AD' },
      '&.cm-focused': { outline: 'none' },
    }, { dark }),
    syntaxHighlighting(HighlightStyle.define([
      { tag: [tags.keyword, tags.modifier], color: dark ? '#CFB8FF' : '#7042A1' },
      { tag: [tags.string, tags.special(tags.string)], color: dark ? '#9CD9B6' : '#206348' },
      { tag: [tags.number, tags.bool, tags.null], color: dark ? '#F4C589' : '#8A4D00' },
      { tag: [tags.comment, tags.meta], color: dark ? '#A4ADC5' : '#626B81', fontStyle: 'italic' },
      { tag: [tags.function(tags.variableName), tags.propertyName, tags.tagName, tags.attributeName], color: dark ? '#B9C4FF' : '#3157D5' },
      { tag: [tags.operator, tags.punctuation], color: dark ? '#D3DAF0' : '#3D4660' },
      { tag: tags.heading, color: dark ? '#B9C4FF' : '#3157D5', fontWeight: 'bold' },
      { tag: tags.emphasis, fontStyle: 'italic' },
      { tag: tags.strong, fontWeight: 'bold' },
      { tag: tags.link, color: dark ? '#FFB0CE' : '#A13E67', textDecoration: 'underline' },
      { tag: tags.invalid, color: dark ? '#FFB4AB' : '#BA1A1A' },
    ])),
  ];
}

const paired = (label: string, before: string, after: string): TouchAction => ({ label, before, after });
const touchActions: Record<LanguageKind, TouchAction[]> = {
  text: [paired('( )', '(', ')'), paired('[ ]', '[', ']'), paired('" "', '"', '"')],
  markdown: [{ label: '#', before: '# ' }, { label: '•', before: '- ', title: 'List item' }, paired('Bold', '**', '**'), paired('Code', '`', '`'), paired('$ $', '$', '$'), paired('Link', '[', ']()')],
  latex: [{ label: '\\', before: '\\' }, paired('{ }', '{', '}'), paired('$ $', '$', '$'), { label: '^', before: '^' }, { label: '_', before: '_' }, { label: 'a/b', before: '\\frac{}{}', cursor: 6, title: 'Fraction' }, { label: '∑', before: '\\sum' }],
  python: [{ label: ':', before: ':' }, paired('( )', '(', ')'), paired('[ ]', '[', ']'), paired('{ }', '{', '}'), { label: '=', before: '=' }, { label: '#', before: '# ' }, { label: '_', before: '_' }],
  r: [{ label: '←', before: ' <- ', title: 'Assignment' }, paired('( )', '(', ')'), paired('[ ]', '[', ']'), { label: '$', before: '$' }, { label: '|>', before: ' |> ' }, { label: '#', before: '# ' }],
  cpp: [paired('{ }', '{', '}'), paired('( )', '(', ')'), paired('[ ]', '[', ']'), { label: ';', before: ';' }, { label: '#', before: '#' }, { label: '&', before: '&' }, { label: '*', before: '*' }],
  javascript: [paired('{ }', '{', '}'), paired('( )', '(', ')'), paired('[ ]', '[', ']'), { label: '=>', before: ' => ' }, { label: ';', before: ';' }, paired('` `', '`', '`')],
  typescript: [paired('{ }', '{', '}'), paired('( )', '(', ')'), paired('[ ]', '[', ']'), { label: ':', before: ': ' }, { label: '=>', before: ' => ' }, { label: ';', before: ';' }, paired('< >', '<', '>')],
  html: [paired('< >', '<', '>'), { label: '</>', before: '</', after: '>' }, paired('" "', '"', '"'), { label: '=', before: '=' }],
  css: [paired('{ }', '{', '}'), { label: ':', before: ': ' }, { label: ';', before: ';' }, { label: '#', before: '#' }, { label: '.', before: '.' }, paired('( )', '(', ')')],
  json: [paired('{ }', '{', '}'), paired('[ ]', '[', ']'), paired('" "', '"', '"'), { label: ':', before: ': ' }, { label: ',', before: ',' }],
  yaml: [{ label: ':', before: ': ' }, { label: '−', before: '- ', title: 'List item' }, paired('[ ]', '[', ']'), paired('{ }', '{', '}'), { label: '#', before: '# ' }],
  bash: [{ label: '$', before: '$' }, paired('${ }', '${', '}'), { label: '|', before: ' | ' }, { label: '&&', before: ' && ' }, { label: '\\', before: '\\' }, { label: '#', before: '# ' }],
};

export async function openEditor(host: HTMLElement, options: DocumentOptions): Promise<DocumentHandle> {
  if (!options.bytes) throw new Error('The text editor requires the original document bytes.');
  const bytes = options.bytes;
  const { snapshot: original, encoding, preferred } = decodeText(bytes);
  const indentation = inferIndentation(original.doc);
  const endingsField = createEndingsField(original.endings, preferred);
  const theme = new Compartment();
  const kind = languageKind(options.name);
  const previewKind: PreviewKind | undefined = kind === 'markdown' || kind === 'latex' ? kind : undefined;
  const savedState = options.state && typeof options.state === 'object' ? options.state as Partial<ViewState> : {};
  let dark = options.dark;
  let previewVisible = !!previewKind && savedState.preview === true;
  let editorScrollTop = Number.isFinite(savedState.scrollTop) ? Math.max(0, savedState.scrollTop!) : 0;
  let editorScrollLeft = Number.isFinite(savedState.scrollLeft) ? Math.max(0, savedState.scrollLeft!) : 0;
  let previewScrollTop = Number.isFinite(savedState.previewScrollTop) ? Math.max(0, savedState.previewScrollTop!) : 0;
  let previewStale = true;
  let previewTimer: number | undefined;
  let stateTimer: number | undefined;
  let destroyed = false;
  let baseline = original;
  let serialized: Snapshot | undefined;
  let dirty = false;
  let view: EditorView;

  const pane = document.createElement('section');
  pane.className = 'text-editor';
  pane.dataset.theme = dark ? 'dark' : 'light';
  pane.setAttribute('aria-label', 'Text editor');
  const surface = document.createElement('div');
  surface.className = 'text-editor-surface';
  const editorSurface = document.createElement('div');
  editorSurface.className = 'text-editor-code';
  const preview = document.createElement('article');
  preview.className = 'text-editor-preview';
  preview.setAttribute('aria-label', previewKind === 'latex' ? 'LaTeX formula preview' : 'Markdown preview');
  preview.tabIndex = 0;
  surface.append(editorSurface, preview);
  const toolbar = document.createElement('div');
  toolbar.className = 'text-editor-toolbar';
  toolbar.setAttribute('role', 'toolbar');
  toolbar.setAttribute('aria-label', 'Editor tools');
  pane.append(surface, toolbar);

  const searchBar = document.createElement('div');
  searchBar.className = 'text-editor-reveal';
  searchBar.hidden = true;
  searchBar.setAttribute('role', 'region');
  searchBar.setAttribute('aria-label', 'Search result');
  const searchLabel = document.createElement('span');
  searchLabel.setAttribute('role', 'status');
  searchBar.append(searchLabel);
  const searchButton = (label: string, action: () => void) => {
    const control = document.createElement('button');
    control.type = 'button';
    control.textContent = label;
    control.addEventListener('click', action);
    searchBar.append(control);
    return control;
  };
  const activate = (index: number) => {
    const search = view.state.field(revealedSearchField).search;
    if (!search?.ranges.length) return;
    const active = (index + search.ranges.length) % search.ranges.length;
    view.dispatch({ effects: [activateSearch.of(active), EditorView.scrollIntoView(search.ranges[active].from, { y: 'center' })] });
  };
  const previousMatch = searchButton('Previous', () => activate((view.state.field(revealedSearchField).search?.active ?? 0) - 1));
  const nextMatch = searchButton('Next', () => activate((view.state.field(revealedSearchField).search?.active ?? 0) + 1));
  searchButton('Close', () => view.dispatch({ effects: revealSearch.of(null) }));
  pane.prepend(searchBar);
  const updateSearchBar = () => {
    const search = view.state.field(revealedSearchField).search;
    searchBar.hidden = !search;
    if (!search) return;
    searchLabel.textContent = search.message ?? (search.semantic
      ? 'Related passage · not a literal query match'
      : search.ranges.length ? `Exact text · ${search.active + 1} of ${search.ranges.length}` : 'No literal matches in the current document');
    previousMatch.disabled = nextMatch.disabled = search.semantic || !search.ranges.length;
  };

  const getState = (): ViewState => {
    if (previewVisible) previewScrollTop = preview.scrollTop;
    else {
      editorScrollTop = view.scrollDOM.scrollTop;
      editorScrollLeft = view.scrollDOM.scrollLeft;
    }
    return {
      version: 1, selection: view.state.selection.toJSON(), scrollTop: editorScrollTop,
      scrollLeft: editorScrollLeft, preview: previewVisible, previewScrollTop,
    };
  };
  const scheduleState = () => {
    if (destroyed || stateTimer !== undefined) return;
    stateTimer = window.setTimeout(() => {
      stateTimer = undefined;
      if (!destroyed) options.onState(getState());
    }, 150);
  };
  const refreshPreview = () => {
    if (!previewKind || !previewVisible || !previewStale) return;
    const scroll = preview.scrollTop;
    renderPreview(preview, view.state.doc.toString(), previewKind);
    preview.scrollTop = scroll;
    previewStale = false;
  };
  const setPreview = (visible: boolean) => {
    const next = !!previewKind && visible;
    const changed = next !== previewVisible;
    if (changed) getState();
    previewVisible = next;
    editorSurface.hidden = previewVisible;
    preview.hidden = !previewVisible;
    previewButton?.setAttribute('aria-pressed', String(previewVisible));
    if (previewVisible) {
      refreshPreview();
      if (changed) preview.scrollTop = previewScrollTop;
      view.contentDOM.blur();
    } else if (changed) view.requestMeasure({
      read: () => ({ top: editorScrollTop, left: editorScrollLeft }),
      write(scroll) {
        view.scrollDOM.scrollTop = scroll.top;
        view.scrollDOM.scrollLeft = scroll.left;
      },
    });
    scheduleState();
  };
  const insert = ({ before, after, cursor }: TouchAction) => {
    if (options.readOnly) return;
    setPreview(false);
    view.focus();
    view.dispatch({
      ...view.state.changeByRange(range => ({
        changes: { from: range.from, to: range.to, insert: before + (after === undefined ? '' : view.state.sliceDoc(range.from, range.to) + after) },
        range: range.empty || after === undefined
          ? EditorSelection.cursor(range.from + (cursor ?? before.length))
          : EditorSelection.range(range.from + before.length, range.to + before.length),
      })),
      userEvent: 'input', scrollIntoView: true,
    });
  };
  const runCommand = (command: string) => {
    if (command === 'theme:dark' || command === 'theme:light') {
      dark = command === 'theme:dark';
      pane.dataset.theme = dark ? 'dark' : 'light';
      view.dispatch({ effects: theme.reconfigure(editorTheme(dark)) });
      return;
    }
    if (command === 'preview') {
      setPreview(!previewVisible);
      return;
    }
    if (command.startsWith('insert:')) {
      insert({ label: '', before: command.slice(7) });
      return;
    }
    setPreview(false);
    if (command !== 'find' && command !== 'replace') view.focus();
    switch (command) {
      case 'find': case 'replace': openSearchPanel(view); break;
      case 'selectAll': selectAll(view); break;
      case 'undo': if (!options.readOnly) undo(view); break;
      case 'redo': if (!options.readOnly) redo(view); break;
      case 'indent': if (!options.readOnly) indentMore(view); break;
      case 'outdent': if (!options.readOnly) indentLess(view); break;
      case 'comment': if (!options.readOnly) toggleComment(view); break;
    }
  };
  const addButton = (label: string, title: string, action: () => void, mutates = false) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.title = title;
    button.setAttribute('aria-label', title);
    button.disabled = mutates && options.readOnly;
    button.addEventListener('pointerdown', event => event.preventDefault());
    button.addEventListener('click', action);
    toolbar.append(button);
    return button;
  };
  const undoButton = addButton('Undo', 'Undo', () => runCommand('undo'), true);
  const redoButton = addButton('Redo', 'Redo', () => runCommand('redo'), true);
  addButton('Find', options.readOnly ? 'Find' : 'Find and replace', () => runCommand('find'));
  const previewButton = previewKind ? addButton('Preview', previewKind === 'latex' ? 'Toggle formula preview' : 'Toggle Markdown preview', () => runCommand('preview')) : undefined;
  addButton('Tab', 'Indent selection', () => runCommand('indent'), true);
  addButton('⇤', 'Outdent selection', () => runCommand('outdent'), true);
  for (const action of touchActions[kind]) addButton(action.label, action.title ?? `Insert ${action.label}`, () => insert(action), true);

  const updateStatus = () => {
    const currentDirty = !baseline.doc.eq(view.state.doc) || !baseline.endings.eq(view.state.field(endingsField).text);
    if (dirty !== currentDirty) {
      dirty = currentDirty;
      options.onDirty(dirty);
    }
    undoButton.disabled = options.readOnly || undoDepth(view.state) === 0;
    redoButton.disabled = options.readOnly || redoDepth(view.state) === 0;
  };
  let state = EditorState.create({
    doc: original.doc,
    extensions: [
      basicSetup,
      theme.of(editorTheme(dark)),
      languageExtension(kind, options.name),
      endingsField,
      endingsHistory(endingsField),
      revealedSearchField,
      indentUnit.of(indentation.unit),
      EditorState.tabSize.of(indentation.size),
      keymap.of([indentWithTab]),
      search({ top: true }),
      EditorState.readOnly.of(options.readOnly),
      EditorView.editable.of(!options.readOnly),
      EditorView.contentAttributes.of({ 'aria-label': options.name, spellcheck: 'false', autocapitalize: 'off', autocorrect: 'off' }),
      EditorView.updateListener.of(update => {
        if (update.startState.field(revealedSearchField) !== update.state.field(revealedSearchField)) updateSearchBar();
        if (update.docChanged) {
          for (const transaction of update.transactions) {
            if (transaction.docChanged) options.onJournal?.(journalEntry(transaction, endingsField));
          }
          updateStatus();
          previewStale = true;
          if (previewVisible) {
            window.clearTimeout(previewTimer);
            previewTimer = window.setTimeout(refreshPreview, 180);
          }
        }
        if (update.docChanged || update.selectionSet || update.viewportChanged) scheduleState();
      }),
    ],
  });
  // Replay before creating the view: recovery must not journal itself or populate undo history.
  if (options.journal?.length) state = replayJournal(state, endingsField, options.journal);
  if (savedState.selection) {
    try {
      const selection = EditorSelection.fromJSON(savedState.selection);
      state = state.update({ selection: EditorSelection.create(selection.ranges.map(range => EditorSelection.range(
        Math.min(range.anchor, state.doc.length), Math.min(range.head, state.doc.length),
      )), selection.mainIndex) }).state;
    } catch { /* A stale view selection must not prevent opening intact document bytes. */ }
  }
  view = new EditorView({ parent: editorSurface, state });
  host.append(pane);
  view.scrollDOM.addEventListener('scroll', scheduleState, { passive: true });
  preview.addEventListener('scroll', scheduleState, { passive: true });
  preview.addEventListener('click', event => {
    const link = event.target instanceof Element ? event.target.closest('a') : null;
    if (!link) return;
    event.preventDefault();
    const href = link.getAttribute('href');
    if (href?.startsWith('#')) {
      try {
        const id = decodeURIComponent(href.slice(1));
        for (const target of preview.querySelectorAll<HTMLElement>('[id]')) if (target.id === id) target.scrollIntoView({ block: 'start' });
      } catch { /* Invalid local anchors do not navigate away from the document. */ }
    }
  });
  setPreview(previewVisible);
  updateStatus();
  const restoreFrame = window.requestAnimationFrame(() => {
    if (destroyed) return;
    if (previewVisible) preview.scrollTop = previewScrollTop;
    else {
      view.scrollDOM.scrollTop = editorScrollTop;
      view.scrollDOM.scrollLeft = editorScrollLeft;
    }
  });

  return {
    async save() {
      const snapshot = { doc: view.state.doc, endings: view.state.field(endingsField).text };
      const result = snapshot.doc.eq(original.doc) && snapshot.endings.eq(original.endings) ? bytes : encodeText(snapshot, encoding);
      // Only markSaved advances the baseline, and only to these exact serialized bytes.
      serialized = snapshot;
      return result;
    },
    markSaved() {
      if (!serialized) return;
      baseline = serialized;
      serialized = undefined;
      updateStatus();
    },
    getState,
    command: runCommand,
    reveal(location) {
      if (destroyed) return;
      window.cancelAnimationFrame(restoreFrame);
      setPreview(false);
      const doc = view.state.doc;
      if (!Number.isInteger(location.line) || location.line < 1 || location.line > doc.lines ||
          !Number.isInteger(location.endLine) || location.endLine < location.line || location.endLine > doc.lines) {
        view.dispatch({ effects: revealSearch.of({ query: location.query, semantic: location.semantic, ranges: [], active: 0,
          message: 'Search result changed · the indexed source lines are no longer available' }) });
        return;
      }
      const line = location.line;
      const endLine = location.endLine;
      const from = doc.line(line).from;
      const to = doc.line(endLine).to;
      const source = doc.sliceString(from, to);
      const passageOffset = location.text ? source.indexOf(location.text.replace(/\r\n?/g, '\n')) : -1;
      let search: RevealedSearch;
      if (location.semantic) {
        search = passageOffset < 0
          ? { query: location.query, semantic: true, ranges: [], active: 0, message: 'Related result · source passage changed or could not be located' }
          : { query: location.query, semantic: true, ranges: [{ from: from + passageOffset, to: from + passageOffset + location.text.replace(/\r\n?/g, '\n').length }], active: 0 };
      } else {
        const ranges = literalRanges(doc.toString(), location.query);
        const passageFrom = passageOffset < 0 ? from : from + passageOffset;
        const passageTo = passageOffset < 0 ? to : passageFrom + location.text.replace(/\r\n?/g, '\n').length;
        const active = ranges.findIndex(range => range.from >= passageFrom && range.to <= passageTo);
        const verified = passageOffset >= 0 && active >= 0;
        search = { query: location.query, semantic: false, ranges: verified ? ranges : [], active: Math.max(0, active),
          ...(!verified ? { message: 'Search result changed · the indexed passage and literal match could not be verified' } : {}) };
      }
      view.dispatch({ effects: [revealSearch.of(search), EditorView.scrollIntoView(search.ranges[search.active]?.from ?? from, { y: 'center' })] });
      updateSearchBar();
    },
    destroy() {
      if (destroyed) return;
      options.onState(getState());
      destroyed = true;
      window.clearTimeout(stateTimer);
      window.clearTimeout(previewTimer);
      window.cancelAnimationFrame(restoreFrame);
      view.destroy();
      pane.remove();
    },
  };
}
