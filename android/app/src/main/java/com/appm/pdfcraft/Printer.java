package com.appm.pdfcraft;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.util.Base64;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prints through Android's print framework. PdfCraft's browser print draws each sheet as a PNG
 * and calls {@code print()} on a hidden frame, which Android's WebView ignores; the shell hands
 * those sheets here instead, and they become a PDF for {@link android.print.PrintManager}.
 */
final class Printer {
    private static final class Page {
        final double widthPt;
        final double heightPt;
        final boolean turned;
        final File png;

        Page(double widthPt, double heightPt, boolean turned, File png) {
            this.widthPt = widthPt;
            this.heightPt = heightPt;
            this.turned = turned;
            this.png = png;
        }
    }

    private static final class Job {
        final String name;
        final File dir;
        final List<Page> pages = new ArrayList<>();

        Job(String name, File dir) {
            this.name = name;
            this.dir = dir;
        }
    }

    /** A finished print-ready PDF. */
    static final class Document {
        final String name;
        final File pdf;
        final int pageCount;
        final File dir;

        Document(String name, File pdf, int pageCount, File dir) {
            this.name = name;
            this.pdf = pdf;
            this.pageCount = pageCount;
            this.dir = dir;
        }
    }

    /**
     * The most pixels decoded at once (16 MB). A PNG can only be read from its start, so each strip
     * rereads the rows above it, and fewer, taller strips are faster.
     */
    private static final int STRIP_PIXELS = 1 << 22;

    private final File root;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, Job> jobs = new HashMap<>();

    Printer(Context context) {
        root = new File(context.getCacheDir(), "print");
        deleteTree(root); // Leftovers from a print the app didn't finish.
    }

    String begin(String name) throws IOException {
        String id = "p" + ids.incrementAndGet();
        File dir = new File(root, id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        String jobName = name == null || name.trim().isEmpty() ? "PdfCraft" : name.trim();
        synchronized (this) {
            jobs.put(id, new Job(jobName, dir));
        }
        return id;
    }

    void addPage(String id, double widthPt, double heightPt, boolean turned, String base64Png) throws IOException {
        Job job;
        synchronized (this) {
            job = jobs.get(id);
        }
        if (job == null) throw new IOException("unknown print job " + id);
        if (!(widthPt > 0 && heightPt > 0)) throw new IllegalArgumentException("bad sheet size");
        File png = new File(job.dir, job.pages.size() + ".png");
        try (OutputStream out = new FileOutputStream(png)) {
            out.write(Base64.decode(base64Png, Base64.DEFAULT));
        }
        job.pages.add(new Page(widthPt, heightPt, turned, png));
    }

    void abort(String id) {
        Job job;
        synchronized (this) {
            job = jobs.remove(id);
        }
        if (job != null) deleteTree(job.dir);
    }

    /** Write the sheets into a PDF, one at a time. Slow: call it off the UI thread. */
    Document build(String id) throws IOException {
        Job job;
        synchronized (this) {
            job = jobs.remove(id);
        }
        if (job == null) throw new IOException("unknown print job " + id);
        if (job.pages.isEmpty()) {
            deleteTree(job.dir);
            throw new IOException("nothing to print");
        }
        File pdf = new File(job.dir, "print.pdf");
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(pdf), 1 << 16);
                    PrintPdfWriter writer = new PrintPdfWriter(out)) {
                int number = 0;
                for (Page sheet : job.pages) {
                    writeSheet(writer, sheet, ++number);
                    //noinspection ResultOfMethodCallIgnored
                    sheet.png.delete();
                }
                writer.finish();
            }
            return new Document(job.name, pdf, job.pages.size(), job.dir);
        } catch (IOException | RuntimeException e) {
            // The sheets and the unfinished PDF go with the job's folder.
            deleteTree(job.dir);
            throw e;
        }
    }

    /**
     * Sheet {@code number}'s picture into {@code writer}, decoded a strip of rows at a time. Decoded
     * whole, a sheet takes up to 256 MB (64 megapixels).
     */
    private static void writeSheet(PrintPdfWriter writer, Page sheet, int number) throws IOException {
        BitmapRegionDecoder decoder;
        try {
            decoder = newDecoder(sheet.png.getPath());
        } catch (IOException e) {
            throw new IOException("sheet " + number + " isn't a picture", e);
        }
        if (decoder == null) throw new IOException("sheet " + number + " isn't a picture");
        try {
            int width = decoder.getWidth();
            int height = decoder.getHeight();
            if (width <= 0 || height <= 0) throw new IOException("sheet " + number + " isn't a picture");
            writer.beginSheet(sheet.widthPt, sheet.heightPt, sheet.turned, width, height);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            int strip = Math.max(1, Math.min(height, STRIP_PIXELS / width));
            int[] row = new int[width];
            for (int top = 0; top < height; top += strip) {
                int rows = Math.min(strip, height - top);
                Bitmap bitmap = decoder.decodeRegion(new Rect(0, top, width, top + rows), options);
                if (bitmap == null) throw new IOException("sheet " + number + " can't be read");
                try {
                    if (bitmap.getWidth() != width || bitmap.getHeight() != rows) {
                        throw new IOException("sheet " + number + " can't be read");
                    }
                    for (int y = 0; y < rows; y++) {
                        bitmap.getPixels(row, 0, width, 0, y, width, 1);
                        writer.writeRow(row);
                    }
                } finally {
                    bitmap.recycle();
                }
            }
            writer.endSheet();
        } finally {
            decoder.recycle();
        }
    }

    @SuppressWarnings("deprecation") // newInstance(String, boolean) is the only one before Android 12.
    private static BitmapRegionDecoder newDecoder(String path) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return BitmapRegionDecoder.newInstance(path);
        return BitmapRegionDecoder.newInstance(path, false);
    }

    /** Hands a built PDF to the print framework, and deletes it once the framework is done. */
    static PrintDocumentAdapter adapter(Document document) {
        return new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal cancel,
                    LayoutResultCallback callback, Bundle extras) {
                if (cancel.isCanceled()) {
                    callback.onLayoutCancelled();
                    return;
                }
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(document.name + ".pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(document.pageCount)
                        .build();
                callback.onLayoutFinished(info, oldAttributes == null || !oldAttributes.equals(newAttributes));
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination, CancellationSignal cancel,
                    WriteResultCallback callback) {
                try (InputStream in = new FileInputStream(document.pdf);
                        OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        if (cancel.isCanceled()) {
                            callback.onWriteCancelled();
                            return;
                        }
                        out.write(buffer, 0, n);
                    }
                    callback.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
                } catch (IOException e) {
                    callback.onWriteFailed(e.getMessage());
                }
            }

            @Override
            public void onFinish() {
                deleteTree(document.dir);
            }
        };
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
