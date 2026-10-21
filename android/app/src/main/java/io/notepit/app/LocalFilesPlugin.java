package io.notepit.app;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "LocalFiles")
public class LocalFilesPlugin extends Plugin {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private interface Job { JSObject run() throws Exception; }
    private void run(PluginCall call, Job job) {
        io.execute(() -> {
            try { call.resolve(job.run()); }
            catch (Exception error) { call.reject(error.getMessage() == null ? "File operation failed." : error.getMessage(), error); }
        });
    }
    private Uri uri(PluginCall call) {
        String value = call.getString("uri");
        if (value == null || !value.startsWith("content://")) throw new IllegalArgumentException("Invalid document URI.");
        return Uri.parse(value);
    }
    private Set<String> rootsSet() {
        return new HashSet<>(getContext().getSharedPreferences("folders", 0).getStringSet("roots", Collections.emptySet()));
    }
    private void store(Set<String> roots) {
        getContext().getSharedPreferences("folders", 0).edit().putStringSet("roots", roots).apply();
    }
    private JSObject entry(Uri uri, Cursor cursor) {
        boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
        int flags = cursor.getInt(3);
        boolean writable = (flags & (directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE)) != 0;
        return new JSObject().put("uri", uri.toString()).put("name", cursor.getString(1))
            .put("directory", directory).put("writable", writable).put("size", cursor.getLong(4)).put("modified", cursor.getLong(5));
    }
    private JSObject queryEntry(Uri uri) throws IOException {
        try (Cursor cursor = getContext().getContentResolver().query(uri, COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Document no longer exists. Refresh the folder.");
            return entry(uri, cursor);
        }
    }
    @PluginMethod public void roots(PluginCall call) {
        run(call, () -> {
            JSArray entries = new JSArray();
            for (String root : rootsSet()) {
                Uri tree = Uri.parse(root);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                try { entries.put(queryEntry(doc)); }
                catch (Exception error) {
                    entries.put(new JSObject().put("uri", doc.toString()).put("name", "Folder access lost — reconnect")
                        .put("directory", true).put("writable", false).put("size", 0).put("modified", 0));
                }
            }
            return new JSObject().put("entries", entries);
        });
    }
    @PluginMethod public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPicked");
    }
    @ActivityCallback private void folderPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) { call.reject("Folder selection cancelled.", "CANCELLED"); return; }
        run(call, () -> {
            Intent data = result.getData();
            Uri tree = data.getData();
            if (tree == null) throw new IOException("No folder selected.");
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContext().getContentResolver().takePersistableUriPermission(tree, flags);
            Set<String> roots = rootsSet(); roots.add(tree.toString()); store(roots);
            return new JSObject().put("entry", queryEntry(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))));
        });
    }
    @PluginMethod public void forget(PluginCall call) {
        run(call, () -> {
            Uri doc = uri(call);
            Set<String> roots = rootsSet();
            String match = null;
            for (String root : roots) {
                Uri tree = Uri.parse(root);
                if (DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).equals(doc)) match = root;
            }
            if (match != null) {
                roots.remove(match); store(roots);
                for (UriPermission permission : getContext().getContentResolver().getPersistedUriPermissions()) {
                    if (permission.getUri().toString().equals(match)) {
                        int flags = (permission.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                            | (permission.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                        getContext().getContentResolver().releasePersistableUriPermission(permission.getUri(), flags);
                        break;
                    }
                }
            }
            return new JSObject();
        });
    }
    @PluginMethod public void list(PluginCall call) {
        run(call, () -> {
            Uri parent = uri(call);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent));
            JSArray entries = new JSArray();
            try (Cursor cursor = getContext().getContentResolver().query(children, COLUMNS, null, null, null)) {
                if (cursor == null) throw new IOException("Unable to read this folder. Reconnect it using Add folder.");
                while (cursor.moveToNext()) entries.put(entry(DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)), cursor));
            }
            return new JSObject().put("entries", entries);
        });
    }
    private String name(PluginCall call) {
        String name = call.getString("name", "").trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) throw new IllegalArgumentException("Enter a valid file name.");
        return name;
    }
    @PluginMethod public void create(PluginCall call) {
        run(call, () -> {
            String displayName = name(call);
            String extension = displayName.substring(displayName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            Uri created = DocumentsContract.createDocument(getContext().getContentResolver(), uri(call),
                call.getBoolean("directory", false) ? DocumentsContract.Document.MIME_TYPE_DIR
                    : mime == null ? "application/octet-stream" : mime, displayName);
            if (created == null) throw new IOException("The provider could not create this document.");
            return new JSObject().put("entry", queryEntry(created));
        });
    }
    @PluginMethod public void rename(PluginCall call) {
        run(call, () -> {
            Uri renamed = DocumentsContract.renameDocument(getContext().getContentResolver(), uri(call), name(call));
            if (renamed == null) throw new IOException("The provider could not rename this document.");
            return new JSObject().put("entry", queryEntry(renamed));
        });
    }
    @PluginMethod public void remove(PluginCall call) {
        run(call, () -> {
            if (!DocumentsContract.deleteDocument(getContext().getContentResolver(), uri(call))) throw new IOException("The provider could not delete this document.");
            return new JSObject();
        });
    }
    @PluginMethod public void save(PluginCall call) {
        run(call, () -> {
            Uri target = uri(call);
            String revision = call.getString("revision");
            byte[] bytes = android.util.Base64.decode(call.getString("data", ""), android.util.Base64.DEFAULT);
            File backup = File.createTempFile("notepit-save-", ".bak", getContext().getCacheDir());
            boolean writing = false;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = getContext().getContentResolver().openInputStream(target); OutputStream out = new FileOutputStream(backup)) {
                    if (in == null) throw new IOException("Unable to read the original file.");
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = in.read(buffer)) != -1) { digest.update(buffer, 0, count); out.write(buffer, 0, count); }
                }
                StringBuilder hash = new StringBuilder(64);
                for (byte b : digest.digest()) hash.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
                if (!hash.toString().equals(revision)) throw new IOException("The file changed outside NotePit. Your edits are still open. Reopen the file before saving.");
                writing = true;
                try (OutputStream out = getContext().getContentResolver().openOutputStream(target, "rwt")) {
                    if (out == null) throw new IOException("The provider denied write access.");
                    out.write(bytes);
                }
            } catch (Exception error) {
                if (writing) {
                    try (InputStream in = new FileInputStream(backup); OutputStream out = getContext().getContentResolver().openOutputStream(target, "rwt")) {
                        if (out == null) throw new IOException("Unable to restore original.");
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    } catch (Exception restore) {
                        throw new IOException("Save and recovery failed. Original retained in app cache: " + backup.getName(), restore);
                    }
                }
                backup.delete();
                throw error;
            }
            backup.delete();
            return new JSObject();
        });
    }
    @Override protected void handleOnDestroy() { io.shutdown(); }
}
