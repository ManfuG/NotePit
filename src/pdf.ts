import './pdf.css';

interface AnnotationStorage {
  onSetModified: (() => void) | null;
  onResetModified: (() => void) | null;
}

interface PdfDocument {
  annotationStorage: AnnotationStorage;
  saveDocument(): Promise<Uint8Array>;
}

interface ViewerApplication {
  initializedPromise: Promise<void>;
  pdfLoadingTask: {
    onPassword: ((updatePassword: (password: string) => void, reason: number) => void) | null;
    destroy(): Promise<void>;
  } | null;
  pdfDocument: PdfDocument | null;
  pdfViewer: {
    firstPagePromise: Promise<unknown>;
    _layerProperties: {
      annotationEditorUIManager?: { endCurrentEditing(): void };
    };
  };
  pdfLinkService: { externalLinkEnabled: boolean };
  open(options: { data: Uint8Array; filename: string }): Promise<void>;
  close(): Promise<void>;
}

interface ViewerWindow extends Window {
  PDFViewerApplication: ViewerApplication;
  PDFViewerApplicationOptions: { setAll(options: Record<string, unknown>): void };
}

interface PdfOptions {
  name: string;
  bytes: Uint8Array;
  readOnly: boolean;
  onDirty: (dirty: boolean) => void;
}

