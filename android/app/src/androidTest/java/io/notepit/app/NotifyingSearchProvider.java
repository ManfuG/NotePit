package io.notepit.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real ContentService notifications and SAF streams; configuration/metrics are available over IPC. */
public final class NotifyingSearchProvider extends DocumentsProvider {
    public static final String AUTHORITY = "io.notepit.app.search.feedback.tests";
    public static final Uri BASE = Uri.parse("content://" + AUTHORITY);
    public static final Uri CONTROL = Uri.parse("content://" + AUTHORITY + "/control");
    public static final Uri TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
    private static final String[] COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    private static final class Source {
        final String id;
        final String name;
        final File file;
        long size;
        long modified;

        Source(String id, String name, File file, long size) {
            this.id = id;
            this.name = name;
            this.file = file;
            this.size = size;
            this.modified = 1_000L;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, Integer> opens = new LinkedHashMap<>();
    private int queryCount;
    private String blockedId;
    private boolean blocked;
    private volatile CountDownLatch release = new CountDownLatch(0);
    private String grantedPackage;

    public static Uri documentUri(String id) {
        return DocumentsContract.buildDocumentUriUsingTree(TREE, id);
    }

    private Context providerContext() {
        return Objects.requireNonNull(getContext());
    }

    private File directory() {
        File directory = new File(providerContext().getFilesDir(), "notifying-search-provider");
        directory.mkdirs();
        return directory;
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "reset":
                synchronized (lock) {
                    release.countDown();
                    sources.clear();
                    opens.clear();
                    queryCount = 0;
                    blockedId = null;
                    blocked = false;
                    File directory = directory();
                    deleteRecursively(directory);
                    directory.mkdirs();
                    for (int number = 0; number < 44; number++) {
                        String suffix = number < 10 ? "0" + number : Integer.toString(number);
                        add("healthy-" + suffix, "healthy-" + suffix + ".txt",
                            ("originalNeedle" + suffix).getBytes(StandardCharsets.UTF_8));
                    }
                    for (int number = 0; number < 2; number++) {
                        add("bad-" + number, "bad-" + number + ".txt", new byte[] {0, 1, 2, 3});
                    }
                    grantedPackage = Objects.requireNonNull(Objects.requireNonNull(extras).getString("package"));
                    long identity = Binder.clearCallingIdentity();
                    try {
                        providerContext().grantUriPermission(grantedPackage, TREE,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                    Bundle result = new Bundle();
                    result.putString("tree", TREE.toString());
                    return result;
                }
            case "metrics":
                synchronized (lock) {
                    Bundle result = new Bundle();
                    result.putInt("queries", queryCount);
                    result.putBoolean("blocked", blocked);
                    Bundle counts = new Bundle();
                    for (Map.Entry<String, Integer> entry : opens.entrySet()) {
                        counts.putInt(entry.getKey(), entry.getValue());
                    }
                    result.putBundle("opens", counts);
                    return result;
                }
            case "edit": {
                String id = Objects.requireNonNull(arg);
                synchronized (lock) {
                    Source source = Objects.requireNonNull(sources.get(id), id);
                    String text = Objects.requireNonNull(Objects.requireNonNull(extras).getString("text"));
                    writeBytes(source.file, text.getBytes(StandardCharsets.UTF_8));
                    if (!extras.getBoolean("preserveMetadata")) {
                        source.size = source.file.length();
                        source.modified += 2_000;
                    }
                }
                notifySource(id, ContentResolver.NOTIFY_UPDATE);
                return Bundle.EMPTY;
            }
            case "add": {
                String id = Objects.requireNonNull(arg);
                synchronized (lock) {
                    String text = Objects.requireNonNull(Objects.requireNonNull(extras).getString("text"));
                    add(id, id + ".txt", text.getBytes(StandardCharsets.UTF_8));
                }
                notifySource(id, ContentResolver.NOTIFY_INSERT);
                return Bundle.EMPTY;
            }
            case "remove": {
                String id = Objects.requireNonNull(arg);
                synchronized (lock) {
                    Source source = sources.remove(id);
                    if (source != null) source.file.delete();
                }
                notifySource(id, ContentResolver.NOTIFY_DELETE);
                return Bundle.EMPTY;
            }
            case "block":
                synchronized (lock) {
                    blockedId = Objects.requireNonNull(arg);
                    blocked = false;
                    release = new CountDownLatch(1);
                    return Bundle.EMPTY;
                }
            case "release":
                release.countDown();
                return Bundle.EMPTY;
            case "clear":
                synchronized (lock) {
                    release.countDown();
                    if (grantedPackage != null) {
                        long identity = Binder.clearCallingIdentity();
                        try {
                            providerContext().revokeUriPermission(grantedPackage, TREE,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                    grantedPackage = null;
                    sources.clear();
                    opens.clear();
                    deleteRecursively(directory());
                    return Bundle.EMPTY;
                }
            default:
                Bundle result = super.call(method, arg, extras);
                return result != null ? result : Bundle.EMPTY;
        }
    }

    private void add(String id, String name, byte[] bytes) {
        File file = new File(directory(), id);
        writeBytes(file, bytes);
        sources.put(id, new Source(id, name, file, bytes.length));
    }

    private static void writeBytes(File file, byte[] bytes) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write provider fixture " + file, error);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private void notifySource(String id) {
        notifySource(id, 0);
    }

    private void notifySource(String id, int flags) {
        long identity = Binder.clearCallingIdentity();
        try {
            providerContext().getContentResolver().notifyChange(documentUri(id), null, flags);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES
        });
        String[] columns = cursor.getColumnNames();
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Root.COLUMN_ROOT_ID: values[i] = "search-feedback"; break;
                case Root.COLUMN_DOCUMENT_ID: values[i] = "root"; break;
                case Root.COLUMN_TITLE: values[i] = "NotePit search feedback fixture"; break;
                case Root.COLUMN_FLAGS: values[i] = Root.FLAG_SUPPORTS_IS_CHILD; break;
                case Root.COLUMN_MIME_TYPES: values[i] = "text/plain"; break;
            }
        }
        cursor.addRow(values);
        synchronized (lock) { queryCount++; }
        notifySource("root");
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor;
        synchronized (lock) {
            queryCount++;
            cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
            row(cursor, documentId);
        }
        notifySource(documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!"root".equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        MatrixCursor cursor;
        synchronized (lock) {
            queryCount++;
            cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
            for (String id : sources.keySet()) row(cursor, id);
        }
        notifySource("root");
        return cursor;
    }

    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        Source source = "root".equals(id) ? null : sources.get(id);
        if (source == null && !"root".equals(id)) throw new FileNotFoundException(id);
        String[] columns = cursor.getColumnNames();
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: values[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: values[i] = source != null ? source.name : "Notifying search root"; break;
                case Document.COLUMN_MIME_TYPE: values[i] = source != null ? "text/plain" : Document.MIME_TYPE_DIR; break;
                case Document.COLUMN_FLAGS: values[i] = source != null ? Document.FLAG_SUPPORTS_WRITE : 0; break;
                case Document.COLUMN_SIZE: values[i] = source != null ? source.size : 0L; break;
                case Document.COLUMN_LAST_MODIFIED: values[i] = source != null ? source.modified : 1_000L; break;
            }
        }
        cursor.addRow(values);
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        Source source;
        CountDownLatch waitForRelease = null;
        synchronized (lock) {
            source = sources.get(documentId);
            if (source == null) throw new FileNotFoundException(documentId);
            if (mode.indexOf('w') < 0) {
                Integer previous = opens.get(documentId);
                opens.put(documentId, previous != null ? previous + 1 : 1);
            }
            if (documentId.equals(blockedId)) {
                blocked = true;
                waitForRelease = release;
            }
        }
        notifySource(documentId);
        if (waitForRelease != null) {
            try {
                if (!waitForRelease.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Blocked notifying provider was not released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Blocked notifying provider was interrupted", error);
            }
        }
        if (signal != null) signal.throwIfCanceled();
        return ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        synchronized (lock) {
            return "root".equals(parentDocumentId) && sources.containsKey(documentId);
        }
    }
}
