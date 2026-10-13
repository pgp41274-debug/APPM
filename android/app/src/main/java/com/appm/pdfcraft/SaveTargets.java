package com.appm.pdfcraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where a save of each document should go: {@link FileStore}'s rules, kept apart from Android so
 * scripts/test-save-targets.sh can test them on a desktop JVM. {@code F} names a file (a content
 * URI in the app).
 *
 * <p>A save is matched to its document, never to a file name alone: two files can share a name,
 * and PdfCraft names the documents it makes itself. A document opened from here ("Open with",
 * share) carries the id it was handed in with; any other one is known by PdfCraft's number for
 * it, which holds until PdfCraft reloads. Each such key is bound to where its saves go: the file
 * it came from when that may be written, the file a Save As chose, or the Downloads/PdfCraft
 * copy an earlier save made. A key bound to nothing gets a new copy. When PdfCraft closes the
 * document, its key lets go of that file, so the file can be opened and saved to again.
 */
final class SaveTargets<F> {
    private static final String DOC_KEY = "doc:";
    /** Names PdfCraft gives documents it makes (create_ui.rs, Combine): never a picked file. */
    private static final List<String> MADE_NAMES = Arrays.asList("Untitled.pdf", "Clipboard.pdf", "Images.pdf", "Combined.pdf");

    /** Ends every key, so a save kept from an ended process can't bind a document of this one. */
    private final String tag = "@" + UUID.randomUUID().toString().substring(0, 8);
    /** Where saves of each document go, by key. */
    private final Map<String, F> targets = new HashMap<>();
    /**
     * Every file PdfCraft was given, under each name a document made from it can have: its own,
     * and "{stem}.pdf" for the new PDF PdfCraft makes of an image or text file. Only to tell when
     * a name is ambiguous; a file is saved to only under its own name ({@link #pickedWritable}).
     */
    private final Map<String, Set<F>> sources = new HashMap<>();
    /** Files picked in PdfCraft's own Open that may be written, with each one's own name. */
    private final Map<F, String> pickedWritable = new HashMap<>();
    /** Files handed in by id ("Open with", share), with the name each came under, until PdfCraft lets go of them. */
    private final Map<String, Handed<F>> handed = new HashMap<>();

    private static final class Handed<F> {
        final F file;
        final String name;

        Handed(F file, String name) {
            this.file = file;
            this.name = name;
        }
    }
    /** PdfCraft's starts in the WebView so far: its document numbers begin again with each. */
    private int page;

    /** File {@code id}, called {@code name}, goes to PdfCraft; {@code canWrite} if a Save may overwrite it. */
    synchronized void addIncoming(String id, String name, F file, boolean canWrite) {
        addSource(name, file);
        handed.put(id, new Handed<>(file, name));
        if (canWrite) targets.put(id + tag, file);
    }

    /** A file called {@code name} was picked in PdfCraft's own Open; {@code canWrite} if a Save may overwrite it. */
    synchronized void addPicked(String name, F file, boolean canWrite) {
        addSource(name, file);
        if (canWrite) pickedWritable.put(file, name);
    }

    /** {@code file} can become a document called {@code name}, or "{stem}.pdf" (an image or text file). */
    private void addSource(String name, F file) {
        int dot = name.lastIndexOf('.');
        for (String n : new String[] {name, (dot >= 0 ? name.substring(0, dot) : name) + ".pdf"}) {
            Set<F> files = sources.get(n);
            if (files == null) {
                files = new HashSet<>();
                sources.put(n, files);
            }
            files.add(file);
        }
    }

    /**
     * The key of the document that {@code hostId} (the id it was opened with) or else {@code doc}
     * (PdfCraft's number for it) names, or null when both are empty.
     */
    synchronized String keyFor(String hostId, String doc) {
        if (hostId != null && !hostId.isEmpty()) return hostId + tag;
        if (doc != null && !doc.isEmpty()) return DOC_KEY + page + ":" + doc + tag;
        return null;
    }

    /**
     * PdfCraft (re)started: forget its old document numbers, which it now hands out again. A
     * restart (the WebView's renderer was killed) takes the old page's documents with it without
     * a close for any of them, so also let go of every file it was handed, except those still
     * {@code waiting} for the new page to fetch.
     */
    synchronized void newPage(Collection<String> waiting) {
        page++;
        targets.keySet().removeIf(key -> key.startsWith(DOC_KEY));
        for (String id : new ArrayList<>(handed.keySet())) {
            if (!waiting.contains(id)) {
                targets.remove(id + tag);
                release(id);
            }
        }
    }

    /**
     * Where a Save of document {@code key}, called {@code name}, should be written, or null for a
     * new copy in Downloads/PdfCraft. A document from PdfCraft's own Open has no id, only its
     * name: its first save may go back to a picked file only when that file has this name itself
     * (so it isn't an image or text file PdfCraft made a new PDF of), nothing else PdfCraft was
     * given could have made a document of that name, and no other document saves there. That
     * file is then bound to it.
     */
    synchronized F targetFor(String key, String name) {
        if (key == null) return null;
        F bound = targets.get(key);
        if (bound != null || !key.startsWith(DOC_KEY) || MADE_NAMES.contains(name)) return bound;
        Set<F> files = sources.get(name);
        if (files == null || files.size() != 1) return null;
        F file = files.iterator().next();
        if (!name.equals(pickedWritable.get(file)) || targets.containsValue(file)) return null;
        targets.put(key, file);
        return file;
    }

    /** Make {@code file} where later saves of document {@code key} go. */
    synchronized void bind(String key, F file) {
        if (key != null) targets.put(key, file);
    }

    /** {@link #bind}, unless saves of {@code key} go somewhere already. */
    synchronized void bindIfUnbound(String key, F file) {
        if (key != null) targets.putIfAbsent(key, file);
    }

    /** Document {@code key} is closed: its file is free for the next document opened from it. */
    synchronized void forget(String key) {
        if (key == null) return;
        targets.remove(key);
        if (!key.startsWith(DOC_KEY) && key.endsWith(tag)) release(key.substring(0, key.length() - tag.length()));
    }

    /**
     * File {@code id} is no longer open in PdfCraft. Unless another open document came from the
     * same file, stop counting it under its own name, so that picking it again in PdfCraft's Open
     * (often under a different URI than "Open with" gave) isn't taken for a second file of that
     * name. Its "{stem}.pdf" entry stays: a PDF PdfCraft made of an image may still be open.
     */
    private void release(String id) {
        Handed<F> gone = handed.remove(id);
        if (gone == null) return;
        for (Handed<F> other : handed.values()) {
            if (other.file.equals(gone.file)) return;
        }
        Set<F> files = sources.get(gone.name);
        if (files == null) return;
        files.remove(gone.file);
        if (files.isEmpty()) sources.remove(gone.name);
    }
}
