package com.appm.pdfcraft;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.util.Base64;

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

    /** Draw the sheets into a PDF. Slow: call it off the UI thread. */
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
        PdfDocument doc = new PdfDocument();
        try {
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            int number = 0;
            for (Page sheet : job.pages) {
                int w = Math.max(1, (int) Math.round(sheet.widthPt));
                int h = Math.max(1, (int) Math.round(sheet.heightPt));
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(w, h, ++number).create());
                Bitmap bitmap = BitmapFactory.decodeFile(sheet.png.getPath());
                if (bitmap == null) throw new IOException("sheet " + number + " isn't a picture");
                Canvas canvas = page.getCanvas();
                if (sheet.turned) {
                    // The picture is drawn landscape on a portrait sheet, as PdfCraft's print page
                    // does it: translate(0, h) rotate(-90deg).
                    canvas.save();
                    canvas.translate(0, h);
                    canvas.rotate(-90);
                    canvas.drawBitmap(bitmap, null, new RectF(0, 0, h, w), paint);
                    canvas.restore();
                } else {
                    canvas.drawBitmap(bitmap, null, new RectF(0, 0, w, h), paint);
                }
                bitmap.recycle();
                doc.finishPage(page);
                //noinspection ResultOfMethodCallIgnored
                sheet.png.delete();
            }
            File pdf = new File(job.dir, "print.pdf");
            try (OutputStream out = new FileOutputStream(pdf)) {
                doc.writeTo(out);
            }
            return new Document(job.name, pdf, job.pages.size(), job.dir);
        } catch (IOException | RuntimeException e) {
            deleteTree(job.dir);
            throw e;
        } finally {
            doc.close();
        }
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
