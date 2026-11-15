package com.example.reporttest.samples;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Screenshot-driven OCR navigation (a "visual locator" for the app).
 *
 * <p>The DiasoftClient app is UIA-opaque: its menu, toolbar and grid rows are
 * custom-drawn and are NOT separate HWNDs, so the only reliable way to locate an
 * element by its visible text is to screenshot the window and run OCR on it.
 * This helper captures a window to a PNG, upscales it, runs Tesseract (rus+eng)
 * via Tess4J and returns the recognised words with their bounding boxes, so a
 * test can click the right coordinates.
 *
 * <p>ENGINE: we render the image preprocessed (5x-scale to a TYPE_INT_RGB
 * bitmap, then saved as a temp PNG) and call {@code createDocuments} with the
 * TSV renderer.  We deliberately do NOT use {@code getWords}/{@code getRecognizedWords}
 * (the ResultIterator at WORD level crashes in the bundled Tesseract native on
 * tscrolled-screen snapshots).  The TSV file contains one line per recognised
 * word with left/top/width/height/confidence/text, which we parse ourselves.
 *
 * <p>MATCHING: exact text equality (case-insensitive).  A word is reported as a
 * match only when its recognised text EQUALS the needle, so searching
 * {@code nrb_Pasp1trp} will NOT match the row {@code nrb_Pasp1trpXX}.
 */
@Slf4j
public final class OcrDriver {

    private static final Path TESSDATA_DIR = Path.of("src", "test", "resources", "tessdata");
    private static final double UPSCALE = 2.0d;

    private final ITesseract tesseract;

    public OcrDriver() {
        tesseract = new Tesseract();
        tesseract.setDatapath(TESSDATA_DIR.toAbsolutePath().toString());
        tesseract.setLanguage("rus+eng");
        tesseract.setPageSegMode(6);
    }

    /** One recognised word: its text and position in ORIGINAL screenshot pixels. */
    public static final class OcrWord {
        public final String text;
        public final Rectangle box;   // in original (non-upscaled) screenshot pixels
        public final float confidence;

        OcrWord(String text, Rectangle box, float confidence) {
            this.text = text;
            this.box = box;
            this.confidence = confidence;
        }

        public int centerX() {
            return box.x + box.width / 2;
        }

        public int centerY() {
            return box.y + box.height / 2;
        }

        @Override
        public String toString() {
            return "OcrWord['" + text + "' box=" + box + " conf=" + confidence + "]";
        }
    }

    /**
     * OCR a window screenshot PNG and return all recognised words in ORIGINAL
     * screenshot pixel coordinates.
     */
    public List<OcrWord> readWords(File png) {
        BufferedImage src = load(png);
        return ocrWords(src);
    }

    /** OCR an in-memory image and return words in ITS pixel coordinates. */
    public List<OcrWord> readWordsOfImage(BufferedImage src) {
        if (src == null) {
            return new ArrayList<>();
        }
        return ocrWords(src);
    }

