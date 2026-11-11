package com.example.reporttest.driver;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;

import java.util.ArrayList;
import java.util.List;

/**
 * A thin, immutable handle wrapper around a native Win32 HWND, exposing the
 * operations needed for the test (text, class, visibility, child lookup,
 * setting text, clicking, closing, list-box readback, status-bar read and
 * real keyboard input).
 */
public final class NativeWindow {

    private static final int SW_RESTORE = 9;
    private static final int KEYEVENTF_KEYUP = 0x0002;
    private static final int VK_SHIFT = 0x10;
    private static final int UNMAPPABLE = 0xFFFF; // VkKeyScanW returns -1 as short

    private final Pointer handle;

    private NativeWindow(Pointer handle) {
        this.handle = handle;
    }

    public static NativeWindow of(Pointer handle) {
        return new NativeWindow(handle);
    }

    public Pointer handle() {
        return handle;
    }

    public String windowText() {
        int len = Win32.INSTANCE.GetWindowTextLengthW(handle);
        if (len <= 0) {
            return "";
        }
        char[] buf = new char[len + 1];
        Win32.INSTANCE.GetWindowTextW(handle, buf, buf.length);
        return new String(buf, 0, len);
    }

    /**
     * Read an EDIT control's content by sending WM_GETTEXT to the control.
     * This is cross-process-safe for EDIT controls and reflects the buffer
     * actually updated by WM_CHAR typing, unlike GetWindowText which may return
     * a cached value for a control owned by a different process (LCL).
     */
    public String editText() {
        int len = (int) Win32.INSTANCE.SendMessageW(handle, Win32.WM_GETTEXTLENGTH, 0, 0L);
        if (len <= 0) {
            return "";
        }
        char[] buf = new char[len + 1];
        long got = Win32.INSTANCE.SendMessageW(handle, Win32.WM_GETTEXT, (long) buf.length, buf);
        if (got <= 0) {
            return "";
        }
        return new String(buf, 0, (int) got);
    }

    public String className() {
        char[] buf = new char[256];
        int n = Win32.INSTANCE.GetClassNameW(handle, buf, buf.length);
        if (n <= 0) {
            return "";
        }
        return new String(buf, 0, n);
    }

    public boolean isVisible() {
        return Win32.INSTANCE.IsWindowVisible(handle);
    }

    public boolean isEnabled() {
        return Win32.INSTANCE.IsWindowEnabled(handle);
    }

    public boolean isValid() {
        return handle != null && handle != Pointer.NULL;
    }

    public void setFocus() {
        Win32.INSTANCE.ShowWindow(handle, SW_RESTORE);
        Win32.INSTANCE.SetForegroundWindow(handle);
    }

    /** Put keyboard focus on this control (and bring its window to front). */

    /**
     * Bring the top-level (owner) window to the foreground so synthetic
     * keyboard input (keybd_event) is delivered to this app instead of the
     * previously focused window.
     */
    public void foreground() {
        Pointer top = this.topLevelOwner();
        Win32.INSTANCE.ShowWindow(top, SW_RESTORE);
        Win32.INSTANCE.SetForegroundWindow(top);
        // give the foreground switch a moment to take effect
        sleep(60);
    }

    /** Walk up to the top-level owner window for this control. */
    private Pointer topLevelOwner() {
        Pointer p = handle;
        Pointer parent = Win32.INSTANCE.GetParent(p);
        int guard = 0;
        while (parent != null && guard++ < 32) {
            p = parent;
            parent = Win32.INSTANCE.GetParent(p);
        }
        return p;
    }
    /**
     * Force keyboard focus onto this control.  A forked background JVM (e.g.
     * under surefire) is NOT the foreground process, so SetForegroundWindow
     * alone is refused by Windows.  Like pywinauto.set_focus, we temporarily
     * ATTACH this thread's input queue to the target window's thread, then
     * bring it up and give it focus, then detach.  This makes synthetic
     * keybd_event input land in the right control.
     */
    public void clickFocus() {
        Pointer window = topLevelOwner();
        long targetThread = Win32.INSTANCE.GetWindowThreadProcessId(window, null);
        long current = Kernel32.INSTANCE.GetCurrentThreadId();
        boolean attached = false;
        if (targetThread != 0 && targetThread != current) {
            attached = Win32.INSTANCE.AttachThreadInput(current, targetThread, true);
        }
        try {
            Win32.INSTANCE.ShowWindow(window, Win32.SW_RESTORE);
            Win32.INSTANCE.BringWindowToTop(window);
            Win32.INSTANCE.SetForegroundWindow(window);
            Win32.INSTANCE.SetFocus(handle);
        } finally {
            if (attached) {
                Win32.INSTANCE.AttachThreadInput(current, targetThread, false);
            }
        }
    }

