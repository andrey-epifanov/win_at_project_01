package win.driver;

import java.util.List;

/**
 * A lazy, Playwright-style handle to one or more descendant controls matching a
 * By selector.  The query is resolved against a scope WinWindow each time a
 * method is called, so the element can appear/disappear between calls.
 *
 * Example:
 *   WinProcess app = WinDriver.launch(exe, workDir);
 *   WinWindow main = app.mainWindow(By.title("My App"), 10000);
 *   Locator folder = main.locator(By.className("Edit")).matchingTextStartingWith("D:");
 *   folder.fill("D:\\...\\reports");
 *   System.out.println(folder.value());
 */
public final class Locator {

    private final WinWindow scope;
    private final By by;

    Locator(WinWindow scope, By by) {
        this.scope = scope;
        this.by = by;
    }

    /** Number of controls matching the selector right now. */
    public int count() {
        return scope.children(by).size();
    }

    /** The resolved control (throws if none / out of range). */
    public WinWindow resolve() {
        List<WinWindow> all = scope.children(by);
        int idx = by.index();
        if (idx < all.size()) {
            return all.get(idx);
        }
        throw new WinException("No control matched " + by + " under " + scope
                + " (found " + all.size() + ")");
    }

    /** The resolved control (same as resolve(), but returns null instead of throwing). */
    public WinWindow firstOrNull() {
        List<WinWindow> all = scope.children(by);
        return all.isEmpty() ? null : all.get(Math.min(by.index(), all.size() - 1));
    }

    /** Narrow the selector to the n-th matching sibling. */
    public Locator nth(int n) {
        return new Locator(scope, by.nth(n));
    }

    // ----- actions -------------------------------------------------------

    public Locator fill(String value) {
        resolve().fill(value);
        return this;
    }

    public Locator type(String value) {
        resolve().fill(value);
        return this;
    }

    public Locator click() {
        resolve().click();
        return this;
    }

    /** Edit text of the resolved control. */
    public String value() {
        return resolve().editText();
    }

    /** Window text (title / caption) of the resolved control. */
    public String innerText() {
        return resolve().text();
    }

    public boolean isVisible() {
        WinWindow w = firstOrNull();
        return w != null && w.isVisible();
    }

    public boolean isEnabled() {
        WinWindow w = firstOrNull();
        return w != null && w.isEnabled();
    }

    /** Wait (until timeoutMs) for the control to become visible. */
    public Locator waitFor(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isVisible()) {
                return this;
            }
            WinDriver.sleep(100);
        }
        throw new WinException("Timed out waiting for " + by + " to become visible");
    }

    /** Number of children of the resolved control matching a nested selector. */
    public int childCount(By nestedBy) {
        return resolve().children(nestedBy).size();
    }

    @Override
    public String toString() {
        return "Locator[" + by + "]";
    }
}
