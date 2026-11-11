package win.driver;

import com.sun.jna.Pointer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A launched Win32 application process.  This is the analogue of a Playwright
 * {@code Browser}: it owns the OS process and lets you find its top-level
 * windows.  Start it with {@link #start(String, String)} (or
 * {@link WinDriver#launch(String, String)}).
 *
 * All window targeting is scoped to this PID so you never pick up unrelated
 * windows from a previously launched instance of the same app.
 */
public final class WinProcess implements AutoCloseable {

    private final Process process;
    private final long pid;

    private WinProcess(Process process) {
        this.process = process;
        this.pid = process.pid();
    }

    /** Launch an executable with an optional working directory. */
    public static WinProcess start(String exePath, String workDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exePath);
        if (workDir != null && !workDir.isEmpty()) {
            pb.directory(new File(workDir));
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        return new WinProcess(p);
    }

    public long pid() {
        return pid;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Wait (until timeoutMs) for the process to exit; true if it exited. */
    public boolean waitForExit(long timeoutMs) {
        try {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /** Force-kill the process. */
    public void terminate() {
        process.destroy();
        try {
            process.waitFor(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        terminate();
    }

    // ----- top-level windows ---------------------------------------------

    /** All top-level windows in the system (EnumWindows). */
    public static List<WinWindow> topWindows() {
        final List<WinWindow> out = new ArrayList<>();
        Win32.INSTANCE.EnumWindows((hWnd, lParam) -> {
            out.add(new WinWindow(hWnd, null));
            return true;
        }, Pointer.NULL);
        return out;
    }

    /** Process id that owns the given window handle. */
    public static long processId(WinWindow w) {
        return w.processId();
    }

    /** Find the first visible top-level window belonging to THIS process with exact title. */
    public WinWindow windowByTitle(String title) {
        for (WinWindow w : topWindows()) {
            if (w.isVisible() && w.title().equals(title)) {
                return w.withOwner(this);
            }
        }
        return null;
    }

    /** Find the first visible top-level window belonging to THIS process with exact title. */
    public WinWindow windowByPidAndTitle(String title) {
        for (WinWindow w : topWindows()) {
            if (w.processId() == pid && w.isVisible() && w.title().equals(title)) {
                return w.withOwner(this);
            }
        }
        return null;
    }

    /** Find the first visible top-level window belonging to THIS process whose title starts with prefix. */
    public WinWindow windowByPidAndTitlePrefix(String prefix) {
        for (WinWindow w : topWindows()) {
            if (w.processId() == pid && w.isVisible() && w.title().startsWith(prefix)) {
                return w.withOwner(this);
            }
        }
        return null;
    }

    /** A lazy Locator over this process's top-level windows matching a By. */
    public Locator windowLocator(By by) {
        return new Locator(new ProcessScope(this), by);
    }

    /** Main (top-level) window by exact title, waiting until it appears and is visible. */
    public WinWindow mainWindow(By by, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            WinWindow w = resolveTopLevel(by);
            if (w != null && w.isVisible()) {
                return w;
            }
            WinDriver.sleep(150);
        }
        throw new WinException("Timed out waiting for top-level window " + by);
    }

    /** Main window by exact title (alias of mainWindow(By.title(...), timeout)). */
    public WinWindow mainWindow(String title, long timeoutMs) {
        return mainWindow(By.title(title), timeoutMs);
    }

    private WinWindow resolveTopLevel(By by) {
        for (WinWindow w : topWindows()) {
            if (w.processId() != pid || !w.isVisible()) {
                continue;
            }
            if (ByMatcher.matches(w, by)) {
                return w.withOwner(this);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "WinProcess[pid=" + pid + ", alive=" + isAlive() + "]";
    }

    /**
     * Adapter that exposes the process's top-level windows as "children" of a
     * synthetic scope, so {@link Locator} can query them uniformly.
     */
    private static final class ProcessScope extends WinWindow {
        private final WinProcess ownerProc;

        ProcessScope(WinProcess owner) {
            super(Pointer.NULL, owner);
            this.ownerProc = owner;
        }

        @Override
        public List<WinWindow> children() {
            final List<WinWindow> out = new ArrayList<>();
            for (WinWindow w : WinProcess.topWindows()) {
                if (w.processId() == ownerProc.pid && w.isVisible()) {
                    out.add(w.withOwner(ownerProc));
                }
            }
            return out;
        }

        @Override
        public boolean isVisible() {
            return true;
        }
    }
}
