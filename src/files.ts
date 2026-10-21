import { Capacitor, registerPlugin } from '@capacitor/core';

export interface Entry {
  uri: string;
  name: string;
  directory: boolean;
  writable: boolean;
  size: number;
  modified: number;
}
interface FilesPlugin {
  roots(): Promise<{ entries: Entry[] }>;
  pickFolder(): Promise<{ entry: Entry }>;
  forget(options: { uri: string }): Promise<void>;
  list(options: { uri: string }): Promise<{ entries: Entry[] }>;
  create(options: { uri: string; name: string; directory: boolean }): Promise<{ entry: Entry }>;
  rename(options: { uri: string; name: string }): Promise<{ entry: Entry }>;
  remove(options: { uri: string }): Promise<void>;
  save(options: { uri: string; data: string; revision: string }): Promise<void>;
}
export const files = registerPlugin<FilesPlugin>('LocalFiles');
export const native = Capacitor.isNativePlatform();

export async function readFile(entry: Entry): Promise<Uint8Array> {
  const response = await fetch(Capacitor.convertFileSrc(entry.uri));
  if (!response.ok) throw new Error('Unable to read this file. Reconnect its folder if access was revoked.');
  return new Uint8Array(await response.arrayBuffer());
}
export async function fingerprint(bytes: Uint8Array): Promise<string> {
  const hash = await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>);
  return Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('');
}
export function toBase64(bytes: Uint8Array): string {
  const chunks: string[] = [];
  for (let i = 0; i < bytes.length; i += 32768) chunks.push(String.fromCharCode(...bytes.subarray(i, i + 32768)));
  return btoa(chunks.join(''));
}
export const isPdf = (name: string) => /\.pdf$/i.test(name);
export const isText = (name: string) => /\.(txt|md|markdown|tex|bib|sty|cls|py|r|rmd|c|h|cpp|hpp|cc|cxx|js|jsx|ts|tsx|json|csv|tsv|yaml|yml|toml|xml|html|css|scss|sh|sql|log|ini|cfg)$/i.test(name) || !name.includes('.');
