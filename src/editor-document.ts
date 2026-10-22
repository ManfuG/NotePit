import { ChangeSet, EditorState, StateEffect, StateField, Text, Transaction } from '@codemirror/state';
import type { ChangeSpec, Extension } from '@codemirror/state';
import { invertedEffects } from '@codemirror/commands';

export type Snapshot = { doc: Text; endings: Text };
export type Encoding = 'utf8' | 'utf8-bom' | 'utf16le' | 'utf16be';
export type Newline = 'n' | 'c' | 'r';
export type EndingsState = { text: Text; changes: ChangeSet };
export type EditorJournal = { version: 1; changes: unknown; endings: unknown };

const endingStrings = { n: '\n', c: '\r\n', r: '\r' };
const restoreEndings = StateEffect.define<ChangeSet>();

export function inferIndentation(doc: Text): { unit: string; size: number } {
  let tabs = 0;
  let spaces = 0;
  let previous = 0;
  const widths = new Map<number, number>();
  for (const line of doc.iterLines()) {
    if (!line.trim()) continue;
    const leading = /^[\t ]*/.exec(line)![0];
    if (leading.startsWith('\t')) tabs++;
    else if (leading.length) spaces++;
    if (leading.includes('\t')) continue;
    const width = leading.length;
    const difference = Math.abs(width - previous);
    if (difference > 0 && difference <= 8) widths.set(difference, (widths.get(difference) ?? 0) + 1);
    previous = width;
  }
  let size = 4;
  let frequency = 0;
  for (const [width, count] of widths) {
    if (count > frequency || (count === frequency && width < size)) {
      size = width;
      frequency = count;
    }
  }
  return { unit: tabs > spaces ? '\t' : ' '.repeat(size), size: tabs > spaces ? 4 : size };
}

export function decodeText(bytes: Uint8Array): { snapshot: Snapshot; encoding: Encoding; preferred: Newline } {
  let encoding: Encoding = 'utf8';
  let offset = 0;
  if (bytes[0] === 0xff && bytes[1] === 0xfe) {
    encoding = 'utf16le';
    offset = 2;
    if (bytes[2] === 0 && bytes[3] === 0) throw new Error('UTF-32 is not supported. Use UTF-8 or BOM-marked UTF-16.');
  } else if (bytes[0] === 0xfe && bytes[1] === 0xff) {
    encoding = 'utf16be';
    offset = 2;
  } else if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    encoding = 'utf8-bom';
    offset = 3;
  }
  let source: string;
  try {
    source = new TextDecoder(encoding === 'utf16le' ? 'utf-16le' : encoding === 'utf16be' ? 'utf-16be' : 'utf-8', {
      fatal: true, ignoreBOM: true,
    }).decode(bytes.subarray(offset));
  } catch {
    throw new Error('Unsupported or invalid text encoding. Use UTF-8 or BOM-marked UTF-16; the original file has not been changed.');
  }
  if (/[\u0000-\u0008\u000b\u000e-\u001f\u007f]/.test(source)) {
    throw new Error('This appears to be a binary or unsupported text file. The original file has not been changed.');
  }
  const lines: string[] = [];
  const separators: string[] = [];
  const counts = { n: 0, c: 0, r: 0 };
  let start = 0;
  for (const match of source.matchAll(/\r\n|\r|\n/g)) {
    lines.push(source.slice(start, match.index));
    const marker = match[0] === '\r\n' ? 'c' : match[0] === '\r' ? 'r' : 'n';
    separators.push(marker);
    counts[marker]++;
    start = match.index + match[0].length;
  }
  lines.push(source.slice(start));
  separators.push('');
  const preferred = counts.c > counts.n && counts.c >= counts.r ? 'c' : counts.r > counts.n ? 'r' : 'n';
  return { snapshot: { doc: Text.of(lines), endings: Text.of(separators) }, encoding, preferred };
}

