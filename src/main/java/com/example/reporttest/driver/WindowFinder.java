package com.example.reporttest.driver;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilities to find top-level (root) windows owned by a given process whose
 * title matches a predicate.
 */
public final class WindowFinder {

    private WindowFinder() {
    }

    public static List<NativeWindow> topWindows() {
        final List<NativeWindow> out = new ArrayList<>();
        Win32.INSTANCE.EnumWindows((hWnd, lParam) -> {
            out.add(NativeWindow.of(hWnd));
            return true;
        }, Pointer.NULL);
        return out;
    }

    public static long processId(NativeWindow w) {
        IntByReference pid = new IntByReference();
        Win32.INSTANCE.GetWindowThreadProcessId(w.handle(), pid);
        return pid.getValue() & 0xFFFFFFFFL;
    }

    /**
     * Wait (until timeoutMs) for a visible top-level window belonging to pid
     * whose title equals title.
     */
    public static NativeWindow waitForTitle(long pid, String title, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        NativeWindow found = null;
        while (System.currentTimeMillis() < deadline) {
            found = findTitle(pid, title);
            if (found != null && found.isVisible()) {
                return found;
            }
            sleep(200);
        }
        return found; // may be null
    }

    /** First visible top-level window for pid with an exact title. */
    public static NativeWindow findTitle(long pid, String title) {
        for (NativeWindow w : topWindows()) {
            if (processId(w) == pid && w.isVisible() && w.windowText().equals(title)) {
                return w;
            }
        }
        return null;
    }

    /**
     * First VISIBLE top-level window for pid with an exact title that has a
     * real (non-zero) rectangle.  Some LCL apps create a phantom top-level
     * window with the same title and a 0x0 rect at startup; that one must be
     * ignored when we look for the real modal dialog.
     */
    public static NativeWindow findDialog(long pid, String title) {
        for (NativeWindow w : topWindows()) {
            if (processId(w) == pid && w.isVisible() && w.windowText().equals(title)) {
                RECT r = w.getRect();
                if (r != null && (r.right - r.left) > 0 && (r.bottom - r.top) > 0) {
                    return w;
                }
            }
        }
        return null;
    }

    /** First visible top-level window for pid whose title starts with prefix. */
    public static NativeWindow findTitleStartsWith(long pid, String prefix) {
        for (NativeWindow w : topWindows()) {
            if (processId(w) == pid && w.isVisible() && w.windowText().startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    /** Wait (until timeoutMs) for a visible top-level window with title prefix. */
    public static NativeWindow waitForTitlePrefix(long pid, String prefix, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            NativeWindow w = findTitleStartsWith(pid, prefix);
            if (w != null) {
                return w;
            }
            sleep(200);
        }
        return null;
    }

    /** True if a visible top-level window (pid, title) still exists. */
    public static boolean isTitleVisible(long pid, String title) {
        return findTitle(pid, title) != null;
    }

    /** True if a visible top-level window (pid, prefix) still exists. */
    public static boolean isTitlePrefixVisible(long pid, String prefix) {
        return findTitleStartsWith(pid, prefix) != null;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}