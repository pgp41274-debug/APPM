package com.appm.pdfcraft;

import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes the PDF that {@link Printer} prints: one page per sheet, filled by the sheet's picture.
 * Each picture is compressed row by row as it arrives and none of it is kept, so memory doesn't
 * grow with the size or the number of sheets. It needs only java.io and java.util.zip, so
 * scripts/test-print-pdf.sh tests it on a desktop JVM.
 *
 * <p>For each sheet: {@link #beginSheet}, one {@link #writeRow} per pixel row from the top, then
 * {@link #endSheet}. Then {@link #finish}, and {@link #close} in any case.
 */
final class PrintPdfWriter implements Closeable {
    /** Counts the bytes written, for the cross-reference table. */
    private static final class Counter extends FilterOutputStream {
        long count;

        Counter(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private final Counter out;
    private Deflater deflater;
    /** Each object's byte offset, by object number. 1 is the catalog and 2 the page tree, both written last. */
    private long[] offsets = new long[64];
    private int objects = 2;
    private final StringBuilder kids = new StringBuilder();
    private int sheets;
    private boolean finished;

    // The sheet being written: its picture's stream, and the object its length goes into.
    private DeflaterOutputStream picture;
    private byte[] rgb;
    private int width;
    private int height;
    private int rows;
    private long pictureStart;
    private int lengthObject;

    PrintPdfWriter(OutputStream out) throws IOException {
        this.out = new Counter(out);
        // The header, and a comment of bytes above 127 that marks the file as binary.
        this.out.write(new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n', '%', (byte) 0xe2, (byte) 0xe3,
                (byte) 0xcf, (byte) 0xd3, '\n'});
    }

    /**
     * Start a page of {@code widthPt} by {@code heightPt} points, filled by a picture of {@code width}
     * by {@code height} pixels. A {@code turned} sheet's picture is landscape on a portrait page,
     * turned 90 degrees counter-clockwise as PdfCraft's print page does it: CSS {@code width:H;
     * height:W; transform-origin:0 0; transform:translate(0,H) rotate(-90deg)} on a page W wide and
     * H high.
     */
    void beginSheet(double widthPt, double heightPt, boolean turned, int width, int height) throws IOException {
        if (finished || picture != null) throw new IllegalStateException("not between sheets");
        int number = sheets + 1;
        if (!(Double.isFinite(widthPt) && Double.isFinite(heightPt) && widthPt >= 0.001 && heightPt >= 0.001)) {
            throw new IllegalArgumentException("sheet " + number + " has no usable size");
        }
        if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE / 3) {
            throw new IllegalArgumentException("sheet " + number + "'s picture is " + width + "x" + height + " pixels");
        }
        String w = number(widthPt);
        String h = number(heightPt);
        int page = ++objects;
        int contents = ++objects;
        int image = ++objects;
        lengthObject = ++objects;
        // The picture's unit square onto the page. Turned, that CSS puts the picture's point (x, y) at
        // (y, H - x) with y pointing down, which is (y, x) in PDF's upward y: the picture's top left at
        // the page's bottom left, its top right at the page's top left.
        String matrix = turned ? "0 " + h + " -" + w + " 0 " + w + " 0" : w + " 0 0 " + h + " 0 0";
        byte[] content = ("q " + matrix + " cm /Im0 Do Q").getBytes(StandardCharsets.US_ASCII);

        startObject(page);
        ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + w + " " + h + "] /Resources << /XObject << /Im0 "
                + image + " 0 R >> >> /Contents " + contents + " 0 R >>\nendobj\n");
        startObject(contents);
        ascii("<< /Length " + content.length + " >>\nstream\n");
        out.write(content);
        ascii("\nendstream\nendobj\n");
        startObject(image);
        ascii("<< /Type /XObject /Subtype /Image /Width " + width + " /Height " + height
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length " + lengthObject
                + " 0 R >>\nstream\n");
        kids.append(kids.length() == 0 ? "" : " ").append(page).append(" 0 R");

        if (deflater == null) deflater = new Deflater();
        deflater.reset();
        pictureStart = out.count;
        picture = new DeflaterOutputStream(out, deflater, 1 << 16);
        rgb = new byte[width * 3];
        this.width = width;
        this.height = height;
        rows = 0;
    }

    /**
     * The picture's next row: {@code width} non-premultiplied ARGB colors, as {@code Bitmap.getPixels}
     * gives them. PDF images have no alpha, so colors are laid on white paper here.
     */
    void writeRow(int[] argb) throws IOException {
        if (picture == null) throw new IllegalStateException("no sheet begun");
        if (rows >= height) throw new IllegalStateException("sheet " + (sheets + 1) + " has " + height + " rows");
        if (argb.length < width) throw new IllegalArgumentException("a row of " + argb.length + " pixels for " + width);
        for (int x = 0, i = 0; x < width; x++, i += 3) {
            int c = argb[x];
            int a = c >>> 24;
            int r = c >> 16 & 0xff;
            int g = c >> 8 & 0xff;
            int b = c & 0xff;
            if (a != 255) {
                // c * a + white * (1 - a), rounded.
                int paper = 255 * (255 - a) + 127;
                r = (r * a + paper) / 255;
                g = (g * a + paper) / 255;
                b = (b * a + paper) / 255;
            }
            rgb[i] = (byte) r;
            rgb[i + 1] = (byte) g;
            rgb[i + 2] = (byte) b;
        }
        picture.write(rgb);
        rows++;
    }

    /** End the sheet once all its rows are written. */
    void endSheet() throws IOException {
        if (picture == null) throw new IllegalStateException("no sheet begun");
        if (rows != height) {
            throw new IllegalStateException("sheet " + (sheets + 1) + " got " + rows + " of its " + height + " rows");
        }
        picture.finish();
        picture = null;
        rgb = null;
        long length = out.count - pictureStart;
        ascii("\nendstream\nendobj\n");
        startObject(lengthObject);
        ascii(length + "\nendobj\n");
        sheets++;
    }

    /** Write the page tree, the catalog and the cross-reference table. The stream is left open. */
    void finish() throws IOException {
        if (finished || picture != null) throw new IllegalStateException("not between sheets");
        if (sheets == 0) throw new IllegalStateException("no sheets");
        startObject(2);
        ascii("<< /Type /Pages /Kids [" + kids + "] /Count " + sheets + " >>\nendobj\n");
        startObject(1);
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        long xref = out.count;
        StringBuilder table = new StringBuilder("xref\n0 " + (objects + 1) + "\n0000000000 65535 f \n");
        for (int k = 1; k <= objects; k++) {
            String offset = Long.toString(offsets[k]);
            if (offset.length() > 10) throw new IOException("the PDF is too large");
            // Every entry is 20 bytes: 10-digit offset, generation, "n" and a 2-byte end of line.
            table.append("0000000000", offset.length(), 10).append(offset).append(" 00000 n \n");
        }
        table.append("trailer\n<< /Size ").append(objects + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref)
                .append("\n%%EOF\n");
        ascii(table.toString());
        out.flush();
        finished = true;
    }

    /** Frees the compressor. Doesn't close the stream. */
    @Override
    public void close() {
        picture = null;
        if (deflater != null) deflater.end();
    }

    private void startObject(int number) throws IOException {
        if (number >= offsets.length) offsets = Arrays.copyOf(offsets, Math.max(number + 1, offsets.length * 2));
        offsets[number] = out.count;
        ascii(number + " 0 obj\n");
    }

    private void ascii(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** {@code v} to 1/1000, in plain decimal whatever the locale. */
    private static String number(double v) {
        return BigDecimal.valueOf(Math.round(v * 1000), 3).stripTrailingZeros().toPlainString();
    }
}
