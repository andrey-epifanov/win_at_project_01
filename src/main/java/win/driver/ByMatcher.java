package win.driver;

/** Internal helper to test a By selector against a WinWindow. */
final class ByMatcher {
    private ByMatcher() {
    }

    static boolean matches(WinWindow w, By by) {
        switch (by.kind()) {
            case CLASS_NAME:
                return w.className().equals(by.value());
            case TEXT_EXACT:
            case TITLE_EXACT:
                return w.text().equals(by.value());
            case TEXT_CONTAINS:
                return w.text().contains(by.value());
            default:
                return false;
        }
    }
}