    /** Set window text via the real SetWindowTextW (native text, not LCL-cached). */
    public void setText(String text) {
        Win32.INSTANCE.SetWindowTextW(handle, new WString(text));
    }

    /**
     * Type the given string with real synthetic keyboard events so the LCL
     * control's Text property and OnChange are updated (SetWindowText /
     * EM_REPLACESEL alone do NOT refresh the LCL-cached Text).
     */
    /**
     * Type into an EDIT control by posting WM_CHAR messages straight to it.
     * Unlike keybd_event (which only reaches the foreground window), a
     * background forked JVM can drive the control reliably, and the LCL
     * control's Text property and OnChange are updated (the same effect
     * pywinauto's type_keys produces).
     */
    public void typeText(String text) {
        // clear existing content first (WM_SETTEXT with an empty string)
        Win32.INSTANCE.SendMessageW(handle, Win32.WM_SETTEXT, 0, new WString(""));
        sleep(30);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // WM_CHAR wParam carries the UTF-16 code unit; lParam repeat=1
            Win32.INSTANCE.SendMessageW(handle, Win32.WM_CHAR, ch & 0xFFFF, 1);
            sleep(10);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Click via BM_CLICK (works for standard buttons and LCL buttons). */
    public void click() {
        int[] res = new int[1];
        // SendMessageTimeoutW avoids blocking the test thread forever if the
        // target window's thread is busy (the LCL search runs synchronously).
        Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.BM_CLICK, 0, 0, Win32.SMTO_NORMAL, 5000, res);
    }

    /** Click via WM_COMMAND with BN_CLICKED (alternative to BM_CLICK). */
    public void clickCommand() {
        int ctrlId = Win32.INSTANCE.GetDlgCtrlID(handle);
        long wParam = ((long) (Win32.BN_CLICKED & 0xFFFF)) | (((long) ctrlId & 0xFFFF) << 16);
        Pointer parent = Win32.INSTANCE.GetParent(handle);
        if (parent != null) {
            Win32.INSTANCE.SendMessageW(parent, Win32.WM_COMMAND, wParam, ((long) Pointer.nativeValue(handle)));
        }
    }

    public void postClose() {
        Win32.INSTANCE.PostMessageW(handle, Win32.WM_CLOSE, 0, 0);
    }

    /** True if this window owns a menu bar (GetMenu). */
    public boolean hasMenu() {
        return Win32.INSTANCE.GetMenu(handle) != null;
    }

    /** Send a menu command to this window by its command id (WM_COMMAND wParam). */
    public void menuCommand(int commandId) {
        Win32.INSTANCE.PostMessageW(handle, Win32.WM_COMMAND, commandId, 0);
        sleep(120);
    }

    /** Find a top-level owner window with the exact given title belonging to this app (pid). */
    public boolean isTopTitleVisible(String title) {
        for (NativeWindow w : WindowFinder.topWindows()) {
            if (WindowFinder.processId(w) == WindowFinder.processId(this) && w.windowText().equals(title)) {
                return w.isVisible();
            }
        }
        return false;
    }

    /** Click the (single) OK / action button of a modal dialog window (the ReportSearch dialog). */
    public void clickFirstChildButton() {
        List<NativeWindow> buttons = childrenByClass("Button");
        if (!buttons.isEmpty()) {
            buttons.get(0).click();
        }
    }

    /**
     * Toggle the check box of list-box row {@code index} by sending a real mouse
     * click on the left-most check glyph of that row.  LCL TCheckListBox stores
     * its check state internally (LB_GETCHECK/LB_SETCHECK return 0), so the only
     * reliable way is a physical mouse click.
     */
    public void clickRowCheckbox(int index) {
        RECT itemRect = listBoxItemRect(index);
        RECT client = new RECT();
        Win32.INSTANCE.GetClientRect(handle, client);
        POINT origin = new POINT();
        Win32.INSTANCE.ClientToScreen(handle, origin);
        // Check glyph sits at the left edge of the row.
        int itemTop = itemRect.top;
        int itemBottom = itemRect.bottom;
        int midY = (itemTop + itemBottom) / 2;
        int screenX = origin.x + 8;
        int screenY = origin.y + midY;
        // Bring the owner window to the foreground first, then physically click.
        this.foreground();
        Win32.INSTANCE.SetCursorPos(screenX, screenY);
        sleep(40);
        Win32.INSTANCE.mouse_event(Win32.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, Pointer.NULL);
        sleep(40);
        Win32.INSTANCE.mouse_event(Win32.MOUSEEVENTF_LEFTUP, 0, 0, 0, Pointer.NULL);
        sleep(120);
    }

