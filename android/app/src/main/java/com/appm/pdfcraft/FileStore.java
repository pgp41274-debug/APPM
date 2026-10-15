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
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.os.BundleCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        final boolean canWrite;

        Incoming(Uri uri, String name, boolean canWrite) {
            this.uri = uri;
            this.name = name;
            this.canWrite = canWrite;
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
        /**
         * Where the bytes go, for a write an earlier process began and didn't finish
         * ({@link #interruptedWrites}); null for every other job.
         */
        final Uri resume;
        /**
         * For a {@link #resume}d write whose new bytes are gone: {@link #file} is what the file
         * held before, to be put back ({@link FileStore#restore}).
         */
        final boolean restore;

        SaveJob(String name, String mime, boolean saveAs, String kind, String key, File file) {
            this(name, mime, saveAs, kind, key, file, null, false);
        }

        private SaveJob(String name, String mime, boolean saveAs, String kind, String key, File file, Uri resume, boolean restore) {
            this.name = name;
            this.mime = mime;
            this.saveAs = saveAs;
            this.kind = kind;
            this.key = key;
            this.file = file;
            this.resume = resume;
            this.restore = restore;
        }

        /** A write an earlier process began ({@link FileStore#interruptedWrites}), to finish or undo. */
        static SaveJob resumed(String name, String mime, File file, Uri uri, boolean restore) {
            return new SaveJob(name, mime, false, "save", null, file, uri, restore);
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
            if (resume != null) b.putString("resume", resume.toString());
            b.putBoolean("restore", restore);
            return b;
        }

        /** The job {@link #toBundle} kept, or null if there is none or its bytes are gone. */
        static SaveJob fromBundle(Bundle b) {
            if (b == null) return null;
            String name = b.getString("name");
            String path = b.getString("file");
            if (name == null || path == null || !new File(path).isFile()) return null;
            String resume = b.getString("resume");
            return new SaveJob(name, b.getString("mime"), b.getBoolean("saveAs"), b.getString("kind"), b.getString("key"),
                    new File(path), resume == null ? null : Uri.parse(resume), b.getBoolean("restore"));
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
    /** Bytes that couldn't be saved anywhere, open again in a tab ({@link #reopen}), by that tab's id. */
    private final Map<String, File> unsaved = new HashMap<>();

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
        return addIncoming(uri, displayName(uri), canWrite);
    }

    /** {@link #addIncoming(Uri, boolean)} for a file whose name is known. */
    String addIncoming(Uri uri, String name, boolean canWrite) {
        synchronized (this) {
            String id = "f" + ids.incrementAndGet();
            incoming.put(id, new Incoming(uri, name, canWrite));
            pending.add(id);
            targets.addIncoming(id, name, uri, canWrite);
            return id;
        }
    }

    /** Note a file picked in PdfCraft's own Open; {@code canWrite} if a Save may overwrite it. */
    void addPicked(Uri uri, boolean canWrite) {
        targets.addPicked(displayName(uri), uri, canWrite);
    }

    /**
     * The files handed in that the page hasn't taken yet, as instance state: if Android ends the
     * process before PdfCraft is ready, the next one opens them ({@link #restoreWaiting}). Their
     * grants last as long as the activity. Only files from other apps: bytes kept by
     * {@link #reopen} come back by themselves.
     */
    synchronized ArrayList<Bundle> waiting() {
        ArrayList<Bundle> out = new ArrayList<>();
        for (String id : pending) {
            Incoming in = incoming.get(id);
            if (in == null || !"content".equals(in.uri.getScheme())) continue;
            Bundle b = new Bundle();
            b.putParcelable("uri", in.uri);
            b.putString("name", in.name);
            b.putBoolean("canWrite", in.canWrite);
            out.add(b);
        }
        return out;
    }

    /** Queue again the files {@link #waiting} kept. */
    void restoreWaiting(List<Bundle> waiting) {
        for (Bundle b : waiting) {
            Uri uri = BundleCompat.getParcelable(b, "uri", Uri.class);
            String name = b.getString("name");
            if (uri != null && name != null) addIncoming(uri, name, b.getBoolean("canWrite"));
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
            } catch (JSONException ignored) {
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

    /**
     * PdfCraft (re)started: forget its old document numbers and the files its old page had
     * already taken, keeping those still queued for the new page ({@link SaveTargets#newPage}).
     */
    synchronized void newPage() {
        targets.newPage(new ArrayList<>(pending));
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
        if (spool != null) discard(spool);
    }

    /** The page died: the saves it was still sending will never be finished. */
    void abortAll() {
        List<Spool> all;
        synchronized (this) {
            all = new ArrayList<>(spools.values());
            spools.clear();
        }
        for (Spool spool : all) discard(spool);
    }

    private static void discard(Spool spool) {
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

    // ---- writing ----

    /** Notes on writes in progress, and what each file held before; kept until the write is done. */
    private File writingDir() {
        return new File(context.getNoBackupFilesDir(), "writing");
    }

    /**
     * Replace what {@code uri} holds with {@code job}'s bytes without risking either. What the
     * file held is copied aside first and put back if the write fails; a note on disk lets the
     * next process finish the write ({@link #interruptedWrites}) if this one ends in the middle.
     * Throws, leaving {@code uri} as it was, if it can't be read or written.
     */
    void replace(Uri uri, SaveJob job) throws IOException {
        File dir = writingDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        String base = job.file.getName(); // Unique: the spool's temporary name.
        File backup = new File(dir, base + ".bak");
        File note = new File(dir, base + ".json");
        // A write an earlier process began keeps the copy taken before it: the file itself may be
        // cut short now.
        boolean resumed = backup.isFile();
        if (!resumed) {
            // The note first: if the process ends while the file is being copied aside, the next
            // one still has the save to finish.
            writeNote(note, uri, job);
            File part = new File(dir, base + ".bak.part");
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) throw new FileNotFoundException("can't read " + uri);
                copy(in, part);
                if (!part.renameTo(backup)) throw new IOException("can't keep a copy of " + uri);
            } catch (IOException | RuntimeException e) {
                delete(part, note); // The file is untouched.
                throw e;
            }
        }
        OutputStream out;
        try {
            out = openForWrite(uri); // Empties the file.
        } catch (IOException | RuntimeException e) {
            // Not writable: untouched, unless an earlier process had begun writing it. Then what
            // it held goes to Downloads, or stays here for the next process to try again.
            if (!resumed || keepCopy(beforeSaving(job.name), job.mime, backup)) delete(note, backup);
            throw e;
        }
        try {
            copy(job.file, out);
        } catch (IOException | RuntimeException e) {
            boolean kept;
            try {
                writeTo(uri, backup);
                kept = true;
            } catch (IOException | RuntimeException again) {
                // The file is cut short and can't be mended: keep what it held in Downloads.
                e.addSuppressed(again);
                kept = keepCopy(beforeSaving(job.name), job.mime, backup);
            }
            if (kept) delete(note, backup); // Else the next process tries again.
            throw e;
        }
        delete(note, backup);
    }

    /**
     * Put back what {@code uri} held before a write an earlier process didn't finish and whose
     * new bytes are gone ({@link SaveJob#restore}). Returns false if it can't be reached any more
     * (its grant ended with the task) and what it held went to Downloads as
     * {@link #beforeSaving}; throws if even that failed, leaving everything for the next process.
     */
    boolean restore(Uri uri, SaveJob job) throws IOException {
        File backup = job.file;
        String base = backup.getName().substring(0, Math.max(0, backup.getName().length() - ".bak".length()));
        File note = new File(backup.getParentFile(), base + ".json");
        try {
            writeTo(uri, backup);
            delete(note, backup);
            return true;
        } catch (IOException | RuntimeException e) {
            if (!keepCopy(beforeSaving(job.name), job.mime, backup)) throw e;
            delete(note, backup);
            return false;
        }
    }

    /**
     * The writes ({@link #replace}) an earlier process began and didn't finish, as saves to write
     * again: from the bytes it kept, or else putting back what the file held before. Once per
     * process, before {@link #sweepSpools}, which must keep their files.
     */
    List<SaveJob> interruptedWrites() {
        List<SaveJob> jobs = new ArrayList<>();
        synchronized (FileStore.class) {
            if (swept) return jobs;
        }
        File dir = writingDir();
        File[] all = dir.listFiles();
        if (all == null) return jobs;
        Set<String> notes = new HashSet<>();
        for (File f : all) {
            String n = f.getName();
            if (n.endsWith(".json")) notes.add(n.substring(0, n.length() - ".json".length()));
        }
        // What a process left half done: a copy or note it was still writing, a copy with no note.
        for (File f : all) {
            String n = f.getName();
            if (n.endsWith(".part") || (n.endsWith(".bak") && !notes.contains(n.substring(0, n.length() - ".bak".length())))) {
                delete(f);
            }
        }
        for (String base : notes) {
            File note = new File(dir, base + ".json");
            File backup = new File(dir, base + ".bak");
            try {
                JSONObject o = new JSONObject(new String(readAll(note), StandardCharsets.UTF_8));
                Uri uri = Uri.parse(o.getString("uri"));
                File bytes = new File(o.getString("file"));
                String name = o.getString("name");
                String mime = o.optString("mime", "application/pdf");
                if (bytes.isFile()) {
                    // Write it again; from the start if the file was never touched (no copy yet).
                    jobs.add(SaveJob.resumed(name, mime, bytes, uri, false));
                } else if (backup.isFile()) {
                    // The new bytes are gone (the cache was cleared): put back what the file held.
                    jobs.add(SaveJob.resumed(name, mime, backup, uri, true));
                } else {
                    delete(note); // Nothing was written, and there is nothing left to write.
                }
            } catch (IOException | JSONException | RuntimeException e) {
                delete(note, backup);
            }
        }
        return jobs;
    }

    private void writeNote(File note, Uri uri, SaveJob job) throws IOException {
        String json;
        try {
            json = new JSONObject()
                    .put("uri", uri.toString())
                    .put("file", job.file.getPath())
                    .put("name", job.name)
                    .put("mime", job.mime)
                    .toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File part = new File(note.getPath() + ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!part.renameTo(note)) throw new IOException("can't write " + note);
        } catch (IOException | RuntimeException e) {
            delete(part);
            throw e;
        }
    }

    private static void delete(File... files) {
        //noinspection ResultOfMethodCallIgnored
        for (File f : files) f.delete();
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void copy(InputStream in, File to) throws IOException {
        try (OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
    }

    /**
     * Keep {@code file} in Downloads/PdfCraft as {@code name}; false if that failed. An empty
     * file (a new document from the "Save as" picker held nothing yet) needs no keeping.
     */
    private boolean keepCopy(String name, String mime, File file) {
        if (file.length() == 0) return true;
        try {
            saveToDownloads(name, mime, file);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e("PdfCraft", "can't keep " + name, e);
            return false;
        }
    }

    // ---- bytes no save could write ----

    private File unsavedDir() {
        return new File(context.getNoBackupFilesDir(), "unsaved");
    }

    /**
     * Keep the bytes of a save that couldn't be written anywhere, and open them again in a tab.
     * They stay, and open again after a restart ({@link #reopenAll}), until that document is
     * saved or closed ({@link #settled}). Returns the tab's id.
     */
    String reopen(SaveJob job) throws IOException {
        File dir = new File(unsavedDir(), job.file.getName());
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File kept = new File(dir, job.name);
        if (!job.file.renameTo(kept)) throw new IOException("can't keep " + job.name);
        synchronized (this) {
            String id = addIncoming(Uri.fromFile(kept), job.name, false);
            unsaved.put(id, kept);
            return id;
        }
    }

    /**
     * A new page or process: open the bytes {@link #reopen} kept in tabs again, except those
     * still waiting to be opened.
     */
    synchronized void reopenAll() {
        Set<File> waiting = new HashSet<>();
        for (Iterator<Map.Entry<String, File>> it = unsaved.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, File> e = it.next();
            if (pending.contains(e.getKey())) waiting.add(e.getValue());
            else it.remove();
        }
        File[] dirs = unsavedDir().listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            File[] kept = dir.listFiles();
            if (kept == null || kept.length == 0) {
                delete(dir);
                continue;
            }
            if (waiting.contains(kept[0])) continue;
            unsaved.put(addIncoming(Uri.fromFile(kept[0]), kept[0].getName(), false), kept[0]);
        }
    }

    /** Document {@code key} was saved or closed: bytes {@link #reopen} kept for it can go. */
    synchronized void settled(String key) {
        if (key == null) return;
        for (Iterator<Map.Entry<String, File>> it = unsaved.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, File> e = it.next();
            if (key.equals(targets.keyFor(e.getKey(), ""))) {
                delete(e.getValue(), e.getValue().getParentFile());
                it.remove();
            }
        }
    }

    /** "Report.pdf" becomes "Report (before saving).pdf". */
    static String beforeSaving(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " (before saving)" + name.substring(dot) : name + " (before saving)";
    }

    /** Copy {@code file} over the contents of {@code uri}. */
    private void writeTo(Uri uri, File file) throws IOException {
        copy(file, openForWrite(uri));
    }

    /** Copy {@code file} to {@code out}, and close it. */
    private static void copy(File file, OutputStream out) throws IOException {
        try (OutputStream o = out; InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) o.write(buffer, 0, n);
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
        Uri uri;
        try {
            uri = newDownload(name, mime);
        } catch (IOException | IllegalStateException e) {
            // Android numbers a taken name only so far ("x (31).pdf") and then refuses it.
            uri = newDownload(stamped(name), mime);
        }
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

    private Uri newDownload(String name, String mime) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOADS_FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Downloads refused the file");
        return uri;
    }

    /** "Report.pdf" becomes "Report 2026-10-14 091500.pdf". */
    static String stamped(String name) {
        String time = new SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.ROOT).format(new Date());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " " + time + name.substring(dot) : name + " " + time;
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
