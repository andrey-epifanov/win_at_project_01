package win.driver;

/**
 * Synthetic keyboard support for the win.driver toolkit.
 *
 * NOTE: keybd_event only reaches the FOREGROUND window.  For reliably typing
 * into an Edit control use {@link WinWindow#fill(String)} (WM_CHAR), which does
 * not depend on foreground.  WinKey is provided for the cases where you need to
 * simulate modifier keys / navigation to a focused control.
 */
public final class WinKey {

    private WinKey() {
    }

    // Common virtual-key codes
    public static final int VK_BACK      = 0x08;
    public static final int VK_TAB       = 0x09;
    public static final int VK_RETURN    = 0x0D;
    public static final int VK_ESCAPE    = 0x1B;
    public static final int VK_SPACE     = 0x20;
    public static final int VK_END       = 0x23;
    public static final int VK_HOME      = 0x24;
    public static final int VK_LEFT      = 0x25;
    public static final int VK_UP        = 0x26;
    public static final int VK_RIGHT     = 0x27;
    public static final int VK_DOWN      = 0x28;
    public static final int VK_DELETE    = 0x2E;
    public static final int VK_SHIFT     = 0x10;
    public static final int VK_CONTROL   = 0x11;
    public static final int VK_MENU      = 0x12;

    private static final int KEYEVENTF_KEYUP = 0x0002;

    /** Press and release a single virtual key. */
    public static void press(int vk) {
        event((byte) vk, false);
        event((byte) vk, true);
    }

    /** Press and release a virtual key while holding Control. */
    public static void ctrl(int vk) {
        chord(VK_CONTROL, vk);
    }

    /** Hold one key, tap the second, release the first (e.g. Ctrl+Shift+Esc). */
    public static void chord(int modifierVk, int vk) {
        event((byte) modifierVk, false);
        event((byte) vk, false);
        event((byte) vk, true);
        event((byte) modifierVk, true);
    }

    private static void event(byte vk, boolean keyUp) {
        Win32.INSTANCE.keybd_event(vk, (byte) 0, keyUp ? KEYEVENTF_KEYUP : 0, null);
        WinDriver.sleep(15);
    }
}