export function encodeText(snapshot: Snapshot, encoding: Encoding): Uint8Array {
  const content: string[] = encoding === 'utf8-bom' ? ['\ufeff'] : [];
  const endings = snapshot.endings.iterLines();
  for (const line of snapshot.doc.iterLines()) {
    const marker = endings.next().value as Newline | '';
    content.push(line, marker ? endingStrings[marker] : '');
  }
  const source = content.join('');
  if (encoding === 'utf8' || encoding === 'utf8-bom') return new TextEncoder().encode(source);
  const bytes = new Uint8Array(2 + source.length * 2);
  const littleEndian = encoding === 'utf16le';
  bytes[0] = littleEndian ? 0xff : 0xfe;
  bytes[1] = littleEndian ? 0xfe : 0xff;
  const data = new DataView(bytes.buffer);
  for (let index = 0; index < source.length; index++) data.setUint16(2 + index * 2, source.charCodeAt(index), littleEndian);
  return bytes;
}

export function createEndingsField(original: Text, preferred: Newline): StateField<EndingsState> {
  return StateField.define<EndingsState>({
    create: () => ({ text: original, changes: ChangeSet.empty(original.length) }),
    update(value, transaction) {
      let restored: ChangeSet | undefined;
      for (const effect of transaction.effects) {
        if (effect.is(restoreEndings)) restored = restored ? restored.compose(effect.value) : effect.value;
      }
      if (restored) return { text: restored.apply(value.text), changes: restored };
      if (!transaction.docChanged) return value;
      const replacements: ChangeSpec[] = [];
      transaction.changes.iterChanges((from, to, _fromNew, _toNew, inserted) => {
        const first = transaction.startState.doc.lineAt(from).number;
        const last = transaction.startState.doc.lineAt(to).number;
        if (first !== last || inserted.lines > 1) {
          const markers: string[] = Array<string>(inserted.lines - 1).fill(preferred);
          markers.push('');
          replacements.push({ from: value.text.line(first).from, to: value.text.line(last).from, insert: Text.of(markers) });
        }
      });
      const changes = ChangeSet.of(replacements, value.text.length);
      return { text: changes.apply(value.text), changes };
    },
  });
}

export function endingsHistory(field: StateField<EndingsState>): Extension {
  return invertedEffects.of(transaction => {
    if (!transaction.docChanged) return [];
    const changes = transaction.state.field(field).changes;
    return changes.empty ? [] : [restoreEndings.of(changes.invert(transaction.startState.field(field).text))];
  });
}

export function journalEntry(transaction: Transaction, field: StateField<EndingsState>): EditorJournal {
  return { version: 1, changes: transaction.changes.toJSON(), endings: transaction.state.field(field).changes.toJSON() };
}

export function replayJournal(state: EditorState, field: StateField<EndingsState>, journal: readonly unknown[]): EditorState {
  try {
    for (const value of journal) {
      if (!value || typeof value !== 'object' || !('version' in value) || value.version !== 1 || !('changes' in value) || !('endings' in value)) {
        throw new Error('Invalid journal entry');
      }
      const changes = ChangeSet.fromJSON(value.changes);
      const endings = ChangeSet.fromJSON(value.endings);
      changes.iterChanges((_from, _to, _newFrom, _newTo, inserted) => {
        for (const line of inserted.iterLines()) if (line.includes('\r')) throw new Error('Invalid normalized journal text');
      });
      endings.iterChanges((_from, _to, _newFrom, _newTo, inserted) => {
        for (const line of inserted.iterLines()) if (!/^[ncr]?$/.test(line)) throw new Error('Invalid journal newline metadata');
      });
      state = state.update({ changes, effects: restoreEndings.of(endings), annotations: Transaction.addToHistory.of(false) }).state;
    }
    const endings = state.field(field).text;
    if (endings.lines !== state.doc.lines || endings.line(endings.lines).text !== '') throw new Error('Inconsistent journal newline count');
    let number = 0;
    for (const marker of endings.iterLines()) {
      if (++number < endings.lines && !/^[ncr]$/.test(marker)) throw new Error('Invalid journal newline marker');
    }
    return state;
  } catch {
    throw new Error('The recovered edit journal is invalid. The original file and recovery data have not been changed.');
  }
}
