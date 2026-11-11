package win.driver;

/**
 * Selector describing HOW to address a window or a child control, in the
 * spirit of Playwright's selector.  A By is declarative and lazy: it is not
 * resolved until a query is run against a scope window.
 *
 * Supported strategies:
 *   By.className("Edit")        - Win32 window class (GetClassNameW)
 *   By.title("Поиск ...")       - exact window text / control text
 *   By.text("Поиск")            - alias of title (exact)
 *   By.textContains("XML")      - substring match on text
 *   By.index(n)                 - the n-th match (default 0) among matching siblings
 */
public final class By {

    public enum Kind { CLASS_NAME, TEXT_EXACT, TEXT_CONTAINS, TITLE_EXACT }

    private final Kind kind;
    private final String value;
    private final int index;

    private By(Kind kind, String value, int index) {
        this.kind = kind;
        this.value = value;
        this.index = index;
    }

    public static By className(String className) {
        return new By(Kind.CLASS_NAME, className, 0);
    }

    public static By title(String text) {
        return new By(Kind.TITLE_EXACT, text, 0);
    }

    public static By text(String text) {
        return new By(Kind.TEXT_EXACT, text, 0);
    }

    public static By textContains(String text) {
        return new By(Kind.TEXT_CONTAINS, text, 0);
    }

    /** Pick the n-th matching sibling instead of the first. */
    public By nth(int n) {
        return new By(kind, value, n);
    }

    public Kind kind() {
        return kind;
    }

    public String value() {
        return value;
    }

    public int index() {
        return index;
    }

    @Override
    public String toString() {
        return kind + "(" + value + ")[#" + index + "]";
    }
}
