package win.natur.driver;

import lombok.extern.slf4j.Slf4j;
import win.win32.driver.WinWindow;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Screenshot-driven navigation above the generic {@code win.win32.driver} toolkit.
 *
 * <p>It answers the following questions in screenshot terms:
 * <ol>
 *   <li><b>findText</b> — where on screen (OCR) does some text appear?</li>
 *   <li><b>findByTypeNear</b> — from that text, which nearby real HWND control
 *       of a given type (Edit / Button / ComboBox / ...) is in a chosen
 *       direction?  Directions: {@link Direction#LEFT}/{@link Direction#RIGHT}/
 *       {@link Direction#ABOVE}/{@link Direction#BELOW} (search a real control on
 *       that side) and {@link Direction#STRAIGHT}/{@link Direction#EQUAL} (return
 *       the control located exactly at the text point).</li>
 *   <li><b>findByImage</b> — where does a reference image (a text-less button /
 *       icon) appear in the screenshot?</li>
 * </ol>
 *
 * <p>Every locating method returns a {@link WindElement} — the found element
 * with its bounds and the standard set of actions (click / double-click / type /
 * text / screenshot / bounds).
 *
 * <p>Coordinate model: a screenshot PNG is 1:1 with the window's client pixels.
 * {@code WinWindow.rect()} gives the window's top-left on the physical screen,
 * so a screenshot point {@code (x,y)} maps to screen {@code (rect.left+x,
 * rect.top+y)}.  {@link WindElement#position()} and all click actions use that
 * mapping.
 */
@Slf4j
public final class ScreenNavigator {

    /** Directions to look from an anchor text / point. */
    public enum Direction {
        LEFT, RIGHT, ABOVE, BELOW,
        /** The element located exactly AT the anchor text point. */
        STRAIGHT,
        /** Same semantics as STRAIGHT (element at the point of the text). */
        EQUAL
    }

    private final OcrDriver ocr;

    public ScreenNavigator(OcrDriver ocr) {
        this.ocr = ocr;
    }

    // ===== 1. find text on screen =========================================

    /**
     * OCR the window screenshot and return the first word exactly equal to
     * {@code text}, routed to a {@link WindElement} at the found point.
     */
    public WindElement findText(WinWindow window, String text) throws IOException {
        File shot = window.screenshot(Path.of("screenshots"), "nav_find.png");
        List<OcrDriver.OcrWord> words = ocr.findByText(shot, text);
        if (words.isEmpty()) {
            throw new IllegalStateException("OCR did not find text '" + text + "' on " + window);
        }
        OcrDriver.OcrWord w = words.get(0);
        return new WindElement(window, w.box, ocr);
    }

    /** All matches for text, as screenshot-coordinate boxes. */
    public List<OcrDriver.OcrWord> findTextAll(WinWindow window, String text) throws IOException {
        File shot = window.screenshot(Path.of("screenshots"), "nav_find_all.png");
        return ocr.findByText(shot, text);
    }

    // ===== 2. find control of a type near text ============================

    /**
     * Find the real HWND control of the given Win32 class relative to the
     * OCR-anchored text.  For {@code LEFT/RIGHT/ABOVE/BELOW} it returns the
     * nearest such control on that side; for {@code STRAIGHT/EQUAL} it returns
     * the control whose bounding box contains the anchor text point.
     * Returns a {@link WindElement}, or null.
     */
    public WindElement findByTypeNear(WinWindow window, String text,
                                      String winClass, Direction direction) throws IOException {
        File shot = window.screenshot(Path.of("screenshots"), "nav_type_near.png");
        OcrDriver.OcrWord anchor = first(ocr.findByText(shot, text));
        if (anchor == null) {
            throw new IllegalStateException("OCR anchor text '" + text + "' not found on " + window);
        }
        WinWindow ctrl = findControlNear(window, anchor.box, winClass, direction);
        return ctrl == null ? null : new WindElement(window, clientBox(ctrl, window.rect()), ocr);
    }

    /**
     * Return a {@link WindElement} directly from an anchor box (in window-client
     * pixels).  Useful to wrap an already-known region.
     */
    public WindElement element(WinWindow window, Rectangle box) {
        return new WindElement(window, box, ocr);
    }

    /**
     * Pick the real control of class {@code winClass} from {@code anchor}
     * (window-client px).  For LEFT/RIGHT/ABOVE/BELOW choose the nearest by edge
     * gap; for STRAIGHT/EQUAL choose the control containing the anchor point.
     */
    public WinWindow findControlNear(WinWindow window, Rectangle anchor,
                                     String winClass, Direction direction) {
        int[] top = window.rect();
        WinWindow bestWin = null;
        int bestDistance = Integer.MAX_VALUE;

        // STRAIGHT/EQUAL: return the element whose box contains the anchor point.
        if (direction == Direction.STRAIGHT || direction == Direction.EQUAL) {
            int cx = anchor.x + anchor.width / 2;
            int cy = anchor.y + anchor.height / 2;
            List<WinWindow> cands = childrenOfClass(window, winClass);
            List<Rectangle> boxes = new ArrayList<>();
            for (WinWindow c : cands) {
                boxes.add(clientBox(c, top));
            }
            int idx = selectAtPoint(boxes, cx, cy);
            return idx < 0 ? null : cands.get(idx);
        }

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
                bestWin = child;
            }
        }
        return bestWin;
    }

    private static List<WinWindow> childrenOfClass(WinWindow window, String winClass) {
        List<WinWindow> out = new ArrayList<>();
        for (WinWindow child : window.children()) {
            if (child.className().equalsIgnoreCase(winClass)) {
                out.add(child);
            }
        }
        return out;
    }

    /**
     * Pick the box that contains the point {@code (cx,cy)}; if none contains it,
     * fall back to the nearest box centre; {@code -1} if {@code boxes} is empty.
     * This is the pure decision rule behind {@code STRAIGHT}/{@code EQUAL}.
     */
    public static int selectAtPoint(List<Rectangle> boxes, int cx, int cy) {
        int bestIdx = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            Rectangle b = boxes.get(i);
            if (b.contains(cx, cy)) {
                return i;
            }
            double dx = b.getCenterX() - cx;
            double dy = b.getCenterY() - cy;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < bestD) {
                bestD = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    // ===== 3. find by image ===============================================

    /**
     * Find a reference image (a text-less control) in the screenshot.  The
     * template is loaded from a path; you can pass a search {@code region}
     * (relative to the window) to scope the scan, and a {@code minScore}
     * (default 0.7).  Returns a {@link WindElement} at the best match, or null.
     */
    public WindElement findByImage(WinWindow window, Path templatePath,
                                   Rectangle region, float minScore) throws IOException {
        TemplateMatcher matcher = TemplateMatcher.load(templatePath);
        File shot = window.screenshot(Path.of("screenshots"), "nav_image.png");
        BufferedImage img = ImageIO.read(shot);
        List<TemplateMatcher.Match> found = matcher.matchIn(img, region, minScore);
        if (found.isEmpty()) {
            return null;
        }
        TemplateMatcher.Match m = found.get(0);
        return new WindElement(window, new Rectangle(m.x, m.y, m.width, m.height), ocr);
    }

    // ===== internals ======================================================

    private static OcrDriver.OcrWord first(List<OcrDriver.OcrWord> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    private static Rectangle clientBox(WinWindow child, int[] topOrigin) {
        int[] r = child.rect(); // screen coords
        return new Rectangle(r[0] - topOrigin[0], r[1] - topOrigin[1],
                r[2] - r[0], r[3] - r[1]);
    }

    /**
     * Signed gap from {@code anchor} to {@code b} along {@code direction}.
     * Negative means the candidate is not in that direction (skip it).
     */
    public static int distance(Rectangle anchor, Rectangle b, Direction dir) {
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
            default:
                return -1;
        }
    }
}