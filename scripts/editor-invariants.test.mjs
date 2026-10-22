import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';
import ts from 'typescript';

// Compile only the DOM-free persistence module; no browser, server, or extra test framework.
const require = createRequire(import.meta.url);
const compiled = ts.transpileModule(readFileSync(new URL('../src/editor-document.ts', import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
const module = { exports: {} };
new Function('require', 'module', 'exports', compiled)(require, module, module.exports);
const { createEndingsField, decodeText, encodeText, endingsHistory, inferIndentation, journalEntry, replayJournal } = module.exports;
const { ChangeSet, EditorState } = require('@codemirror/state');
const { history, undo, redo } = require('@codemirror/commands');

function sourceBytes(source, encoding) {
  if (encoding === 'utf8') return new Uint8Array(Buffer.from(source, 'utf8'));
  if (encoding === 'utf8-bom') return new Uint8Array(Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(source, 'utf8')]));
  const body = Buffer.from(source, 'utf16le');
  if (encoding === 'utf16be') body.swap16();
  return new Uint8Array(Buffer.concat([Buffer.from(encoding === 'utf16le' ? [0xff, 0xfe] : [0xfe, 0xff]), body]));
}

function fixture(source, encoding = 'utf8') {
  const bytes = sourceBytes(source, encoding);
  const decoded = decodeText(bytes);
  const field = createEndingsField(decoded.snapshot.endings, decoded.preferred);
  let state = EditorState.create({ doc: decoded.snapshot.doc, extensions: [history(), field, endingsHistory(field)] });
  const journal = [];
  const dispatch = transaction => {
    state = transaction.state;
    if (transaction.docChanged) journal.push(journalEntry(transaction, field));
  };
  const target = { get state() { return state; }, dispatch };
  return {
    bytes, decoded, field, journal, target,
    get state() { return state; },
    edit(spec) { dispatch(state.update(spec)); },
    bytesNow() { return encodeText({ doc: state.doc, endings: state.field(field).text }, encoding); },
    replay() {
      const fresh = EditorState.create({ doc: decoded.snapshot.doc, extensions: [history(), field, endingsHistory(field)] });
      return replayJournal(fresh, field, JSON.parse(JSON.stringify(journal)));
    },
  };
}

for (const encoding of ['utf8', 'utf8-bom', 'utf16le', 'utf16be']) {
  test(`${encoding}: BOM, Unicode and mixed newlines round-trip exactly`, () => {
    const leading = encoding === 'utf8' ? '' : '\ufeff';
    const file = fixture(`${leading}alpha\r\nβeta\rgamma\n𝛑\tend\r\n`, encoding);
    assert.deepEqual(encodeText(file.decoded.snapshot, file.decoded.encoding), file.bytes);
    const beta = file.state.doc.line(2);
    file.edit({ changes: { from: beta.from, to: beta.to, insert: 'BETA' } });
    const expected = fixture(`${leading}alpha\r\nBETA\rgamma\n𝛑\tend\r\n`, encoding);
    assert.deepEqual(file.bytesNow(), expected.bytes);
    assert.equal(undo(file.target), true);
    assert.deepEqual(file.bytesNow(), file.bytes);
    assert.equal(redo(file.target), true);
    const replayed = file.replay();
    assert.deepEqual(encodeText({ doc: replayed.doc, endings: replayed.field(file.field).text }, encoding), expected.bytes);
  });
}

test('deleting lines preserves surviving separators and undo restores removed mixed endings', () => {
  const file = fixture('alpha\r\nbeta\rgamma\ndelta');
  file.edit({ changes: { from: file.state.doc.line(2).from, to: file.state.doc.line(3).from, insert: '' } });
  assert.deepEqual(file.bytesNow(), new TextEncoder().encode('alpha\r\ngamma\ndelta'));
  undo(file.target);
  assert.deepEqual(file.bytesNow(), file.bytes);
  const replayed = file.replay();
  assert.equal(replayed.doc.eq(file.decoded.snapshot.doc), true);
  assert.equal(replayed.field(file.field).text.eq(file.decoded.snapshot.endings), true);
});

test('grouped undo/redo composes incremental newline journals without losing original markers', () => {
  const file = fixture('a\r\nb\rc\nd', 'utf16be');
  file.edit({ changes: { from: file.state.doc.length, insert: '\nx' }, userEvent: 'input.type' });
  file.edit({ changes: { from: file.state.doc.length, insert: '\ny' }, userEvent: 'input.type' });
  const changed = file.bytesNow();
  undo(file.target);
  assert.deepEqual(file.bytesNow(), file.bytes);
  redo(file.target);
  assert.deepEqual(file.bytesNow(), changed);
  const replayed = file.replay();
  assert.deepEqual(encodeText({ doc: replayed.doc, endings: replayed.field(file.field).text }, 'utf16be'), changed);
});

test('multi-cursor newline insertion preserves metadata and uses the source preferred separator', () => {
  const file = fixture('one\r\ntwo\r\nthree\nfour');
  file.edit({ changes: [{ from: 1, insert: '\n' }, { from: 5, insert: '\n' }] });
  assert.deepEqual(file.bytesNow(), new TextEncoder().encode('o\r\nne\r\nt\r\nwo\r\nthree\nfour'));
  const replayed = file.replay();
  assert.equal(replayed.field(file.field).text.eq(file.state.field(file.field).text), true);
  assert.equal(replayed.doc.eq(file.state.doc), true);
});

test('newline metadata journal is incremental rather than a document-sized snapshot', () => {
  const file = fixture('line\r\n'.repeat(10000));
  file.edit({ changes: { from: 2, insert: '\n' } });
  assert.ok(JSON.stringify(file.journal[0]).length < 200);
});

test('recovery does not populate undo history or mutate the serialized journal', () => {
  const file = fixture('first\r\nsecond');
  file.edit({ changes: { from: 0, insert: 'new\n' } });
  const serialized = JSON.stringify(file.journal);
  const restored = file.replay();
  assert.equal(JSON.stringify(file.journal), serialized);
  const target = { state: restored, dispatch() { throw new Error('Recovery unexpectedly created an undo event'); } };
  assert.equal(undo(target), false);
});

test('unsupported and malformed encodings are rejected, never decoded with replacement characters', () => {
  for (const bytes of [
    [0xc3, 0x28], [0xff, 0xfe, 0x61], [0xfe, 0xff, 0x61], [0xff, 0xfe, 0x00, 0xd8],
    [0xff, 0xfe, 0x00, 0x00, 0x61, 0x00, 0x00, 0x00], [0x61, 0x00, 0x62, 0x00],
  ]) assert.throws(() => decodeText(new Uint8Array(bytes)));
});

test('invalid recovery versions and mismatched change-set lengths are rejected', () => {
  const file = fixture('safe\r\ntext');
  assert.throws(() => replayJournal(file.state, file.field, [{ version: 9, changes: [], endings: [] }]));
  assert.throws(() => replayJournal(file.state, file.field, [{ version: 1, changes: [999], endings: [0] }]));
  assert.throws(() => replayJournal(file.state, file.field, [{
    version: 1,
    changes: ChangeSet.of({ from: 0, insert: '\n' }, file.state.doc.length).toJSON(),
    endings: ChangeSet.empty(file.decoded.snapshot.endings.length).toJSON(),
  }]));
});

test('indentation inference recognizes source tabs and common space widths without changing source', () => {
  for (const [source, unit] of [
    ['if ok:\n  one()\n  if other:\n    two()\n', '  '],
    ['if ok:\n    one()\n    if other:\n        two()\n', '    '],
    ['if ok:\n\tone()\n\tif other:\n\t\ttwo()\n', '\t'],
  ]) {
    const file = fixture(source);
    assert.equal(inferIndentation(file.state.doc).unit, unit);
    assert.deepEqual(file.bytesNow(), file.bytes);
  }
});
