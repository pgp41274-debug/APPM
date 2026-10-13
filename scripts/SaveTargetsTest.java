package com.appm.pdfcraft;

/**
 * Tests {@link SaveTargets}, the rules for where a Save goes, on a desktop JVM;
 * scripts/test-save-targets.sh runs it. Files are strings here (content URIs in the app), and
 * each case drives the calls FileStore and MainActivity make: a file handed in ("Open with") or
 * picked in PdfCraft's Open, saves of a document by its key, a Save As, a close.
 */
final class SaveTargetsTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        convertedImage();
        pickedPdf();
        ambiguousName();
        openWithAndPicked();
        madeNames();
        busyFile();
        closeFreesFile();
        closeAfterOpenWith();
        closeAfterSaveAs();
        newPage();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    /** What a Save of the document does in MainActivity.write: its target, else a new copy. */
    private static String save(SaveTargets<String> t, String key, String name, String copy) {
        String target = t.targetFor(key, name);
        if (target != null) return target;
        t.bind(key, copy);
        return copy;
    }

    /** Picked receipt.jpg (writable): PdfCraft makes a new receipt.pdf of it, whose Save never overwrites the picture. */
    private static void convertedImage() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("receipt.jpg", "content://docs/receipt.jpg", true);
        String doc = t.keyFor("", "1");
        check("a PDF made from a picked image is not saved over the image", t.targetFor(doc, "receipt.pdf") == null);
        check("... nor on its second save",
                "downloads/receipt.pdf".equals(save(t, doc, "receipt.pdf", "downloads/receipt.pdf"))
                        && "downloads/receipt.pdf".equals(t.targetFor(doc, "receipt.pdf")));
        // The picture itself, picked and opened as a PDF called receipt.jpg (as PdfCraft would
        // open a PDF with that name), still saves back to itself.
        check("a document with the picked file's own name saves to it",
                "content://docs/receipt.jpg".equals(t.targetFor(t.keyFor("", "2"), "receipt.jpg")));
    }

    /** Picked report.pdf (writable): its Save goes back to it, and stays there. */
    private static void pickedPdf() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("report.pdf", "content://docs/report.pdf", true);
        String doc = t.keyFor("", "1");
        check("Save of a picked writable PDF goes back to it", "content://docs/report.pdf".equals(t.targetFor(doc, "report.pdf")));
        check("... every time", "content://docs/report.pdf".equals(t.targetFor(doc, "report.pdf")));
        SaveTargets<String> readOnly = new SaveTargets<>();
        readOnly.newPage();
        readOnly.addPicked("report.pdf", "content://docs/report.pdf", false);
        check("a picked file that can't be written is never saved to", readOnly.targetFor(readOnly.keyFor("", "1"), "report.pdf") == null);
    }

    /** Picked scan.jpg and scan.pdf: a document called scan.pdf may be either, so it goes to neither. */
    private static void ambiguousName() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("scan.jpg", "content://docs/scan.jpg", true);
        t.addPicked("scan.pdf", "content://docs/scan.pdf", true);
        String converted = t.keyFor("", "1");
        check("Save of a scan.pdf made from scan.jpg, with scan.pdf picked too, binds to neither",
                t.targetFor(converted, "scan.pdf") == null);
        check("... nor does the picked scan.pdf's own Save", t.targetFor(t.keyFor("", "2"), "scan.pdf") == null);
    }

    /** "Open with" a read-only scan.pdf, and pick a writable scan.pdf in PdfCraft's Open. */
    private static void openWithAndPicked() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addIncoming("f1", "scan.pdf", "content://mail/scan.pdf", false);
        t.addPicked("scan.pdf", "content://docs/scan.pdf", true);
        String openWith = t.keyFor("f1", "1");
        check("the Open-with document never binds to the picked file of its name", t.targetFor(openWith, "scan.pdf") == null);
        check("... nor after a save made it a copy",
                "downloads/scan.pdf".equals(save(t, openWith, "scan.pdf", "downloads/scan.pdf"))
                        && "downloads/scan.pdf".equals(t.targetFor(openWith, "scan.pdf")));
        // The same file both ways: the picked document may have it, the Open-with one still not.
        SaveTargets<String> same = new SaveTargets<>();
        same.newPage();
        same.addIncoming("f1", "scan.pdf", "content://docs/scan.pdf", false);
        same.addPicked("scan.pdf", "content://docs/scan.pdf", true);
        check("the Open-with document of a picked file doesn't save to it either", same.targetFor(same.keyFor("f1", "1"), "scan.pdf") == null);
        check("the picked document of that file does", "content://docs/scan.pdf".equals(same.targetFor(same.keyFor("", "2"), "scan.pdf")));
        // A writable "Open with" file is the Open-with document's own.
        SaveTargets<String> writable = new SaveTargets<>();
        writable.newPage();
        writable.addIncoming("f1", "scan.pdf", "content://docs/scan.pdf", true);
        writable.addPicked("scan.pdf", "content://docs/scan.pdf", true);
        check("a writable Open-with file is saved to by its document",
                "content://docs/scan.pdf".equals(writable.targetFor(writable.keyFor("f1", "1"), "scan.pdf")));
        check("... and not by a picked document of it while that one is open",
                writable.targetFor(writable.keyFor("", "2"), "scan.pdf") == null);
    }

    /** Names PdfCraft gives the documents it makes are never a picked file's. */
    private static void madeNames() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("Untitled.pdf", "content://docs/Untitled.pdf", true);
        check("a new blank document isn't saved over a picked Untitled.pdf", t.targetFor(t.keyFor("", "1"), "Untitled.pdf") == null);
    }

    /** Two documents from one picked file: the first to save has it, the other gets a copy. */
    private static void busyFile() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("report.pdf", "content://docs/report.pdf", true);
        String first = t.keyFor("", "1");
        String second = t.keyFor("", "2");
        check("the first document saves to the picked file", "content://docs/report.pdf".equals(t.targetFor(first, "report.pdf")));
        check("a second document of it doesn't while the first is open", t.targetFor(second, "report.pdf") == null);
    }

    /** Close a picked document, open the file again: the new document saves back to it. */
    private static void closeFreesFile() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("report.pdf", "content://docs/report.pdf", true);
        String first = t.keyFor("", "1");
        t.targetFor(first, "report.pdf");
        t.forget(first);
        t.addPicked("report.pdf", "content://docs/report.pdf", true);
        String again = t.keyFor("", "2");
        check("after its tab closes, the file reopened saves back to it",
                "content://docs/report.pdf".equals(t.targetFor(again, "report.pdf")));
        t.forget(t.keyFor("", "99"));
        t.forget(null);
        check("closing a document that never saved changes nothing", "content://docs/report.pdf".equals(t.targetFor(again, "report.pdf")));
    }

    /** Close an "Open with" document, then pick its file in PdfCraft's Open. */
    private static void closeAfterOpenWith() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addIncoming("f1", "report.pdf", "content://docs/report.pdf", true);
        String openWith = t.keyFor("f1", "1");
        check("the Open-with document saves to its file", "content://docs/report.pdf".equals(t.targetFor(openWith, "report.pdf")));
        t.forget(openWith);
        t.addPicked("report.pdf", "content://docs/report.pdf", true);
        check("once it is closed, the file picked again saves back to it",
                "content://docs/report.pdf".equals(t.targetFor(t.keyFor("", "2"), "report.pdf")));
        // A file PdfCraft never opened (an image it made a new PDF of, or one it couldn't read)
        // is let go of by its id alone.
        SaveTargets<String> failed = new SaveTargets<>();
        failed.newPage();
        failed.addIncoming("f1", "report.pdf", "content://docs/report.pdf", true);
        failed.forget(failed.keyFor("f1", ""));
        failed.addPicked("report.pdf", "content://docs/report.pdf", true);
        check("a file Android handed in that never opened is free for the next document",
                "content://docs/report.pdf".equals(failed.targetFor(failed.keyFor("", "1"), "report.pdf")));
    }

    /** Save As to a file, close the document, pick that file: its new document saves to it. */
    private static void closeAfterSaveAs() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("report.pdf", "content://docs/report.pdf", true);
        String doc = t.keyFor("", "1");
        t.targetFor(doc, "report.pdf");
        t.bind(doc, "content://docs/final.pdf"); // Save As chose final.pdf.
        check("after Save As the document saves to the new file", "content://docs/final.pdf".equals(t.targetFor(doc, "report.pdf")));
        t.addPicked("final.pdf", "content://docs/final.pdf", true);
        check("while it is open, final.pdf picked again gets a copy", t.targetFor(t.keyFor("", "2"), "final.pdf") == null);
        t.forget(doc);
        t.addPicked("final.pdf", "content://docs/final.pdf", true);
        check("once it is closed, final.pdf picked again saves to itself",
                "content://docs/final.pdf".equals(t.targetFor(t.keyFor("", "3"), "final.pdf")));
        check("... and so does report.pdf", "content://docs/report.pdf".equals(t.targetFor(t.keyFor("", "4"), "report.pdf")));
    }

    /** A restarted PdfCraft numbers documents afresh: an old number's file goes with it. */
    private static void newPage() {
        SaveTargets<String> t = new SaveTargets<>();
        t.newPage();
        t.addPicked("report.pdf", "content://docs/report.pdf", true);
        String old = t.keyFor("", "1");
        t.targetFor(old, "report.pdf");
        t.newPage();
        String renumbered = t.keyFor("", "1");
        check("document 1 of a restarted PdfCraft is another document", !old.equals(renumbered));
        check("... and may have the file document 1 had", "content://docs/report.pdf".equals(t.targetFor(renumbered, "report.pdf")));
        check("a key comes only with an id or a number", t.keyFor("", "") == null && t.keyFor(null, null) == null);
        check("an id names the document before its number", t.keyFor("f1", "1").equals(t.keyFor("f1", "2")));
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
    }
}
