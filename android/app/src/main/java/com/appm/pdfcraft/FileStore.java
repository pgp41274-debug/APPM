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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The files PdfCraft opened and saved, and where a save of each document should go.
 *
 * <p>A save is matched to its document, never to a file name alone: two files can share a name,
 * and PdfCraft names the documents it makes itself. A document opened from here ("Open with",
 * share) carries the id it was handed in with; any other one is known by PdfCraft's number for
 * it, which holds until PdfCraft reloads. Each such key is bound to where its saves go: the file
 * it came from when that may be written, the file a Save As chose, or the Downloads/PdfCraft
 * copy an earlier save made. A key bound to nothing gets a new copy.
 */
final class FileStore {
    static final String DOWNLOADS_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/PdfCraft";
    private static final String DOC_KEY = "doc:";
    /** Names PdfCraft gives documents it makes (create_ui.rs, Combine): never a picked file. */
    private static final List<String> MADE_NAMES = Arrays.asList("Untitled.pdf", "Clipboard.pdf", "Images.pdf", "Combined.pdf");

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

    /** Bytes PdfCraft handed over, spooled to a temporary file, and where they are meant to go. */
    static final class SaveJob {
        final String name;
        final String mime;
        final boolean saveAs;
        /** {@code "save"} for a document save, {@code "export"} for any other download. */
        final String kind;
        /** The document saved (see {@link FileStore}), or null: an export, or no id came. */
        final String key;
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

        /** The job as instance state, so it outlives the process while a picker is open. */
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
    /** Ends every key, so a save kept from an ended process can't bind a document of this one. */
    private final String tag = "@" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, Incoming> incoming = new HashMap<>();
    private final List<String> pending = new ArrayList<>();
    /** Where saves of each document go, by key. */
    private final Map<String, Uri> targets = new HashMap<>();
    /** Every file PdfCraft was given, under each name a document made from it can have. */
    private final Map<String, Set<Uri>> sources = new HashMap<>();
    /** Files picked in PdfCraft's own Open that may be written. */
    private final Set<Uri> pickedWritable = new HashSet<>();
    private final Map<String, Spool> spools = new HashMap<>();
    /** PdfCraft's starts in the WebView so far: its document numbers begin again with each. */
    private int page;

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
            addSource(name, uri);
            if (canWrite) targets.put(id + tag, uri);
            return id;
        }
    }

    /** Note a file picked in PdfCraft's own Open; {@code canWrite} if a Save may overwrite it. */
    void addPicked(Uri uri, boolean canWrite) {
        String name = displayName(uri);
        synchronized (this) {
            addSource(name, uri);
            if (canWrite) pickedWritable.add(uri);
        }
    }

    /** {@code uri} can become a document called {@code name}, or "{stem}.pdf" (an image or text file). */
    private void addSource(String name, Uri uri) {
        int dot = name.lastIndexOf('.');
        for (String n : new String[] {name, (dot >= 0 ? name.substring(0, dot) : name) + ".pdf"}) {
            Set<Uri> uris = sources.get(n);
            if (uris == null) {
                uris = new HashSet<>();
                sources.put(n, uris);
            }
            uris.add(uri);
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
    synchronized void newPage() {
        page++;
        targets.keySet().removeIf(key -> key.startsWith(DOC_KEY));
    }

    /**
     * Where a Save of document {@code key}, called {@code name}, should be written, or null for a
     * new copy in Downloads/PdfCraft. A document from PdfCraft's own Open has no id, only its
     * name: its first save may go back to the picked file only when nothing else PdfCraft was
     * given could have made a document of that name, and no other document saves there. That
     * file is then bound to it.
     */
    synchronized Uri targetFor(String key, String name) {
        if (key == null) return null;
        Uri bound = targets.get(key);
        if (bound != null || !key.startsWith(DOC_KEY) || MADE_NAMES.contains(name)) return bound;
        Set<Uri> uris = sources.get(name);
        if (uris == null || uris.size() != 1) return null;
        Uri uri = uris.iterator().next();
        if (!pickedWritable.contains(uri) || targets.containsValue(uri)) return null;
        targets.put(key, uri);
        return uri;
    }

    /** Make {@code uri} where later saves of document {@code key} go. */
    synchronized void bind(String key, Uri uri) {
        if (key != null) targets.put(key, uri);
    }

    /** {@link #bind}, unless saves of {@code key} go somewhere already. */
    synchronized void bindIfUnbound(String key, Uri uri) {
        if (key != null) targets.putIfAbsent(key, uri);
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
        String key = null;
        if ("save".equals(kind)) {
            synchronized (this) {
                if (hostId != null && !hostId.isEmpty()) key = hostId + tag;
                else if (doc != null && !doc.isEmpty()) key = DOC_KEY + page + ":" + doc + tag;
            }
        }
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

    /** Delete the empty document a "save to" picker created, where its provider allows. */
    void deleteCreated(Uri uri) {
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
