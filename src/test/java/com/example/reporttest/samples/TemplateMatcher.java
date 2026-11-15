package com.example.reporttest.samples;

import lombok.extern.slf4j.Slf4j;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Template matching for "find an element by its image".
 *
 * <p>Useful for button-like controls that have NO text (icons / custom-drawn
 * glyphs), which cannot be found by OCR.  Reference images are saved by hand
 * into test resources (e.g. {@code src/test/resources/templates/btn_x.png}) and
 * loaded here; {@link #matchIn} slides the template over the captured screenshot
 * (optionally restricted to a search region) and scores each position with
 * Normalised Cross-Correlation (NCC) on grayscale pixels.
 *
 * <p>NCC is scale/illumination-tolerant to a degree and needs NO native library
 * (pure Java), unlike OpenCV which would pull a heavy native dependency into the
 * test runtime.  It returns candidate locations with their score, sorted
 * best-first; pick {@link #best} or filter by a minimum threshold.
 *
 * <p>Only grayscale luminance is used, so a template captured once still works
 * if the surrounding colour of the screenshot changes slightly.
 */
@Slf4j
public final class TemplateMatcher {

    private static final float DEFAULT_THRESHOLD = 0.7f;

    private final double[] templateData;
    private final int tw;
    private final int th;

    /** A single match candidate: its top-left position and NCC score. */
    public static final class Match {
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final float score;

        Match(int x, int y, int width, int height, float score) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.score = score;
        }

        public Rectangle toRect() {
            return new Rectangle(x, y, width, height);
        }

        public int centerX() {
            return x + width / 2;
        }

        public int centerY() {
            return y + height / 2;
        }

        @Override
        public String toString() {
            return "Match[" + x + "," + y + " " + width + "x" + height + " score=" + score + "]";
        }
    }

    private TemplateMatcher(BufferedImage template) {
        this.tw = template.getWidth();
        this.th = template.getHeight();
        this.templateData = toGray(template);
    }

    /** Load a template from a workspace-relative path (e.g. src/test/resources/templates/btn.png). */
    public static TemplateMatcher load(Path relativePath) {
        try {
            return new TemplateMatcher(readImage(relativePath.toFile()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read template " + relativePath, e);
        }
    }

    /** Load a template from an absolute file. */
    public static TemplateMatcher load(File file) {
        try {
            return new TemplateMatcher(readImage(file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read template " + file, e);
        }
    }

    /** Build a matcher directly from an in-memory image (e.g. a screenshot crop). */
    public static TemplateMatcher fromImage(BufferedImage img) {
        return new TemplateMatcher(img);
    }

    /** Search the whole screenshot.  Candidates sorted best-first. */
    public List<Match> match(BufferedImage screenshot) {
        return matchIn(screenshot, null, DEFAULT_THRESHOLD);
    }

    /**
     * Search only {@code region} of the screenshot (relative to the screenshot),
     * returning candidates sorted best-first.  {@code threshold} is the minimum
     * NCC score; lower it if the match is known but slightly off (e.g. 0.5).
     */
    public List<Match> matchIn(BufferedImage screenshot, Rectangle region, float threshold) {
        int sw = screenshot.getWidth();
        int sh = screenshot.getHeight();
        int rx = region == null ? 0 : Math.max(0, region.x);
        int ry = region == null ? 0 : Math.max(0, region.y);
        int rw = region == null ? sw : Math.min(sw - rx, region.width);
        int rh = region == null ? sh : Math.min(sh - ry, region.height);
        if (rw < tw || rh < th) {
            return new ArrayList<>();
        }

        int[] data = grayRaster(screenshot);

        final int maxX = rw - tw;
        final int maxY = rh - th;
        List<Match> raw = new ArrayList<>();
        float bestOverall = Float.NEGATIVE_INFINITY;
        int bestX = -1, bestY = -1;

        for (int y = 0; y <= maxY; y++) {
            for (int x = 0; x <= maxX; x++) {
                float score = ncc(data, sw, rx + x, ry + y, sh);
                if (score >= threshold) {
                    raw.add(new Match(rx + x, ry + y, tw, th, score));
                }
                if (score > bestOverall) {
                    bestOverall = score;
                    bestX = rx + x;
                    bestY = ry + y;
                }
            }
        }

        if (raw.isEmpty() && bestOverall >= threshold) {
            raw.add(new Match(bestX, bestY, tw, th, bestOverall));
        }
        return nms(raw);
    }

    /** The single best match at or above the default threshold, or null. */
    public Match best(BufferedImage screenshot) {
        List<Match> all = match(screenshot);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The single best match within {@code region} at or above the default threshold, or null. */
    public Match bestIn(BufferedImage screenshot, Rectangle region) {
        List<Match> all = matchIn(screenshot, region, DEFAULT_THRESHOLD);
        return all.isEmpty() ? null : all.get(0);
    }

    /** NCC score of the template at position (px,py) in the screen raster. */
    private float ncc(int[] data, int stride, int px, int py, int height) {
        double sumT = 0;
        for (double v : templateData) {
            sumT += v;
        }
        double meanT = sumT / templateData.length;

        double sumI = 0;
        for (int ty = 0; ty < th; ty++) {
            int row = (py + ty) * stride;
            for (int tx = 0; tx < tw; tx++) {
                sumI += data[row + px + tx];
            }
        }
        double meanI = sumI / templateData.length;

        double num = 0, denT = 0, denI = 0;
        int idx = 0;
        for (int ty = 0; ty < th; ty++) {
            int row = (py + ty) * stride;
            for (int tx = 0; tx < tw; tx++) {
                double t = templateData[idx++] - meanT;
                double i = data[row + px + tx] - meanI;
                num += t * i;
                denT += t * t;
                denI += i * i;
            }
        }
        double den = Math.sqrt(denT) * Math.sqrt(denI);
        if (den == 0) {
            return 0f;
        }
        return (float) (num / den);
    }

    /** Non-maximum suppression: drop candidates overlapping a better one. */
    private static List<Match> nms(List<Match> raw) {
        raw.sort((a, b) -> Float.compare(b.score, a.score));
        List<Match> keep = new ArrayList<>();
        for (Match c : raw) {
            boolean overlap = false;
            for (Match k : keep) {
                if (intersect(c, k)) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) {
                keep.add(c);
            }
        }
        return keep;
    }

    private static boolean intersect(Match a, Match b) {
        int ax1 = a.x, ay1 = a.y, ax2 = a.x + a.width, ay2 = a.y + a.height;
        int bx1 = b.x, by1 = b.y, bx2 = b.x + b.width, by2 = b.y + b.height;
        return ax1 < bx2 && bx1 < ax2 && ay1 < by2 && by1 < ay2;
    }

    private static double[] toGray(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        double[] out = new double[w * h];
        int[] rgb = new int[w * h];
        img.getRGB(0, 0, w, h, rgb, 0, w);
        for (int i = 0; i < out.length; i++) {
            out[i] = luma(rgb[i]);
        }
        return out;
    }

    private static int[] grayRaster(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] rgb = new int[w * h];
        img.getRGB(0, 0, w, h, rgb, 0, w);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) luma(rgb[i]);
        }
        return out;
    }

    private static BufferedImage readImage(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) {
            throw new IOException("Cannot decode template image: " + f);
        }
        return img;
    }

    private static double luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }
}