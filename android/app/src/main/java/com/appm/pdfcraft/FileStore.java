package com.appm.pdfcraft;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The files PdfCraft opened and saved, and where a save of each document should go.
 *
 * <p>PdfCraft's host bridge names a saved document only by its file name, so files are tracked
 * by name: a Save goes back to the one writable file of that name, or to the file a Save As or
 * an earlier fallback save chose; with no such file it goes to Downloads/PdfCraft.
 */
final class FileStore {
    static final String DOWNLOADS_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/PdfCraft";

    /** A file handed to PdfCraft, waiting to be fetched from {@code /incoming/<id>}. */
    private static final class Incoming {
        final Uri uri;
        final String name;

        Incoming(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    /** Bytes PdfCraft handed over, spooled to a temporary file, and where they are meant to go. */
    static final class SaveJob {
        final String name;
        final String mime;
        final boolean saveAs;
        /** {@code "save"} for a document save, {@code "export"} for any other download. */
        final String kind;
        final File file;

        SaveJob(String name, String mime, boolean saveAs, String kind, File file) {
            this.name = name;
            this.mime = mime;
            this.saveAs = saveAs;
            this.kind = kind;
            this.file = file;
        }

        boolean isExport() {
            return "export".equals(kind);
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
    private final Map<String, Set<Uri>> writable = new HashMap<>();
    private final Map<String, Uri> preferred = new HashMap<>();
    private final Map<String, Spool> spools = new HashMap<>();

    FileStore(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    // ---- opening ----

    /** Queue {@code uri} for PdfCraft to open; {@code canWrite} if a Save may overwrite it. */
    String addIncoming(Uri uri, boolean canWrite) {
        String name = displayName(uri);
        synchronized (this) {
            String id = "f" + ids.incrementAndGet();
            incoming.put(id, new Incoming(uri, name));
            pending.add(id);
            if (canWrite) remember(name, uri);
            return id;
        }
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

    /** Note that a document called {@code name} may be saved back to {@code uri}. */
    synchronized void remember(String name, Uri uri) {
        Set<Uri> uris = writable.get(name);
        if (uris == null) {
            uris = new LinkedHashSet<>();
            writable.put(name, uris);
        }
        uris.add(uri);
    }

    /** Make {@code uri} where later saves of {@code name} go (after a Save As, say). */
    synchronized void prefer(String name, Uri uri) {
        preferred.put(name, uri);
        remember(name, uri);
    }

    /** Where a Save of {@code name} should be written, or null when that isn't known. */
    synchronized Uri targetFor(String name) {
        Uri uri = preferred.get(name);
        if (uri != null) return uri;
        Set<Uri> uris = writable.get(name);
        // Two writable files with the same name: we can't tell which one this document is.
        return uris != null && uris.size() == 1 ? uris.iterator().next() : null;
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

    // ---- saving ----

    /** Start spooling a save; returns the id the chunks are sent with. */
    String begin(String name, String mime, boolean saveAs, String kind) throws IOException {
        File dir = new File(context.getCacheDir(), "saves");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        String safe = safeName(name == null || name.trim().isEmpty() ? "document.pdf" : name);
        String id = "s" + ids.incrementAndGet();
        File file = new File(dir, id + ".part");
        SaveJob job = new SaveJob(safe, mimeFor(safe, mime), saveAs, kind, file);
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
