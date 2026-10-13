package com.appm.pdfcraft;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The files PdfCraft opened and saved, and where a save of each document should go (by the rules
 * in {@link SaveTargets}).
 */
final class FileStore {
    static final String DOWNLOADS_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/PdfCraft";

    /** Whether this process has deleted the spool files earlier processes left behind. */
    private static boolean swept;

    /** A file handed to PdfCraft, waiting to be fetched from {@code /incoming/<id>}. */
    private static final class Incoming {
        final Uri uri;
        final String name;

        Incoming(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    /**
     * Bytes PdfCraft handed over, spooled to a temporary file, and where they are meant to go; or
     * a document PdfCraft closed, which lets go of its file only after the saves before it.
     */
    static final class SaveJob {
        final String name;
        final String mime;
        final boolean saveAs;
        /**
         * {@code "save"} for a document save, {@code "export"} for any other download,
         * {@code "close"} for a closed document.
         */
        final String kind;
        /** The document saved or closed (see {@link SaveTargets}), or null: an export, or no id came. */
        final String key;
        /** The bytes; null for a close. */
        final File file;

        SaveJob(String name, String mime, boolean saveAs, String kind, String key, File file) {
            this.name = name;
            this.mime = mime;
            this.saveAs = saveAs;
            this.kind = kind;
            this.key = key;
            this.file = file;
        }

        boolean isExport() {
            return "export".equals(kind);
        }

        boolean isClose() {
            return "close".equals(kind);
        }

        /**
         * The job as instance state, so it outlives the process while a picker is open. Not for a
         * close: its key means nothing to another process.
         */
        Bundle toBundle() {
            Bundle b = new Bundle();
            b.putString("name", name);
            b.putString("mime", mime);
            b.putBoolean("saveAs", saveAs);
            b.putString("kind", kind);
            b.putString("key", key);
            b.putString("file", file.getPath());
            return b;
        }

        /** The job {@link #toBundle} kept, or null if there is none or its bytes are gone. */
        static SaveJob fromBundle(Bundle b) {
            if (b == null) return null;
            String name = b.getString("name");
            String path = b.getString("file");
            if (name == null || path == null || !new File(path).isFile()) return null;
            return new SaveJob(name, b.getString("mime"), b.getBoolean("saveAs"), b.getString("kind"), b.getString("key"),
                    new File(path));
        }
    }

    private static final class Spool {
        final SaveJob job;
        final OutputStream out;

        Spool(SaveJob job, OutputStream out) {
            this.job = job;
            this.out = out;
        }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, Incoming> incoming = new HashMap<>();
    private final List<String> pending = new ArrayList<>();
    private final SaveTargets<Uri> targets = new SaveTargets<>();
    private final Map<String, Spool> spools = new HashMap<>();

    FileStore(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    // ---- opening ----

    /**
     * Queue {@code uri} for PdfCraft to open; {@code canWrite} if a Save may overwrite it. The id
     * goes to PdfCraft with the file and comes back with every save of that document.
     */
    String addIncoming(Uri uri, boolean canWrite) {
        String name = displayName(uri);
        synchronized (this) {
            String id = "f" + ids.incrementAndGet();
            incoming.put(id, new Incoming(uri, name));
            pending.add(id);
            targets.addIncoming(id, name, uri, canWrite);
            return id;
        }
    }

    /** Note a file picked in PdfCraft's own Open; {@code canWrite} if a Save may overwrite it. */
    void addPicked(Uri uri, boolean canWrite) {
        targets.addPicked(displayName(uri), uri, canWrite);
    }

    /** The files queued since the last call, as a JSON list of {@code {id, name}}. */
    synchronized String drainPending() {
        JSONArray list = new JSONArray();
        for (String id : pending) {
            Incoming in = incoming.get(id);
            if (in == null) continue;
            try {
                list.put(new JSONObject().put("id", id).put("name", in.name));
            } catch (org.json.JSONException ignored) {
                // put() with a String key and value never throws.
            }
        }
        pending.clear();
        return list.toString();
    }

    /** The bytes of queued file {@code id}; each file can be fetched once. */
    InputStream openIncoming(String id) throws IOException {
        Incoming in;
        synchronized (this) {
            in = incoming.remove(id);
        }
        if (in == null) throw new FileNotFoundException("no incoming file " + id);
        InputStream stream = resolver.openInputStream(in.uri);
        if (stream == null) throw new FileNotFoundException(in.uri.toString());
        return stream;
    }

    String displayName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
            } catch (RuntimeException ignored) {
                // Some providers refuse queries; fall back to the URI.
            }
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "document.pdf";
        return safeName(name);
    }

    // ---- where saves go ----

    /** PdfCraft (re)started: forget its old document numbers, which it now hands out again. */
    void newPage() {
        targets.newPage();
    }

    /** Where a Save of document {@code key}, called {@code name}, should be written ({@link SaveTargets#targetFor}). */
    Uri targetFor(String key, String name) {
        return targets.targetFor(key, name);
    }

    /** Make {@code uri} where later saves of document {@code key} go. */
    void bind(String key, Uri uri) {
        targets.bind(key, uri);
    }

    /** {@link #bind}, unless saves of {@code key} go somewhere already. */
    void bindIfUnbound(String key, Uri uri) {
        targets.bindIfUnbound(key, uri);
    }

    /**
     * PdfCraft closed the document {@code hostId} or else {@code doc} names (as in {@link #begin}),
     * or file {@code hostId} never became one: the job that, queued behind the saves PdfCraft made
     * before, lets go of where they went ({@link #forget}).
     */
    SaveJob closed(String hostId, String doc) {
        return new SaveJob("", null, false, "close", targets.keyFor(hostId, doc), null);
    }

    /** Run a close job: the file document {@code key} saved to is free for the next one opened from it. */
    void forget(String key) {
        targets.forget(key);
    }

    // ---- saving ----

    /**
     * Start spooling a save; returns the id the chunks are sent with. A document save names its
     * document by {@code hostId} (the id it was opened with) or else by {@code doc} (PdfCraft's
     * number for it); either may be empty.
     */
    String begin(String name, String mime, boolean saveAs, String kind, String hostId, String doc) throws IOException {
        File dir = spoolDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        String safe = safeName(name == null || name.trim().isEmpty() ? "document.pdf" : name);
        String id = "s" + ids.incrementAndGet();
        // A new name: a job kept from an earlier process may still be waiting in this folder.
        File file = File.createTempFile(id + "-", ".part", dir);
        String key = "save".equals(kind) ? targets.keyFor(hostId, doc) : null;
        SaveJob job = new SaveJob(safe, mimeFor(safe, mime), saveAs, kind, key, file);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
        synchronized (this) {
            spools.put(id, new Spool(job, out));
        }
        return id;
    }

    void append(String id, String base64) throws IOException {
        Spool spool;
        synchronized (this) {
            spool = spools.get(id);
        }
        if (spool == null) throw new IOException("unknown save " + id);
        spool.out.write(Base64.decode(base64, Base64.DEFAULT));
    }

    SaveJob end(String id) throws IOException {
        Spool spool;
        synchronized (this) {
            spool = spools.remove(id);
        }
        if (spool == null) throw new IOException("unknown save " + id);
        spool.out.close();
        return spool.job;
    }

    void abort(String id) {
        Spool spool;
        synchronized (this) {
            spool = spools.remove(id);
        }
        if (spool == null) return;
        try {
            spool.out.close();
        } catch (IOException ignored) {
            // Being thrown away anyway.
        }
        //noinspection ResultOfMethodCallIgnored
        spool.job.file.delete();
    }

    private File spoolDir() {
        return new File(context.getCacheDir(), "saves");
    }

    /**
     * Once per process, delete the spool files an earlier process left behind when it ended
     * mid-save, except {@code keep}: the jobs restored from its saved state.
     */
    void sweepSpools(Collection<File> keep) {
        synchronized (FileStore.class) {
            if (swept) return;
            swept = true;
        }
        File[] parts = spoolDir().listFiles((dir, n) -> n.endsWith(".part"));
        if (parts == null) return;
        for (File part : parts) {
            //noinspection ResultOfMethodCallIgnored
            if (!keep.contains(part)) part.delete();
        }
    }

    /** Replace the contents of {@code uri} with {@code file}. */
    void writeTo(Uri uri, File file) throws IOException {
        try (InputStream in = new FileInputStream(file); OutputStream out = openForWrite(uri)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
    }

    private OutputStream openForWrite(Uri uri) throws IOException {
        // "wt" truncates; "w" alone leaves stale bytes after a shorter file on some providers.
        for (String mode : new String[] {"wt", "rwt", "w"}) {
            try {
                OutputStream out = resolver.openOutputStream(uri, mode);
                if (out != null) return out;
            } catch (FileNotFoundException | IllegalArgumentException | UnsupportedOperationException e) {
                // Try the next mode.
            }
        }
        throw new FileNotFoundException("can't write " + uri);
    }

    /**
     * Delete the document a "save to" picker created, if it is empty as a new one is, where its
     * provider allows. When the user chose an existing file to replace, the picker hands back
     * that file as it was, and it stays.
     */
    void deleteIfEmpty(Uri uri) {
        try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.isNull(0) || c.getLong(0) != 0) return;
        } catch (RuntimeException e) {
            return; // Its size is unknown: keep it.
        }
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (FileNotFoundException | RuntimeException e) {
            // It stays behind, empty.
        }
    }

    /** Write {@code file} to Downloads/PdfCraft/{@code name}; returns the new file's URI. */
    Uri saveToDownloads(String name, String mime, File file) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOADS_FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Downloads refused the file");
        try {
            writeTo(uri, file);
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    /** The MIME type for {@code name}: from its extension, else {@code fallback}. */
    static String mimeFor(String name, String fallback) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (fromExt != null) return fromExt;
        }
        return fallback == null || fallback.isEmpty() ? "application/octet-stream" : fallback;
    }

    private static String safeName(String name) {
        String s = name.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        return s.length() > 200 ? s.substring(s.length() - 200) : s;
    }
}