    private List<OcrWord> ocrWords(BufferedImage src) {
        BufferedImage big = preprocess(src);
        Path tmpPng = null;
        try {
            tmpPng = Files.createTempFile("ocr_input_", ".png");
            ImageIO.write(big, "png", tmpPng.toFile());

            Path outBase = Files.createTempFile("ocr_out_", "");
            Files.deleteIfExists(outBase); // outputbase must not already exist
            tesseract.createDocuments(tmpPng.toAbsolutePath().toString(),
                    outBase.toAbsolutePath().toString(), List.of(ITesseract.RenderedFormat.TSV));
            File tsv = Path.of(outBase.toString() + ".tsv").toFile();
            if (!tsv.exists()) {
                return new ArrayList<>();
            }
            double sx = (double) src.getWidth() / big.getWidth();
            double sy = (double) src.getHeight() / big.getHeight();
            return parseTsv(List.of(Files.readString(tsv.toPath()).split("\r?\n")), sx, sy);
        } catch (IOException | TesseractException e) {
            throw new IllegalStateException("OCR failed", e);
        } finally {
            if (tmpPng != null) {
                try {
                    Files.deleteIfExists(tmpPng);
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Find every word whose text EQUALS {@code needle} (case-insensitive),
     * mapped to ORIGINAL screenshot coordinates.
     */
    public List<OcrWord> findByText(File png, String needle) {
        List<OcrWord> all = readWords(png);
        List<OcrWord> out = new ArrayList<>();
        String target = needle.trim();
        if (target.isEmpty()) {
            return out;
        }
        if (target.contains(" ")) {
            // Multi-word phrase: find a CONTIGUOUS run of words on one visual
            // line whose joined text EQUALS the target (e.g. the menu bar holds
            // "… Отчетность и формы …" among other items).  Exact token match:
            // "Отчетность и формы" must NOT match "Отчетность и формыXYZ".
            String[] want = target.split("\\s+");
            List<Line> lines = groupLines(all);
            for (Line line : lines) {
                int n = line.words.size();
                for (int start = 0; start + want.length <= n; start++) {
                    boolean ok = true;
                    for (int k = 0; k < want.length; k++) {
                        if (!line.words.get(start + k).text.equalsIgnoreCase(want[k])) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        List<OcrWord> run = line.words.subList(start, start + want.length);
                        out.add(new OcrWord(target, span(run), run.get(0).confidence));
                    }
                }
            }
        } else {
            // Single word: EXACT equality (so "nrb_Pasp1trp" != "nrb_Pasp1trpXX").
            for (OcrWord w : all) {
                if (w.text.equalsIgnoreCase(target)) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /** A horizontal cluster of OCR words that belong to the same visual line. */
    private static final class Line {
        final List<OcrWord> words = new ArrayList<>();
    }

    /** Bounding box spanning the given words (usually a contiguous phrase run). */
    private static Rectangle span(List<OcrWord> ws) {
        int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
        for (OcrWord w : ws) {
            l = Math.min(l, w.box.x);
            t = Math.min(t, w.box.y);
            r = Math.max(r, w.box.x + w.box.width);
            b = Math.max(b, w.box.y + w.box.height);
        }
        return new Rectangle(l, t, r - l, b - t);
    }

    /** Group words into visual lines (same top band, ordered left->right). */
    private static List<Line> groupLines(List<OcrWord> words) {
        List<Line> lines = new ArrayList<>();
        for (OcrWord w : words) {
            Line owner = null;
            for (Line line : lines) {
                int midY = line.words.get(0).centerY();
                if (Math.abs(midY - w.centerY()) < 10) {
                    owner = line;
                    break;
                }
            }
            if (owner == null) {
                owner = new Line();
                lines.add(owner);
            }
            owner.words.add(w);
        }
        for (Line line : lines) {
            line.words.sort((a, b) -> Integer.compare(a.box.x, b.box.x));
        }
        return lines;
    }

    /** True if the screenshot contains a word equal to {@code text} (case-insensitive). */
    public boolean containsText(File png, String text) {
        return !findByText(png, text).isEmpty();
    }

    /** Read the screenshot & converts to an upscaled TYPE_INT_RGB bitmap. */
    private static BufferedImage preprocess(BufferedImage src) {
        int w = (int) Math.round(src.getWidth() * UPSCALE);
        int h = (int) Math.round(src.getHeight() * UPSCALE);
        BufferedImage big = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return big;
    }

    private static BufferedImage load(File png) {
        try {
            return ImageIO.read(png);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read screenshot " + png, e);
        }
    }

    /**
     * Parse a Tesseract TSV.  Only the word lines (level==5) are taken.
     * Returns words in the given scale (sx/sy multiply the big coords back).
     */
    private static List<OcrWord> parseTsv(List<String> lines, double sx, double sy) {
        List<OcrWord> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) { // skip header
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length < 12) {
                continue;
            }
            // level page block par line word left top width height conf text
            int level = parseInt(f[0], -1);
            if (level != 5) {
                continue;
            }
            String text = f[11];
            if (text == null || text.isBlank()) {
                continue;
            }
            int left = parseInt(f[6], 0);
            int top = parseInt(f[7], 0);
            int width = parseInt(f[8], 0);
            int height = parseInt(f[9], 0);
            float conf = parseFloat(f[10], 0f);
            Rectangle box = new Rectangle(
                    (int) Math.round(left * sx),
                    (int) Math.round(top * sy),
                    (int) Math.round(width * sx),
                    (int) Math.round(height * sy));
            out.add(new OcrWord(text, box, conf));
        }
        return out;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return def;
        }
    }
}