    /** Screen rectangle of list-box row {@code index} (client coordinates of the list box). */
    private RECT listBoxItemRect(int index) {
        RECT r = new RECT();
        int[] result = new int[1];
        Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.LB_GETITEMRECT, index, r, Win32.SMTO_NORMAL, 3000, result);
        return r;
    }

    /** Enumerate child windows matching a class name (best-effort). */
    public List<NativeWindow> childrenByClass(final String className) {
        final List<NativeWindow> out = new ArrayList<>();
        Win32.INSTANCE.EnumChildWindows(handle, (child, lParam) -> {
            NativeWindow c = NativeWindow.of(child);
            if (c.className().equals(className)) {
                out.add(c);
            }
            return true;
        }, Pointer.NULL);
        return out;
    }

    /** Enumerate ALL child windows (regardless of class). */
    public List<NativeWindow> children() {
        final List<NativeWindow> out = new ArrayList<>();
        Win32.INSTANCE.EnumChildWindows(handle, (child, lParam) -> {
            out.add(NativeWindow.of(child));
            return true;
        }, Pointer.NULL);
        return out;
    }

    /** Find a child whose window text equals the given text and class. */
    public NativeWindow childByText(String text, String className) {
        for (NativeWindow c : childrenByClass(className)) {
            if (c.windowText().equals(text)) {
                return c;
            }
        }
        throw new IllegalStateException("Child not found: text='" + text + "' class='" + className + "'");
    }

    /** Find the (first) child of the given class. */
    public NativeWindow childByClass(String className) {
        for (NativeWindow c : childrenByClass(className)) {
            return c;
        }
        throw new IllegalStateException("Child not found: class='" + className + "'");
    }

    /** Number of items in a list box. */
    public int listBoxCount() {
        int[] res = new int[1];
        long ret = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.LB_GETCOUNT, 0, 0, Win32.SMTO_NORMAL, 3000, res);
        if (ret == 0) {
            return -1; // timed out / window busy -> caller retries
        }
        return res[0];
    }

    /** Text of the given list-box row. */
    public String listBoxItem(int index) {
        int[] lenRes = new int[1];
        long tr = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.LB_GETTEXTLEN, index, 0, Win32.SMTO_NORMAL, 3000, lenRes);
        int len = (tr == 0) ? 0 : lenRes[0];
        if (len <= 0) {
            return "";
        }
        char[] buf = new char[len + 1];
        int[] got = new int[1];
        long gr = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.LB_GETTEXT, index, buf, Win32.SMTO_NORMAL, 3000, got);
        if (gr == 0) {
            return "";
        }
        int n = Math.min(len, got[0]);
        return new String(buf, 0, n < 0 ? 0 : n);
    }

    public void selectListBoxItem(int index) {
        Win32.INSTANCE.SendMessageW(handle, Win32.LB_SETCURSEL, index, 0);
    }

    /** Text of the given status-bar part (0-based). */
    public String statusBarPart(int part) {
        int[] lenRes = new int[1];
        long tr = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.SB_GETTEXTLENGTH, part, 0, Win32.SMTO_NORMAL, 3000, lenRes);
        int len = (tr == 0) ? 0 : lenRes[0];
        if (len <= 0) {
            return "";
        }
        char[] buf = new char[len + 1];
        int[] got = new int[1];
        long gr = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.SB_GETTEXT, part, buf, Win32.SMTO_NORMAL, 3000, got);
        if (gr == 0) {
            return "";
        }
        int n = Math.min(len, got[0]);
        return new String(buf, 0, n < 0 ? 0 : n);
    }

    /** Status-bar text as read by the app (whole bar / part 0). */
    public String statusBarText() {
        return statusBarPart(0);
    }

    /** Screen rectangle of this window (for screenshots). */
    public RECT getRect() {
        RECT r = new RECT();
        Win32.INSTANCE.GetWindowRect(handle, r);
        return r;
    }

    @Override
    public String toString() {
        return "NativeWindow[" + handle + ", class=" + className() + ", text=" + windowText()
                + ", visible=" + isVisible() + "]";
    }
}