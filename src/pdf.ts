import type { DocumentHandle, DocumentOptions } from './document';
import './pdf.css';

type Bounds = [number, number, number, number];
interface Viewport {
  viewBox: number[]; width: number; height: number; scale: number; rotation: number; userUnit: number;
  convertToPdfPoint(x: number, y: number): number[];
  convertToViewportPoint(x: number, y: number): number[];
}
interface RenderTask { promise: Promise<void>; cancel(): void }
interface PdfPage {
  view: number[];
  getViewport(options: { scale: number; rotation?: number }): Viewport;
  render(options: { canvasContext: CanvasRenderingContext2D; canvas: HTMLCanvasElement; viewport: Viewport; annotationMode: number; background: string }): RenderTask;
}
interface PageView {
  id: number; div: HTMLDivElement; pdfPage: PdfPage | null; viewport: Viewport;
  width: number; height: number;
}
interface EditorManager {
  endCurrentEditing(): void;
  addCommands(...args: unknown[]): void;
  undo(...args: unknown[]): void;
  redo(...args: unknown[]): void;
}
interface AnnotationStorage {
  onSetModified: (() => void) | null;
  onResetModified: (() => void) | null;
  getRawValue(key: string): unknown;
  setValue(key: string, value: Record<string, unknown>): void;
  remove(key: string): void;
}
interface PdfDocument {
  numPages: number; annotationStorage: AnnotationStorage;
  getPage(page: number): Promise<PdfPage>;
  getData(): Promise<Uint8Array>;
  saveDocument(): Promise<Uint8Array>;
}
interface ViewerApplication {
  initializedPromise: Promise<void>;
  pdfDocument: PdfDocument | null;
  initialBookmark: string | null;
  initialRotation: number;
  eventBus: {
    on<T>(name: string, callback: (event: T) => void, options: { signal: AbortSignal }): void;
  };
  pdfViewer: {
    firstPagePromise: Promise<unknown>;
    currentPageNumber: number;
    currentScaleValue: string;
    pagesRotation: number;
    container: HTMLDivElement;
    _layerProperties: { annotationEditorUIManager?: EditorManager };
    _getVisiblePages(): { ids: Set<number>; views: { id: number }[] };
    getPageView(index: number): PageView | undefined;
    scrollPageIntoView(options: { pageNumber: number; destArray?: unknown[] | null; [key: string]: unknown }): void;
    update(): void;
  };
  pdfLinkService: { externalLinkEnabled: boolean; setHash(hash: string): void };
  viewsManager: { switchView(view: number, forceOpen?: boolean): void } | null;
  findBar: { open(): void };
  open(options: { data?: Uint8Array; url?: string; filename: string }): Promise<void>;
  close(): Promise<void>;
}
interface ViewerWindow extends Window {
  PDFViewerApplication: ViewerApplication;
  PDFViewerApplicationOptions: { setAll(options: Record<string, unknown>): void };
}
interface ReadingState {
  page: number; left: number; top: number; zoom: string; rotation: number;
  crop: boolean; bookmarks: number[];
}
interface ViewAnchor { page: number; x: number; y: number; screenX: number; screenY: number }

function readingState(value: unknown): ReadingState {
  const state = (value && typeof value === 'object' ? value : {}) as Partial<ReadingState>;
  return {
    page: Number.isInteger(state.page) && state.page! > 0 ? state.page! : 1,
    left: Number.isFinite(state.left) ? state.left! : 0,
    top: Number.isFinite(state.top) ? state.top! : 0,
    zoom: typeof state.zoom === 'string' && (/^(page-width|page-fit|page-actual|auto)$/.test(state.zoom) || Number(state.zoom) > 0) ? state.zoom : 'page-width',
    rotation: [0, 90, 180, 270].includes(state.rotation!) ? state.rotation! : 0,
    crop: state.crop === true,
    bookmarks: Array.isArray(state.bookmarks) ? [...new Set(state.bookmarks.filter(page => Number.isInteger(page) && page > 0))].sort((a, b) => a - b) : [],
  };
}

