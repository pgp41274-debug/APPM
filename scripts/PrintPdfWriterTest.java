package com.appm.pdfcraft;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * Tests {@link PrintPdfWriter} on a desktop JVM; scripts/test-print-pdf.sh runs it. It checks the
 * PDF byte by byte (cross-reference table, objects, stream lengths, every pixel after
 * decompression), renders plain and turned sheets with pdftoppm to see where their pictures land
 * against PdfCraft's print CSS, and streams a 64-megapixel sheet through the small heap the script
 * gives it.
 */
final class PrintPdfWriterTest {
    private static final int RED = 0xffff0000;
    private static final int GREEN = 0xff00ff00;
    private static final int BLUE = 0xff0000ff;
    private static final int PAPER = 0xffffe8a0;

    /** A sheet to write: its page in points and its picture's rows of ARGB colors. */
    private record Sheet(double widthPt, double heightPt, boolean turned, int[][] argb) {
        int width() {
            return argb[0].length;
        }

        int height() {
            return argb.length;
        }
    }

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        // Numbers in the PDF must not follow the locale (a German one writes 612,5).
        Locale.setDefault(Locale.GERMANY);
        File dir = new File(args.length > 0 ? args[0] : "build/test-print");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        structure(new File(dir, "structure.pdf"));
        placement(new File(dir, "placement.pdf"), dir);
        largeSheet(new File(dir, "large.pdf"));
        misuse();
        System.out.println(checks + " checks, " + failures + " failed; PDFs in " + dir);
        if (failures > 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    // ---- structure ----

    /** Many sheets of random sizes, turns and colors (alpha included), checked byte by byte. */
    private static void structure(File file) throws IOException {
        Random random = new Random(7);
        List<Sheet> sheets = new ArrayList<>();
        double[] sizes = {0.001, 1, 612, 792, 595.276, 841.89, 14400, 100000.5};
        for (int n = 0; n < 150; n++) {
            double w = n < sizes.length ? sizes[n] : 1 + random.nextInt(2000000) / 1000.0;
            double h = n < sizes.length ? sizes[sizes.length - 1 - n] : 1 + random.nextInt(2000000) / 1000.0;
            int[][] argb = new int[1 + random.nextInt(40)][1 + random.nextInt(40)];
            for (int[] row : argb) {
                for (int x = 0; x < row.length; x++) {
                    int alpha = switch (random.nextInt(4)) {
                        case 0 -> 0;
                        case 1 -> 255;
                        default -> random.nextInt(256);
                    };
                    row[x] = alpha << 24 | random.nextInt(1 << 24);
                }
            }
            sheets.add(new Sheet(w, h, random.nextBoolean(), argb));
        }
        write(file, sheets);
        verify(Files.readAllBytes(file.toPath()), sheets, file.getName());
    }

    private static void write(File file, List<Sheet> sheets) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                PrintPdfWriter writer = new PrintPdfWriter(out)) {
            for (Sheet sheet : sheets) {
                writer.beginSheet(sheet.widthPt(), sheet.heightPt(), sheet.turned(), sheet.width(), sheet.height());
                for (int[] row : sheet.argb()) writer.writeRow(row);
                writer.endSheet();
            }
            writer.finish();
        }
    }

    /** Checks every byte of the file's frame, every object, and each picture's pixels. */
    private static void verify(byte[] pdf, List<Sheet> sheets, String name) throws IOException {
        String[] objects = objects(pdf, name);
        check(objects.length == 3 + 4 * sheets.size(), name + ": " + objects.length + " objects");
        if (objects.length < 3) return;
        check(objects[1].equals("<< /Type /Catalog /Pages 2 0 R >>"), name + ": catalog " + objects[1]);
        Matcher tree = Pattern.compile("<< /Type /Pages /Kids \\[([0-9 R]*)\\] /Count (\\d+) >>").matcher(objects[2]);
        check(tree.matches(), name + ": page tree " + objects[2]);
        if (!tree.matches()) return;
        check(Integer.parseInt(tree.group(2)) == sheets.size(), name + ": page count");
        Matcher kid = Pattern.compile("(\\d+) 0 R").matcher(tree.group(1));
        Pattern page = Pattern.compile("<< /Type /Page /Parent 2 0 R /MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)\\]"
                + " /Resources << /XObject << /Im0 (\\d+) 0 R >> >> /Contents (\\d+) 0 R >>");
        Pattern image = Pattern.compile("<< /Type /XObject /Subtype /Image /Width (\\d+) /Height (\\d+)"
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length (\\d+) 0 R >>");
        for (int n = 0; n < sheets.size(); n++) {
            Sheet sheet = sheets.get(n);
            String at = name + " sheet " + (n + 1) + ": ";
            if (!kid.find()) {
                check(false, at + "missing from /Kids");
                break;
            }
            String pageObject = objects[Integer.parseInt(kid.group(1))];
            Matcher p = page.matcher(pageObject);
            check(p.matches(), at + "page " + pageObject);
            if (!p.matches()) continue;
            double w = Double.parseDouble(p.group(1));
            double h = Double.parseDouble(p.group(2));
            check(Math.abs(w - sheet.widthPt()) <= 0.0005 && Math.abs(h - sheet.heightPt()) <= 0.0005,
                    at + "MediaBox " + w + " " + h);
            // The matrix's effect is checked by rendering, in placement().
            String cm = sheet.turned() ? "0 " + p.group(2) + " -" + p.group(1) + " 0 " + p.group(1) + " 0"
                    : p.group(1) + " 0 0 " + p.group(2) + " 0 0";
            String contents = new String(stream(objects, Integer.parseInt(p.group(4))), StandardCharsets.ISO_8859_1);
            check(contents.equals("q " + cm + " cm /Im0 Do Q"), at + "contents " + contents);
            int imageObject = Integer.parseInt(p.group(3));
            String dictionary = objects[imageObject].substring(0, objects[imageObject].indexOf(">>\nstream\n") + 2);
            Matcher i = image.matcher(dictionary);
            check(i.matches(), at + "image " + dictionary);
            if (!i.matches()) continue;
            check(Integer.parseInt(i.group(1)) == sheet.width() && Integer.parseInt(i.group(2)) == sheet.height(),
                    at + "image size");
            byte[] rgb = new InflaterInputStream(new ByteArrayInputStream(stream(objects, imageObject))).readAllBytes();
            check(Arrays.equals(rgb, overWhite(sheet.argb())), at + "pixels");
        }
        check(!kid.find(), name + ": extra /Kids");
    }

    /**
     * Each object's text between "N 0 obj\n" and "\nendobj\n", by number, after checking the
     * header, the cross-reference table (20-byte entries pointing at their objects), the trailer and
     * the end of the file.
     */
    private static String[] objects(byte[] pdf, String name) {
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        check(text.startsWith("%PDF-1.4\n%") && pdf[14] == '\n', name + ": header");
        for (int k = 10; k < 14; k++) check((pdf[k] & 0xff) >= 128, name + ": binary comment");
        Matcher end = Pattern.compile("trailer\n<< /Size (\\d+) /Root 1 0 R >>\nstartxref\n(\\d+)\n%%EOF\n$")
                .matcher(text);
        if (!end.find()) {
            check(false, name + ": trailer");
            return new String[0];
        }
        int size = Integer.parseInt(end.group(1));
        int xref = Integer.parseInt(end.group(2));
        String head = "xref\n0 " + size + "\n";
        check(text.startsWith(head, xref), name + ": startxref doesn't point at the table");
        int entries = xref + head.length();
        check(entries + 20 * size == end.start(), name + ": table length");
        check(text.startsWith("0000000000 65535 f \n", entries), name + ": entry 0");
        int[] offsets = new int[size];
        for (int k = 1; k < size; k++) {
            String entry = text.substring(entries + 20 * k, entries + 20 * k + 20);
            check(entry.matches("\\d{10} 00000 n \n"), name + ": entry " + k + " " + entry);
            offsets[k] = Integer.parseInt(entry.substring(0, 10));
            check(text.startsWith(k + " 0 obj\n", offsets[k]), name + ": entry " + k + " points at " + offsets[k]);
        }
        // The objects follow each other from the end of the header to the table.
        int[] sorted = Arrays.copyOfRange(offsets, 1, size);
        Arrays.sort(sorted);
        check(sorted[0] == 15, name + ": the first object isn't right after the header");
        String[] objects = new String[size];
        objects[0] = "";
        for (int k = 1; k < size; k++) {
            int i = Arrays.binarySearch(sorted, offsets[k]);
            int next = i + 1 < sorted.length ? sorted[i + 1] : xref;
            check(text.startsWith("\nendobj\n", next - 8), name + ": object " + k + " doesn't end with endobj");
            objects[k] = text.substring(offsets[k] + (k + " 0 obj\n").length(), next - 8);
        }
        return objects;
    }

    /** Object {@code number}'s stream data, checking its /Length (direct, or an indirect object). */
    private static byte[] stream(String[] objects, int number) {
        String object = objects[number];
        int data = object.indexOf(">>\nstream\n") + ">>\nstream\n".length();
        Matcher length = Pattern.compile("/Length (\\d+)( 0 R)? >>\nstream\n").matcher(object);
        if (!length.find()) {
            check(false, "object " + number + ": no /Length");
            return new byte[0];
        }
        int n = Integer.parseInt(length.group(1));
        if (length.group(2) != null) n = Integer.parseInt(objects[n]);
        check(object.length() == data + n + "\nendstream".length() && object.startsWith("\nendstream", data + n),
                "object " + number + ": /Length " + n + " doesn't end at endstream");
        return object.substring(data, data + n).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** What the writer should make of {@code argb}: colors over white, as RGB bytes. */
    private static byte[] overWhite(int[][] argb) {
        byte[] rgb = new byte[argb.length * argb[0].length * 3];
        int i = 0;
        for (int[] row : argb) {
            for (int c : row) {
                double a = (c >>> 24) / 255.0;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    rgb[i++] = (byte) Math.round((c >> shift & 0xff) * a + 255 * (1 - a));
                }
            }
        }
        return rgb;
    }

    // ---- placement ----

    /**
     * A picture with a red square at its top left, blue at its top right and green at its bottom
     * left, on a pale yellow ground. Three corners pin down where and which way round it lands.
     */
    private static int[][] markedPicture(int width, int height) {
        int m = Math.min(width, height) / 5;
        int[][] argb = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean top = y < m;
                boolean left = x < m;
                boolean right = x >= width - m;
                boolean bottom = y >= height - m;
                argb[y][x] = top && left ? RED : top && right ? BLUE : bottom && left ? GREEN : PAPER;
            }
        }
        return argb;
    }

    /**
     * Renders plain and turned sheets with pdftoppm and checks each marker's center against where
     * PdfCraft's print page puts it: an img of the page's size, or for a turned sheet
     * {@code width:H; height:W; transform-origin:0 0; transform:translate(0,H) rotate(-90deg)}.
     */
    private static void placement(File file, File dir) throws IOException, InterruptedException {
        List<Sheet> sheets = List.of(
                new Sheet(612, 792, false, markedPicture(153, 198)),
                new Sheet(612, 792, true, markedPicture(198, 153)),
                new Sheet(842, 596, false, markedPicture(210, 149)),
                new Sheet(596, 842, true, markedPicture(421, 298)));
        write(file, sheets);
        verify(Files.readAllBytes(file.toPath()), sheets, file.getName());
        int dpi = 36;
        for (int n = 0; n < sheets.size(); n++) {
            Sheet sheet = sheets.get(n);
            String at = file.getName() + " sheet " + (n + 1) + (sheet.turned() ? " (turned)" : "") + ": ";
            File prefix = new File(dir, "placement-" + (n + 1));
            Process pdftoppm = new ProcessBuilder("pdftoppm", "-r", String.valueOf(dpi), "-f", String.valueOf(n + 1),
                    "-l", String.valueOf(n + 1), "-singlefile", file.getPath(), prefix.getPath())
                    .redirectErrorStream(true).start();
            String output = new String(pdftoppm.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(pdftoppm.waitFor() == 0 && output.isEmpty(), at + "pdftoppm: " + output);
            int[][] raster = readPpm(new File(prefix.getPath() + ".ppm"));
            double scale = dpi / 72.0;
            check(raster.length == Math.round(sheet.heightPt() * scale)
                    && raster[0].length == Math.round(sheet.widthPt() * scale),
                    at + "rendered " + raster[0].length + "x" + raster.length);
            // Each marker's center in the picture, as fractions with y down.
            double mu = Math.min(sheet.width(), sheet.height()) / 5 / 2.0 / sheet.width();
            double mv = Math.min(sheet.width(), sheet.height()) / 5 / 2.0 / sheet.height();
            int[] colors = {RED, BLUE, GREEN};
            String[] names = {"red (top left)", "blue (top right)", "green (bottom left)"};
            double[][] centers = {{mu, mv}, {1 - mu, mv}, {mu, 1 - mv}};
            for (int k = 0; k < colors.length; k++) {
                double[] css = cssPoint(sheet, centers[k][0], centers[k][1]);
                double[] found = centroid(raster, colors[k]);
                boolean near = found != null && Math.abs(found[0] - css[0] * scale) <= 1
                        && Math.abs(found[1] - css[1] * scale) <= 1;
                check(near, at + names[k] + " marker at " + Arrays.toString(found) + " px, CSS puts it at ["
                        + css[0] * scale + ", " + css[1] * scale + "]");
            }
            // The picture reaches every corner of the page.
            int h = raster.length;
            int w = raster[0].length;
            for (int c : new int[] {raster[0][0], raster[0][w - 1], raster[h - 1][0], raster[h - 1][w - 1]}) {
                check((c & 0xffffff) != 0xffffff, at + "white where the picture should be");
            }
        }
    }

    /**
     * Where PdfCraft's print page draws the picture's point (u, v), fractions with v down, in points
     * from the page's top left, worked out as the browser composes the CSS transform.
     */
    private static double[] cssPoint(Sheet sheet, double u, double v) {
        double pw = sheet.widthPt();
        double ph = sheet.heightPt();
        if (!sheet.turned()) return new double[] {u * pw, v * ph};
        // The img box is ph wide and pw high; transform-origin 0 0 is its top left.
        double x = u * ph;
        double y = v * pw;
        // rotate(-90deg): CSS rotate(a) is [cos a, -sin a; sin a, cos a] in a y-down space.
        double a = Math.toRadians(-90);
        double rx = x * Math.cos(a) - y * Math.sin(a);
        double ry = x * Math.sin(a) + y * Math.cos(a);
        // Then translate(0, ph): the transform list applies right to left to the box's points.
        return new double[] {rx, ry + ph};
    }

    /** The center (pixel units, from the top left) of the pixels close to {@code color}, or null. */
    private static double[] centroid(int[][] raster, int color) {
        double sx = 0;
        double sy = 0;
        int count = 0;
        for (int y = 0; y < raster.length; y++) {
            for (int x = 0; x < raster[y].length; x++) {
                int c = raster[y][x];
                boolean close = true;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    close &= Math.abs((c >> shift & 0xff) - (color >> shift & 0xff)) < 40;
                }
                if (close) {
                    sx += x + 0.5;
                    sy += y + 0.5;
                    count++;
                }
            }
        }
        return count == 0 ? null : new double[] {sx / count, sy / count};
    }

    /** A binary PPM (P6, 8-bit) as rows of RGB colors. */
    private static int[][] readPpm(File file) throws IOException {
        byte[] b = Files.readAllBytes(file.toPath());
        int[] at = {0};
        String[] fields = new String[4];
        for (int k = 0; k < 4; k++) {
            while (Character.isWhitespace(b[at[0]])) at[0]++;
            StringBuilder field = new StringBuilder();
            while (!Character.isWhitespace(b[at[0]])) field.append((char) b[at[0]++]);
            fields[k] = field.toString();
        }
        at[0]++;
        if (!fields[0].equals("P6") || !fields[3].equals("255")) throw new IOException(file + " isn't an 8-bit P6 PPM");
        int width = Integer.parseInt(fields[1]);
        int height = Integer.parseInt(fields[2]);
        int[][] raster = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = at[0] + 3 * (y * width + x);
                raster[y][x] = (b[i] & 0xff) << 16 | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff);
            }
        }
        return raster;
    }

    // ---- a large sheet ----

    /**
     * A 64-megapixel sheet (the most PdfCraft draws: 40 inches square at 200 dpi), 192 MB as RGB,
     * written through a heap far smaller than that, then decompressed and spot-checked.
     */
    private static void largeSheet(File file) throws IOException {
        int size = 8000;
        long start = System.nanoTime();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                PrintPdfWriter writer = new PrintPdfWriter(out)) {
            writer.beginSheet(2880, 2880, false, size, size);
            int[] row = new int[size];
            for (int y = 0; y < size; y++) {
                largeRow(y, row);
                writer.writeRow(row);
            }
            writer.endSheet();
            writer.finish();
        }
        long written = System.nanoTime();
        byte[] pdf = Files.readAllBytes(file.toPath());
        String[] objects = objects(pdf, file.getName());
        check(objects[3].startsWith("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 2880 2880]"),
                "large: page " + objects[3]);
        int[] row = new int[size];
        byte[] got = new byte[size * 3];
        boolean same = true;
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(stream(objects, 5)))) {
            for (int y = 0; y < size; y++) {
                if (in.readNBytes(got, 0, got.length) != got.length) {
                    check(false, "large: picture ends at row " + y);
                    break;
                }
                if (y % 997 == 0 || y % 1000 < 4 || y == size - 1) {
                    largeRow(y, row);
                    same &= Arrays.equals(overWhite(new int[][] {row}), got);
                }
            }
            check(in.read() == -1, "large: picture longer than 8000 rows");
        }
        check(same, "large: pixels");
        Runtime runtime = Runtime.getRuntime();
        System.out.printf(Locale.ROOT, "large sheet: %dx%d px in %.1f s, %d KB of PDF, heap limit %d MB%n", size, size,
                (written - start) / 1e9, pdf.length >> 10, runtime.maxMemory() >> 20);
    }

    /** Paper with black diagonals, and every 1000th row starts a band of half-transparent colors. */
    private static void largeRow(int y, int[] row) {
        for (int x = 0; x < row.length; x++) {
            boolean diagonal = x == y || x == row.length - 1 - y;
            row[x] = diagonal ? 0xff000000 : y % 1000 < 4 ? 0x80000000 | (x * 31 + y) & 0xffffff : PAPER;
        }
    }

    // ---- misuse ----

    private interface Step {
        void run(PrintPdfWriter writer) throws IOException;
    }

    /** Calls in the wrong order or with bad values throw. */
    private static void misuse() throws IOException {
        expect(IllegalStateException.class, w -> w.writeRow(new int[1]), "a row before a sheet");
        expect(IllegalStateException.class, w -> w.endSheet(), "endSheet before a sheet");
        expect(IllegalStateException.class, w -> w.finish(), "finish with no sheets");
        expect(IllegalArgumentException.class, w -> w.beginSheet(Double.NaN, 792, false, 1, 1), "a NaN width");
        expect(IllegalArgumentException.class, w -> w.beginSheet(612, Double.POSITIVE_INFINITY, false, 1, 1),
                "an infinite height");
        expect(IllegalArgumentException.class, w -> w.beginSheet(0, 792, false, 1, 1), "a zero width");
        expect(IllegalArgumentException.class, w -> w.beginSheet(612, 792, false, 0, 1), "no pixels");
        expect(IllegalArgumentException.class, w -> w.beginSheet(612, 792, false, Integer.MAX_VALUE, 1), "too wide");
        expect(IllegalStateException.class, w -> {
            w.beginSheet(612, 792, false, 2, 2);
            w.writeRow(new int[2]);
            w.endSheet();
        }, "a sheet one row short");
        expect(IllegalStateException.class, w -> {
            w.beginSheet(612, 792, false, 2, 1);
            w.writeRow(new int[2]);
            w.writeRow(new int[2]);
        }, "a row too many");
        expect(IllegalArgumentException.class, w -> {
            w.beginSheet(612, 792, false, 2, 1);
            w.writeRow(new int[1]);
        }, "a short row");
        expect(IllegalStateException.class, w -> {
            w.beginSheet(612, 792, false, 1, 1);
            w.finish();
        }, "finish in the middle of a sheet");
        expect(IllegalStateException.class, w -> {
            w.beginSheet(612, 792, false, 1, 1);
            w.beginSheet(612, 792, false, 1, 1);
        }, "a sheet inside a sheet");
        // An error names the sheet.
        try (PrintPdfWriter writer = new PrintPdfWriter(OutputStream.nullOutputStream())) {
            writer.beginSheet(612, 792, false, 1, 1);
            writer.writeRow(new int[1]);
            writer.endSheet();
            writer.beginSheet(612, -1, false, 1, 1);
            check(false, "a negative height");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("sheet 2"), "error doesn't name sheet 2: " + e.getMessage());
        }
    }

    private static void expect(Class<? extends Exception> type, Step step, String what) throws IOException {
        try (PrintPdfWriter writer = new PrintPdfWriter(OutputStream.nullOutputStream())) {
            step.run(writer);
            check(false, what + ": no " + type.getSimpleName());
        } catch (RuntimeException e) {
            check(type.isInstance(e), what + ": " + e);
        }
    }
}
