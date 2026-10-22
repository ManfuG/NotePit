export interface SearchLocation {
  query: string;
  page: number;
  line: number;
  endLine: number;
  text: string;
  semantic: boolean;
  sourceChanged?: boolean;
}

export interface DocumentOptions {
  name: string;
  bytes?: Uint8Array;
  url?: string;
  readOnly: boolean;
  dark: boolean;
  state?: unknown;
  onDirty: (dirty: boolean) => void;
  onState: (state: unknown) => void;
  onJournal?: (entry: unknown) => void;
  journal?: unknown[];
}

export interface DocumentHandle {
  save(): Promise<Uint8Array>;
  markSaved(): void;
  destroy(): void;
  getState(): unknown;
  command?(command: string): void;
  reveal?(location: SearchLocation): void | Promise<void>;
}