/** Uses the locally packaged, unmodified PDF.js rendering/editor implementation. */
export async function openPdf(host: HTMLElement, options: PdfOptions): Promise<{
  save: () => Promise<Uint8Array>;
  markSaved: () => void;
  destroy: () => void;
}> {
  const frame = document.createElement('iframe');
  frame.className = 'pdf-frame';
  frame.title = `PDF reader: ${options.name}`;
  // No popups, downloads, forms, or top-level navigation from document links.
  frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
  frame.src = `${import.meta.env.BASE_URL}pdfjs/web/viewer.html?file=`;
  let application: ViewerApplication | undefined;
  let destroyed = false;
  let serializing = false;
  let changed = false;
  let generation = 0;
  let serializedGeneration = 0;
  let latestBytes = options.bytes;
  const events = new AbortController();
  let readyTimer: number | undefined;

  const destroy = () => {
    if (destroyed) return;
    destroyed = true;
    events.abort();
    clearTimeout(readyTimer);
    const storage = application?.pdfDocument?.annotationStorage;
    if (storage) {
      storage.onSetModified = null;
      storage.onResetModified = null;
    }
    // close() destroys the loading task, worker, page views and annotation editors.
    if (application) void application.close().catch(() => {}).finally(() => frame.remove());
    else frame.remove();
    frame.hidden = true;
  };

  try {
    const ready = Promise.withResolvers<ViewerApplication>();
    readyTimer = window.setTimeout(() => ready.reject(new Error('The bundled PDF reader could not start.')), 30_000);
    document.addEventListener('webviewerloaded', (event) => {
        const viewerEvent = event as CustomEvent<{ source: ViewerWindow }>;
        const source = viewerEvent.detail?.source;
        if (!source || source !== frame.contentWindow) return;
        application = source.PDFViewerApplication;
        source.PDFViewerApplicationOptions.setAll({
          disablePreferences: true,
          disableHistory: true,
          defaultUrl: '',
          defaultZoomValue: 'page-width',
          localeProperties: { lang: 'en-US' },
          toolbarDensity: 2,
          supportsDownloading: false,
          supportsPrinting: false,
          enableScripting: false,
          enablePermissions: true,
          enableComment: !options.readOnly,
          enableHighlightFloatingButton: !options.readOnly,
          enableAltText: false,
          enableAltTextModelDownload: false,
          enableGuessAltText: false,
          enableNewAltTextWhenAddingImage: false,
          annotationEditorMode: options.readOnly ? -1 : 0,
          annotationMode: options.readOnly ? 1 : 2,
          maxCanvasPixels: 16_777_216,
          viewerCssTheme: 1,
        });
        source.document.documentElement.classList.toggle('notepit-readonly', options.readOnly);
        source.document.addEventListener('click', (click) => {
          const target = click.target as Element | null;
          if (!target || typeof target.closest !== 'function') return;
          const anchor = target.closest('a[href]');
          if (!anchor) return;
          const url = new URL(anchor.getAttribute('href')!, source.location.href);
          if (url.origin !== source.location.origin || url.pathname !== source.location.pathname || url.search !== source.location.search) {
            click.preventDefault();
            click.stopImmediatePropagation();
          }
        }, { capture: true, signal: events.signal });
        source.document.addEventListener('keydown', (key) => {
          if ((key.ctrlKey || key.metaKey) && ['o', 'p', 's'].includes(key.key.toLowerCase())) {
            key.preventDefault();
            key.stopImmediatePropagation();
          }
        }, { capture: true, signal: events.signal });
        source.document.addEventListener('drop', (drop) => {
          if (Array.from(drop.dataTransfer?.files ?? []).some((file) => file.type === 'application/pdf')) {
            drop.preventDefault();
            drop.stopImmediatePropagation();
          }
        }, { capture: true, signal: events.signal });
        application.initializedPromise.then(() => {
          clearTimeout(readyTimer);
          ready.resolve(application!);
        }, ready.reject);
      }, { signal: events.signal });
    frame.addEventListener('error', () => ready.reject(new Error('The bundled PDF reader could not load.')), { once: true, signal: events.signal });
    host.append(frame);
    const viewer = await ready.promise;
    viewer.pdfLinkService.externalLinkEnabled = false;
    // PDF.js normalizes this cross-frame typed array into its own realm, making
    // the one copy needed for worker transfer without detaching caller bytes.
    const opening = viewer.open({ data: options.bytes, filename: options.name });
    const password = Promise.withResolvers<never>();
    if (!viewer.pdfLoadingTask) {
      password.reject(new Error('The PDF reader did not create a document loading task.'));
    } else {
      viewer.pdfLoadingTask.onPassword = () => {
        const error = new Error('This PDF is password protected. Open an unencrypted copy to read it in NotePit.');
        error.name = 'PasswordException';
        password.reject(error);
      };
    }
    await Promise.race([opening, password.promise]);
    await viewer.pdfViewer.firstPagePromise;
    const pdfDocument = viewer.pdfDocument;
    if (!pdfDocument) throw new Error('The PDF could not be loaded.');
    const storage = pdfDocument.annotationStorage;
    // Preserve the viewer's own bookkeeping (title/close behavior).
    const onSetModified = storage.onSetModified;
    const onResetModified = storage.onResetModified;
    storage.onSetModified = () => {
      onSetModified?.();
      changed = true;
      generation++;
      if (!options.readOnly) options.onDirty(true);
    };
    storage.onResetModified = () => {
      onResetModified?.();
      // saveDocument resets storage even when serialization fails. Disk commits
      // and marking the shell clean remain exclusively the shell's responsibility.
      if (!serializing) {
        changed = true;
        generation++;
        if (!options.readOnly) options.onDirty(true);
      }
    };

    return {
      async save() {
        if (destroyed) throw new Error('The PDF reader is closed.');
        // Commit an active FreeText/ink editor before serializing its storage.
        viewer.pdfViewer._layerProperties.annotationEditorUIManager?.endCurrentEditing();
        if (options.readOnly || !changed) return latestBytes;
        serializing = true;
        frame.inert = true;
        try {
          latestBytes = await pdfDocument.saveDocument();
          changed = false;
          serializedGeneration = generation;
          return latestBytes;
        } finally {
          serializing = false;
          frame.inert = false;
        }
      },
      markSaved() {
        if (destroyed) return;
        viewer.pdfViewer._layerProperties.annotationEditorUIManager?.endCurrentEditing();
        options.onDirty(!options.readOnly && generation !== serializedGeneration);
      },
      destroy,
    };
  } catch (error) {
    destroy();
    throw error;
  }
}
