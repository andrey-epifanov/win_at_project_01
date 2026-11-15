package com.example.reporttest.samples;

import lombok.extern.slf4j.Slf4j;
import win.win32.driver.WinWindow;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Screenshot-driven navigation above the generic {@code win.driver} toolkit.
 *
 * <p>It answers three questions in screenshot terms:
 * <ol>
 *   <li><b>findText</b> — where on screen (OCR) does some text appear?</li>
 *   <li><b>findByTypeNear</b> — from that text, which nearby real HWND control
 *       of a given type (Edit / Button / ComboBox / ...) is in a chosen
 *       direction (LEFT / RIGHT / ABOVE / BELOW)?</li>
 *   <li><b>findByImage</b> — where does a reference image (a text-less button /
 *       icon) appear in the screenshot?</li>
 * </ol>
 *
 * <p>Coordinate model: a screenshot PNG is 1:1 with the window's client pixels.
 * {@code WinWindow.rect()} gives the window's top-left on the physical screen,
 * so a screenshot point {@code (x,y)} maps to screen {@code (rect.left+x,
 * rect.top+y)}.
 *
 * <p>The text anchors are found by OCR ({@link OcrDriver}); the neighbouring
 * controlled-type elements are real Win32 HWNDs, enumerated with
 * {@link WinWindow#children()} and matched by class name + geometry relative to
 * the anchor.
 */
@Slf4j
public final class ScreenNavigator {

    /** Directions to look from an anchor text / point. */
    public enum Direction { LEFT, RIGHT, ABOVE, BELOW }

    private final OcrDriver ocr;

    public ScreenNavigator(OcrDriver ocr) {
        this.ocr = ocr;
    }

    // ===== 1. find text on screen =========================================

    /**
     * OCR the window screenshot and return the first word exactly equal to
     * {@code text}, mapped to SCREEN coordinates (absolute desktop pixels).
     */
    public Point findText(WinWindow window, String text) throws IOException {
        File shot = window.screenshot(Path.of("screenshots"), "nav_find.png");
        List<OcrDriver.OcrWord> words = ocr.findByText(shot, text);
        if (words.isEmpty()) {
            throw new IllegalStateException("OCR did not find text '" + text + "' on " + window);
        }
        OcrDriver.OcrWord w = words.get(0);
        int[] r = window.rect();
        return new Point(r[0] + w.centerX(), r[1] + w.centerY(), w.box, text);
    }

    /** All matches for text, as screenshot-coordinate boxes. */
    public List<OcrDriver.OcrWord> findTextAll(WinWindow window, String text) throws IOException {
        File shot = window.screenshot(Path.of("screenshots"), "nav_find_all.png");
        return ocr.findByText(shot, text);
    }

    // ===== 2. find control of a type near text ============================

    /**
     * Find the real HWND control of the given Win32 class in {@code direction}
     * relative to the OCR-anchored text.  Returns the nearest such control, or
     * null.  The returned geometry is in WINDOW-CLIENT coordinates (screenshot
     * space), matching the anchor box.
     */
    public WinWindow findByTypeNear(WinWindow window, String text,
                                    String winClass, Direction direction) throws IOException {
        File shot = window.screenshot(Path.of("screenshots"), "nav_type_near.png");
        OcrDriver.OcrWord anchor = first(ocr.findByText(shot, text));
        if (anchor == null) {
            throw new IllegalStateException("OCR anchor text '" + text + "' not found on " + window);
        }
        return findControlNear(window, anchor.box, winClass, direction);
    }

    /**
     * Pick the real control of class {@code winClass} whose bounding box is the
     * best fit in {@code direction} from {@code anchor} (in window-client px).
     * "Best" = nearest edge in that direction; ties broken by smaller gap.
     */
    public WinWindow findControlNear(WinWindow window, Rectangle anchor,
                                     String winClass, Direction direction) {
        int[] top = window.rect(); // top-left of the top-level window on screen
        Rectangle best = null;
        WinWindow bestWin = null;
        int bestDistance = Integer.MAX_VALUE;
        for (WinWindow child : window.children()) {
            if (!child.className().equalsIgnoreCase(winClass)) {
                continue;
            }
            Rectangle b = clientBox(child, top);
            int dist = distance(anchor, b, direction);
            if (dist < 0) {
                continue; // not on that side
            }
            if (dist < bestDistance) {
                bestDistance = dist;
                best = b;
                bestWin = child;
            }
        }
        return bestWin;
    }

    // ===== 3. find by image ===============================================

    /**
     * Find a reference image (a text-less control) in the screenshot.  The
     * template is loaded from a path; you can pass a search {@code region}
     * (relative to the window) to scope the scan, and a {@code minScore}
     * (default 0.7).  Returns the best match in SCREEN coordinates, or null.
     */
    public Match findByImage(WinWindow window, Path templatePath,
                             Rectangle region, float minScore) throws IOException {
        TemplateMatcher matcher = TemplateMatcher.load(templatePath);
        File shot = window.screenshot(Path.of("screenshots"), "nav_image.png");
        BufferedImage img = ImageIO.read(shot);
        List<TemplateMatcher.Match> found = matcher.matchIn(img, region, minScore);
        if (found.isEmpty()) {
            return null;
        }
        TemplateMatcher.Match m = found.get(0);
        int[] r = window.rect();
        return new Match(r[0] + m.centerX(), r[1] + m.centerY(), m);
    }

    // ===== internals ======================================================

    private static OcrDriver.OcrWord first(List<OcrDriver.OcrWord> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    private static Rectangle clientBox(WinWindow child, int[] topOrigin) {
        int[] r = child.rect(); // screen coords
        // convert to window-client by subtracting the top-level window origin
        return new Rectangle(r[0] - topOrigin[0], r[1] - topOrigin[1],
                r[2] - r[0], r[3] - r[1]);
    }

    /**
     * Signed gap from {@code anchor} to {@code b} along {@code direction}.
     * Negative means the candidate is not in that direction (skip it).
     */
    static int distance(Rectangle anchor, Rectangle b, Direction dir) {
        switch (dir) {
            case LEFT:
                if (b.x + b.width <= anchor.x) {
                    return anchor.x - (b.x + b.width);
                }
                return -1;
            case RIGHT:
                if (b.x >= anchor.x + anchor.width) {
                    return b.x - (anchor.x + anchor.width);
                }
                return -1;
            case ABOVE:
                if (b.y + b.height <= anchor.y) {
                    return anchor.y - (b.y + b.height);
                }
                return -1;
            case BELOW:
                if (b.y >= anchor.y + anchor.height) {
                    return b.y - (anchor.y + anchor.height);
                }
                return -1;
        }
        return -1;
    }

    // ===== value types ====================================================

    /** A screen-coordinate point plus the screenshot box (window-client) it came from. */
    public static final class Point {
        public final int screenX;
        public final int screenY;
        public final Rectangle box;
        public final String text;

        Point(int screenX, int screenY, Rectangle box, String text) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.box = box;
            this.text = text;
        }

        @Override
        public String toString() {
            return "Point['" + text + "' @" + screenX + "," + screenY + " box=" + box + "]";
        }
    }

    /** A screen-coordinate match (from image template). */
    public static final class Match {
        public final int screenX;
        public final int screenY;
        public final Rectangle box;
        public final float score;

        Match(int screenX, int screenY, TemplateMatcher.Match m) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.box = m.toRect();
            this.score = m.score;
        }

        @Override
        public String toString() {
            return "Match@" + screenX + "," + screenY + " box=" + box + " score=" + score;
        }
    }
}