/** The official, offline generic viewer owns rendering, selection and annotations. */
export async function openPdf(host: HTMLElement, options: DocumentOptions): Promise<DocumentHandle> {
  if (!options.bytes && !options.url) throw new Error('The PDF has no document source.');
  const state = readingState(options.state);
  const frame = document.createElement('iframe');
  frame.className = 'pdf-frame';
  frame.classList.toggle('pdf-dark', options.dark);
  frame.title = `PDF reader: ${options.name}`;
  // No popups, downloads, forms, or top-level navigation from document links.
  frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
  frame.src = `${import.meta.env.BASE_URL}pdfjs/web/viewer.html?file=`;
  const events = new AbortController();
  const cleanups: (() => void)[] = [];
  let application: ViewerApplication | undefined;
  let destroyed = false;
  let generation = 0;
  let serializedGeneration = 0;
  let latestBytes = options.bytes;
  let serialization: Promise<Uint8Array> | undefined;
  let readyTimer: number | undefined;
  let stateTimer: number | undefined;
  let cropTimer: number | undefined;
  let cropTask: RenderTask | undefined;
  let cropPage = 0;
  let cropEpoch = 0;
  let cropping = false;
  let cropCanvas: HTMLCanvasElement | undefined;
  // Only bounds, never page bitmaps, are retained. PDF.js owns its bounded canvas cache.
  const bounds = new Map<number, Bounds>();
  const croppedPages = new Set<number>();
  const getState = () => ({ ...state, bookmarks: [...state.bookmarks] });
  const emitState = () => {
    clearTimeout(stateTimer);
    stateTimer = undefined;
    if (!destroyed) options.onState(getState());
  };
  const scheduleState = () => {
    if (stateTimer === undefined) stateTimer = window.setTimeout(emitState, 200);
  };
  const modified = () => {
    if (destroyed || options.readOnly) return;
    generation++;
    options.onDirty(true);
    options.onJournal?.({ type: 'pdf-annotations', generation });
  };
  const destroy = () => {
    if (destroyed) return;
    emitState();
    destroyed = true;
    events.abort();
    clearTimeout(readyTimer);
    clearTimeout(stateTimer);
    clearTimeout(cropTimer);
    cropEpoch++;
    cropTask?.cancel();
    for (const cleanup of cleanups) cleanup();
    if (application) void application.close().catch(() => {}).finally(() => frame.remove());
    else frame.remove();
    frame.hidden = true;
  };

  try {
    const ready = Promise.withResolvers<ViewerApplication>();
    readyTimer = window.setTimeout(() => ready.reject(new Error('The bundled PDF reader could not start.')), 30_000);
    document.addEventListener('webviewerloaded', (event) => {
      const source = (event as CustomEvent<{ source: ViewerWindow }>).detail?.source;
      if (!source || source !== frame.contentWindow) return;
      application = source.PDFViewerApplication;
      source.PDFViewerApplicationOptions.setAll({
        disablePreferences: true, disableHistory: true, viewOnLoad: 1,
        defaultUrl: '', defaultZoomValue: state.zoom, localeProperties: { lang: 'en-US' },
        toolbarDensity: 2, supportsDownloading: false, supportsPrinting: false,
        enableScripting: false, enablePermissions: true, enableSplitMerge: false,
        enableComment: !options.readOnly, enableHighlightFloatingButton: !options.readOnly,
        enableAltText: false, enableAltTextModelDownload: false, enableGuessAltText: false,
        enableNewAltTextWhenAddingImage: false, enableSignatureEditor: false,
        annotationEditorMode: options.readOnly ? -1 : 0, annotationMode: options.readOnly ? 1 : 2,
        scrollModeOnLoad: 0, spreadModeOnLoad: 0, sidebarViewOnLoad: 0,
        // Disable speculative full-file/page fetching; explicit ranges still work.
        disableAutoFetch: true, disableStream: true,
        maxCanvasPixels: 5_242_880, maxCanvasDim: 8192, enableDetailCanvas: false,
        viewerCssTheme: options.dark ? 2 : 1,
      });
      source.document.documentElement.classList.toggle('notepit-readonly', options.readOnly);
      source.document.documentElement.classList.toggle('notepit-dark', options.dark);
      source.document.addEventListener('click', (click) => {
        const target = click.target as Element | null;
        const anchor = target?.closest?.('a[href]');
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
        if (Array.from(drop.dataTransfer?.files ?? []).some(file => file.type === 'application/pdf')) {
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
    const restoreOffset = options.state !== null && typeof options.state === 'object' && 'top' in options.state && typeof options.state.top === 'number' && Number.isFinite(options.state.top);
    viewer.initialBookmark = `page=${state.page}&zoom=${state.zoom}${restoreOffset ? `,${state.left},${state.top}` : ''}`;
    viewer.initialRotation = state.rotation;
    const documentInitialized = Promise.withResolvers<void>();
    viewer.eventBus.on('documentinit', () => documentInitialized.resolve(), { signal: events.signal });
    // URL input stays in the worker's range-loading path, with no JS full-file copy.
    // Keep the upstream password dialog, including incorrect-password retry/cancel.
    await viewer.open(options.url ? { url: options.url, filename: options.name } : { data: options.bytes!, filename: options.name });
    await viewer.pdfViewer.firstPagePromise;
    await documentInitialized.promise;
    const pdfDocument = viewer.pdfDocument;
    if (!pdfDocument) throw new Error('The PDF could not be loaded.');
    state.bookmarks = state.bookmarks.filter(page => page <= pdfDocument.numPages);
    const inner = frame.contentDocument!;
    const pdfViewer = viewer.pdfViewer;

    // Storage's onSetModified is edge-triggered, not an edit counter. Observe
    // actual writes and editor commands as well, so edits after a draft/save are
    // never mistaken for the generation which was committed to disk.
    const storage = pdfDocument.annotationStorage;
    const setValue = storage.setValue;
    const remove = storage.remove;
    storage.setValue = function (key, value) {
      const previous = this.getRawValue(key) as Record<string, unknown> | undefined;
      const different = !previous || Object.entries(value).some(([name, item]) => previous[name] !== item);
      setValue.call(this, key, value);
      if (different) modified();
    };
    storage.remove = function (key) {
      const existed = this.getRawValue(key) !== undefined;
      remove.call(this, key);
      if (existed) modified();
    };
    cleanups.push(() => { storage.setValue = setValue; storage.remove = remove; });
    let canUndo = false;
    let canRedo = false;
    viewer.eventBus.on('editingstateschanged', ({ details }: { details: { hasSomethingToUndo?: boolean; hasSomethingToRedo?: boolean } }) => {
      if (details.hasSomethingToUndo !== undefined) canUndo = details.hasSomethingToUndo;
      if (details.hasSomethingToRedo !== undefined) canRedo = details.hasSomethingToRedo;
    }, { signal: events.signal });
    const manager = pdfViewer._layerProperties.annotationEditorUIManager;
    if (manager && !options.readOnly) {
      for (const method of ['addCommands', 'undo', 'redo'] as const) {
        const original = manager[method];
        manager[method] = function (...args: unknown[]) {
          const changes = method === 'addCommands' || (method === 'undo' ? canUndo : canRedo);
          original.apply(manager, args);
          if (changes) modified();
        };
        cleanups.push(() => { manager[method] = original; });
      }
    }

    const toolbar = inner.createElement('div');
    toolbar.className = 'notepit-reader-tools';
    toolbar.setAttribute('role', 'toolbar');
    toolbar.setAttribute('aria-label', 'Reading tools');
    inner.getElementById('toolbarContainer')!.append(toolbar);
    const button = (label: string, action: () => void) => {
      const control = inner.createElement('button');
      control.type = 'button';
      control.textContent = label;
      control.addEventListener('click', action, { signal: events.signal });
      toolbar.append(control);
      return control;
    };
    const bookmarkList = inner.createElement('select');
    bookmarkList.setAttribute('aria-label', 'Saved page bookmarks');
    const refreshBookmarks = () => {
      bookmarkList.replaceChildren();
      const prompt = inner.createElement('option');
      prompt.value = '';
      prompt.textContent = `Bookmarks (${state.bookmarks.length})`;
      bookmarkList.append(prompt);
      for (const page of state.bookmarks) {
        const item = inner.createElement('option');
        item.value = String(page);
        item.textContent = `Page ${page}`;
        bookmarkList.append(item);
      }
      bookmarkButton.setAttribute('aria-pressed', String(state.bookmarks.includes(pdfViewer.currentPageNumber)));
    };
    const bookmark = () => {
      const page = pdfViewer.currentPageNumber;
      state.bookmarks = state.bookmarks.includes(page) ? state.bookmarks.filter(value => value !== page) : [...state.bookmarks, page].sort((a, b) => a - b);
      refreshBookmarks();
      emitState();
    };
    const bookmarkButton = button('Bookmark', bookmark);
    bookmarkButton.title = 'Add or remove this page bookmark';
    toolbar.append(bookmarkList);
    bookmarkList.addEventListener('change', () => {
      if (bookmarkList.value) pdfViewer.currentPageNumber = Number(bookmarkList.value);
      bookmarkList.value = '';
    }, { signal: events.signal });
    refreshBookmarks();

    // Keep full official layer viewports: editor positions are normalized to
    // those dimensions. Crop only the display/layout; saved coordinates never
    // change when margins are shown or hidden.
    const cropGeometry = (view: PageView, box: number[]) => {
      const first = view.viewport.convertToViewportPoint(box[0], box[1]);
      const last = view.viewport.convertToViewportPoint(box[2], box[3]);
      return { left: Math.min(first[0], last[0]), top: Math.min(first[1], last[1]), width: Math.abs(last[0] - first[0]), height: Math.abs(last[1] - first[1]) };
    };
    const applyBounds = (page: number, box: number[]) => {
      const view = pdfViewer.getPageView(page - 1);
      if (!view?.pdfPage) return false;
      const key = `${box.join(',')}:${view.viewport.rotation}`;
      if (view.div.dataset.notepitCrop === key) return false;
      const full = view.pdfPage.view.every((value, index) => value === box[index]);
      if (full) {
        view.div.classList.remove('notepit-cropped');
        delete view.div.dataset.notepitCrop;
        delete (view as Partial<PageView>).width;
        delete (view as Partial<PageView>).height;
        return croppedPages.has(page);
      }
      const geometry = cropGeometry(view, box);
      const scale = view.viewport.scale * view.viewport.userUnit;
      view.div.style.setProperty('--notepit-full-width', `${view.viewport.width / scale}px`);
      view.div.style.setProperty('--notepit-full-height', `${view.viewport.height / scale}px`);
      for (const name of ['left', 'top', 'width', 'height'] as const) {
        view.div.style.setProperty(`--notepit-crop-${name}`, `${geometry[name] / scale}px`);
      }
      Object.defineProperties(view, {
        width: { configurable: true, get: () => cropGeometry(view, box).width },
        height: { configurable: true, get: () => cropGeometry(view, box).height },
      });
      view.div.dataset.notepitCrop = key;
      view.div.classList.add('notepit-cropped');
      return true;
    };
    const captureAnchor = (): ViewAnchor | undefined => {
      const visible = pdfViewer._getVisiblePages();
      const page = visible.ids.has(pdfViewer.currentPageNumber) ? pdfViewer.currentPageNumber : visible.views[0]?.id;
      if (page === undefined) return;
      const view = pdfViewer.getPageView(page - 1);
      if (!view) return;
      const container = pdfViewer.container.getBoundingClientRect();
      const rect = view.div.getBoundingClientRect();
      const box = bounds.get(page);
      const crop = box && view.div.classList.contains('notepit-cropped') ? cropGeometry(view, box) : { left: 0, top: 0 };
      const [x, y] = view.viewport.convertToPdfPoint(Math.max(0, container.left - rect.left) + crop.left, Math.max(0, container.top - rect.top) + crop.top);
      return { page, x, y, screenX: Math.max(0, rect.left - container.left), screenY: Math.max(0, rect.top - container.top) };
    };
    const restoreAnchor = (anchor: ViewAnchor | undefined) => {
      if (!anchor) return;
      if (pdfViewer.currentPageNumber !== anchor.page) pdfViewer.currentPageNumber = anchor.page;
      const view = pdfViewer.getPageView(anchor.page - 1);
      if (!view) return;
      const container = pdfViewer.container.getBoundingClientRect();
      const rect = view.div.getBoundingClientRect();
      const point = view.viewport.convertToViewportPoint(anchor.x, anchor.y);
      const box = bounds.get(anchor.page);
      const crop = box && view.div.classList.contains('notepit-cropped') ? cropGeometry(view, box) : { left: 0, top: 0, width: view.viewport.width, height: view.viewport.height };
      pdfViewer.container.scrollLeft += rect.left - container.left + Math.max(0, Math.min(crop.width, point[0] - crop.left)) - anchor.screenX;
      pdfViewer.container.scrollTop += rect.top - container.top + Math.max(0, Math.min(crop.height, point[1] - crop.top)) - anchor.screenY;
      pdfViewer.update();
    };
    const updateBounds = (page: number, box: number[]) => {
      const view = pdfViewer.getPageView(page - 1);
      if (!view || view.div.dataset.notepitCrop === `${box.join(',')}:${view.viewport.rotation}`) return;
      const anchor = captureAnchor();
      if (!applyBounds(page, box)) return;
      if (pdfViewer.currentScaleValue === 'page-width') pdfViewer.currentScaleValue = 'page-width';
      restoreAnchor(anchor);
    };
    const navigate = pdfViewer.scrollPageIntoView;
    pdfViewer.scrollPageIntoView = function (destination) {
      const view = this.getPageView(destination.pageNumber - 1);
      // XYZ with a null y means the original page top, not the cropped height.
      if (view?.pdfPage && destination.destArray?.[1] && typeof destination.destArray[1] === 'object' && 'name' in destination.destArray[1] && destination.destArray[1].name === 'XYZ' && destination.destArray[3] == null) {
        destination = { ...destination, destArray: [...destination.destArray] };
        destination.destArray![3] = view.pdfPage.view[3];
      }
      navigate.call(this, destination);
      const box = state.crop ? bounds.get(destination.pageNumber) : undefined;
      if (view && box && destination.destArray) {
        const geometry = cropGeometry(view, box);
        this.container.scrollLeft = Math.max(0, this.container.scrollLeft - geometry.left);
        this.container.scrollTop = Math.max(0, this.container.scrollTop - geometry.top);
      }
    };
    cleanups.push(() => { pdfViewer.scrollPageIntoView = navigate; });
    const scheduleCrop = () => {
      clearTimeout(cropTimer);
      if (!state.crop || destroyed) return;
      const visible = pdfViewer._getVisiblePages();
      if (cropPage && !visible.ids.has(cropPage)) {
        cropEpoch++;
        cropTask?.cancel();
      }
      cropTimer = window.setTimeout(() => { void cropVisible(); }, 140);
    };
    const cropVisible = async () => {
      if (cropping || destroyed || !state.crop) return;
      cropping = true;
      const epoch = cropEpoch;
      try {
        for (const { id } of pdfViewer._getVisiblePages().views) {
          if (destroyed || !state.crop || epoch !== cropEpoch) break;
          if (!pdfViewer._getVisiblePages().ids.has(id)) continue;
          let box = bounds.get(id);
          if (!box) {
            cropPage = id;
            const page = await pdfDocument.getPage(id);
            if (destroyed || !state.crop || epoch !== cropEpoch || !pdfViewer._getVisiblePages().ids.has(id)) break;
            const full = page.getViewport({ scale: 1, rotation: 0 });
            const viewport = page.getViewport({ scale: Math.min(1, 384 / Math.max(full.width, full.height)), rotation: 0 });
            const canvas = cropCanvas ??= inner.createElement('canvas');
            canvas.width = Math.max(1, Math.ceil(viewport.width));
            canvas.height = Math.max(1, Math.ceil(viewport.height));
            const context = canvas.getContext('2d', { willReadFrequently: true })!;
            cropTask = page.render({ canvasContext: context, canvas, viewport, annotationMode: 1, background: '#ffffff' });
            await cropTask.promise;
            cropTask = undefined;
            if (destroyed || !state.crop || epoch !== cropEpoch) break;
            const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
            let left = canvas.width, top = canvas.height, right = -1, bottom = -1;
            // Include faint/non-white marks conservatively; white/blank pages
            // remain uncropped. A small raster-pixel guard preserves antialiasing.
            for (let y = 0; y < canvas.height; y++) {
              for (let x = 0; x < canvas.width; x++) {
                const i = (y * canvas.width + x) * 4;
                if (data[i] < 248 || data[i + 1] < 248 || data[i + 2] < 248) {
                  left = Math.min(left, x); right = Math.max(right, x);
                  top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
              }
            }
            box = [...page.view] as Bounds;
            if (right >= left && bottom >= top) {
              const first = viewport.convertToPdfPoint(Math.max(0, left - 3), Math.max(0, top - 3));
              const last = viewport.convertToPdfPoint(Math.min(viewport.width, right + 4), Math.min(viewport.height, bottom + 4));
              box = [Math.max(box[0], Math.min(first[0], last[0])), Math.max(box[1], Math.min(first[1], last[1])), Math.min(box[2], Math.max(first[0], last[0])), Math.min(box[3], Math.max(first[1], last[1]))];
            }
            bounds.set(id, box);
            if (bounds.size > 64) {
              const oldest = bounds.keys().next().value!;
              const oldView = pdfViewer.getPageView(oldest - 1);
              if (oldView?.pdfPage) updateBounds(oldest, oldView.pdfPage.view);
              bounds.delete(oldest);
              croppedPages.delete(oldest);
            }
          } else {
            bounds.delete(id);
            bounds.set(id, box);
          }
          updateBounds(id, box);
          if (pdfViewer.getPageView(id - 1)?.div.classList.contains('notepit-cropped')) croppedPages.add(id);
        }
      } catch (error) {
        if (!destroyed && state.crop && epoch === cropEpoch && (error as Error).name !== 'RenderingCancelledException') {
          cropButton.title = 'A page could not be analyzed; its original margins are retained.';
        }
      } finally {
        cropTask = undefined;
        cropPage = 0;
        cropping = false;
        if (epoch !== cropEpoch && state.crop && !destroyed) scheduleCrop();
      }
    };
    const toggleCrop = () => {
      const anchor = captureAnchor();
      state.crop = !state.crop;
      cropEpoch++;
      cropTask?.cancel();
      cropButton.setAttribute('aria-pressed', String(state.crop));
      if (!state.crop) {
        for (const page of croppedPages) {
          const view = pdfViewer.getPageView(page - 1);
          if (view?.pdfPage) applyBounds(page, view.pdfPage.view);
        }
        croppedPages.clear();
        if (pdfViewer.currentScaleValue === 'page-width') pdfViewer.currentScaleValue = 'page-width';
        restoreAnchor(anchor);
      } else scheduleCrop();
      emitState();
    };
    const cropButton = button('Crop margins', toggleCrop);
    cropButton.title = 'Reversibly hide detected whitespace on visible pages';
    cropButton.setAttribute('aria-pressed', String(state.crop));
    const outlineButton = button('Outline', () => viewer.viewsManager?.switchView(2, true));
    outlineButton.disabled = (inner.getElementById('outlinesViewMenu') as HTMLButtonElement).disabled;
    viewer.eventBus.on('outlineloaded', ({ outlineCount }: { outlineCount: number }) => {
      outlineButton.disabled = outlineCount === 0;
    }, { signal: events.signal });
    button('Fit width', () => { pdfViewer.currentScaleValue = 'page-width'; });
    viewer.eventBus.on('updateviewarea', ({ location }: { location: { pageNumber: number; left: number; top: number; scale: string | number; rotation: number } }) => {
      state.page = location.pageNumber;
      state.left = location.left;
      state.top = location.top;
      const view = pdfViewer.getPageView(location.pageNumber - 1);
      const box = state.crop ? bounds.get(location.pageNumber) : undefined;
      if (view && box) {
        const point = view.viewport.convertToViewportPoint(location.left, location.top);
        const geometry = cropGeometry(view, box);
        const adjusted = view.viewport.convertToPdfPoint(point[0] + geometry.left, point[1] + geometry.top);
        state.left = adjusted[0];
        state.top = adjusted[1];
      }
      state.zoom = String(location.scale);
      state.rotation = location.rotation;
      bookmarkButton.setAttribute('aria-pressed', String(state.bookmarks.includes(state.page)));
      scheduleState();
      scheduleCrop();
    }, { signal: events.signal });
    viewer.eventBus.on('pagerendered', scheduleCrop, { signal: events.signal });
    pdfViewer.update();
    scheduleCrop();

    return {
      save() {
        if (destroyed) return Promise.reject(new Error('The PDF reader is closed.'));
        if (serialization) return serialization;
        manager?.endCurrentEditing();
        const savingGeneration = generation;
        frame.inert = true;
        serialization = (async () => {
          // Unchanged URL sources fetch bytes only upon an explicit save/draft
          // request. getData obtains the original through the existing worker.
          const bytes = !options.readOnly && savingGeneration !== serializedGeneration
            ? await pdfDocument.saveDocument()
            : latestBytes ?? await pdfDocument.getData();
          latestBytes = bytes;
          serializedGeneration = savingGeneration;
          return bytes;
        })().finally(() => {
          serialization = undefined;
          frame.inert = false;
        });
        return serialization;
      },
      markSaved() {
        if (destroyed) return;
        manager?.endCurrentEditing();
        options.onDirty(!options.readOnly && generation !== serializedGeneration);
      },
      getState,
      command(command) {
        if (destroyed) return;
        switch (command) {
          case 'commit': manager?.endCurrentEditing(); break;
          case 'immersive:on':
          case 'immersive:off':
            inner.documentElement.classList.toggle('notepit-immersive', command === 'immersive:on');
            pdfViewer.update();
            break;
          case 'theme:dark':
          case 'theme:light': {
            const dark = command === 'theme:dark';
            frame.classList.toggle('pdf-dark', dark);
            inner.documentElement.classList.toggle('notepit-dark', dark);
            inner.documentElement.style.colorScheme = dark ? 'dark' : 'light';
            inner.documentElement.classList.toggle('is-light', !dark);
            inner.documentElement.classList.toggle('is-dark', dark);
            break;
          }
          case 'fitWidth': pdfViewer.currentScaleValue = 'page-width'; break;
          case 'crop': toggleCrop(); break;
          case 'bookmark': bookmark(); break;
          case 'outline': viewer.viewsManager?.switchView(2, true); break;
          case 'search': viewer.findBar.open(); break;
          case 'nextPage': pdfViewer.currentPageNumber = Math.min(pdfDocument.numPages, pdfViewer.currentPageNumber + 1); break;
          case 'previousPage': pdfViewer.currentPageNumber = Math.max(1, pdfViewer.currentPageNumber - 1); break;
          default: {
            const page = /^page:(\d+)$/.exec(command);
            if (page) pdfViewer.currentPageNumber = Math.max(1, Math.min(pdfDocument.numPages, Number(page[1])));
          }
        }
      },
      destroy,
    };
  } catch (error) {
    destroy();
    throw error;
  }
}
