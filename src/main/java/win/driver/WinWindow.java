package win.driver;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.IntByReference;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A native Win32 window (top-level OR child control).  This is the analogue of
 * a Playwright "Page"/element: you can read its text, find child controls,
 * click, type, close and screenshot it.  All operations go through the HWND.
 *
 * A WinWindow is immutable with respect to its handle; the underlying window
 * may disappear (e.g. after close), so callers should use isVisible()/isValid()
 * before acting.
 */
public class WinWindow {

    private final Pointer handle;
    private final WinProcess owner;

    protected WinWindow(Pointer handle, WinProcess owner) {
        this.handle = handle;
        this.owner = owner;
    }

    public static WinWindow of(Pointer handle) {
        return new WinWindow(handle, null);
    }

    public Pointer handle() {
        return handle;
    }

    public WinProcess owner() {
        return owner;
    }

    /** Return a copy of this window bound to the given process (scoping). */
    public WinWindow withOwner(WinProcess process) {
        return new WinWindow(handle, process);
    }

    // ----- identity ------------------------------------------------------

    /** Window title / control text (GetWindowText).  May be cached cross-process. */
    public String text() {
        int len = Win32.INSTANCE.GetWindowTextLengthW(handle);
        if (len <= 0) {
            return "";
        }
        char[] buf = new char[len + 1];
        Win32.INSTANCE.GetWindowTextW(handle, buf, buf.length);
        return new String(buf, 0, len);
    }

    /** Window title (alias of text()). */
    public String title() {
        return text();
    }

    /** Win32 window class (GetClassNameW). */
    public String className() {
        char[] buf = new char[256];
        int n = Win32.INSTANCE.GetClassNameW(handle, buf, buf.length);
        if (n <= 0) {
            return "";
        }
        return new String(buf, 0, n);
    }

    public boolean isVisible() {
        return handle != null && handle != Pointer.NULL && Win32.INSTANCE.IsWindowVisible(handle);
    }

    public boolean isEnabled() {
        return handle != null && handle != Pointer.NULL && Win32.INSTANCE.IsWindowEnabled(handle);
    }

    public boolean isValid() {
        return handle != null && handle != Pointer.NULL;
    }

    // ----- text of Edit controls ----------------------------------------

    /**
     * Read an EDIT control's content by sending WM_GETTEXT to the control.
     * This is cross-process-safe for EDIT controls and reflects the buffer
     * actually updated by WM_CHAR typing, unlike GetWindowText which may return
     * a cached value (LCL caches Text).
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

    // ----- typing / filling ----------------------------------------------

    /**
     * Type into an EDIT control by posting WM_CHAR messages straight to it.
     * Unlike keybd_event (which only reaches the foreground window), a
     * background JVM can drive the control reliably, and the control's Text
     * property and OnChange are updated (same effect as pywinauto.type_keys).
     */
    public void fill(String value) {
        Win32.INSTANCE.SendMessageW(handle, Win32.WM_SETTEXT, 0, new WString(""));
        sleep(30);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            Win32.INSTANCE.SendMessageW(handle, Win32.WM_CHAR, ch & 0xFFFF, 1);
            sleep(10);
        }
    }

    /** Alias of fill(). */
    public void type(String value) {
        fill(value);
    }

    // ----- clicking ------------------------------------------------------

    /**
     * Click a button via SendMessageTimeoutW(BM_CLICK).  The timeout prevents
     * the caller from hanging if the target window's thread is busy (e.g. a
     * synchronous search running on the UI thread).
     */
    public void click() {
        int[] res = new int[1];
        Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.BM_CLICK, 0, 0, Win32.SMTO_NORMAL, 5000, res);
    }

    /** Click via WM_COMMAND + BN_CLICKED (alternative to BM_CLICK). */
    public void clickCommand() {
        int ctrlId = Win32.INSTANCE.GetDlgCtrlID(handle);
        long wParam = ((long) (Win32.BN_CLICKED & 0xFFFF)) | (((long) ctrlId & 0xFFFF) << 16);
        Pointer parent = Win32.INSTANCE.GetParent(handle);
        if (parent != null) {
            Win32.INSTANCE.SendMessageW(parent, Win32.WM_COMMAND, wParam, ((long) Pointer.nativeValue(handle)));
        }
    }

    // ----- focus / foreground -------------------------------------------

    /**
     * Put keyboard focus onto this control and bring its top-level window to
     * the front.  A background JVM is NOT the foreground process, so plain
     * SetForegroundWindow alone is refused by Windows.  Like pywinauto.set_focus
     * we temporarily attach our input queue to the target thread's queue.
     */
    public void clickFocus() {
        Pointer window = topLevelOwner();
        long targetThread = Win32.INSTANCE.GetWindowThreadProcessId(window, null);
        long current = Kernel32Bridge.currentThreadId();
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

    /** Bring the top-level owner window to the foreground (best-effort). */
    public void foreground() {
        Pointer top = topLevelOwner();
        Win32.INSTANCE.ShowWindow(top, Win32.SW_RESTORE);
        Win32.INSTANCE.SetForegroundWindow(top);
        sleep(60);
    }

    /** Simple focus without thread-attach (set foreground + focus). */
    public void setFocus() {
        Win32.INSTANCE.ShowWindow(handle, Win32.SW_RESTORE);
        Win32.INSTANCE.SetForegroundWindow(handle);
    }

    // ----- close ---------------------------------------------------------

    /** Post a WM_CLOSE to this window (asynchronous; like pressing the X). */
    public void close() {
        Win32.INSTANCE.PostMessageW(handle, Win32.WM_CLOSE, 0, 0);
    }

    // ----- children / queries -------------------------------------------

    /** Enumerate ALL descendant controls of this window (EnumChildWindows). */
    public List<WinWindow> children() {
        final List<WinWindow> out = new ArrayList<>();
        Win32.INSTANCE.EnumChildWindows(handle, (child, lParam) -> {
            out.add(new WinWindow(child, owner));
            return true;
        }, Pointer.NULL);
        return out;
    }

    /** Enumerate descendants matching a single By selector. */
    public List<WinWindow> children(final By by) {
        final List<WinWindow> matches = new ArrayList<>();
        for (WinWindow w : children()) {
            if (ByMatcher.matches(w, by)) {
                matches.add(w);
            }
        }
        return matches;
    }

    /** Find the first descendant matching the By selector. */
    public WinWindow child(final By by) {
        List<WinWindow> matches = children(by);
        int idx = by.index();
        if (idx < matches.size()) {
            return matches.get(idx);
        }
        throw new WinException("No child matched " + by + " under " + this
                + " (found " + matches.size() + " matches)");
    }

    /** Create a lazy Locator that resolves this selector under this window. */
    public Locator locator(final By by) {
        return new Locator(this, by);
    }

    /** Create a lazy Locator that resolves a simple class/text selector. */
    public Locator locator(String selector) {
        return new Locator(this, parseSimple(selector));
    }

    private static By parseSimple(String selector) {
        String s = selector.trim();
        if (s.startsWith("#")) {
            return By.className(s.substring(1).trim());
        }
        if (s.startsWith("text=")) {
            return By.text(s.substring(5).trim());
        }
        if (s.startsWith("title=")) {
            return By.title(s.substring(6).trim());
        }
        return By.className(s);
    }

    // ----- geometry / screenshots ---------------------------------------

    public int[] rect() {
        RECT r = new RECT();
        Win32.INSTANCE.GetWindowRect(handle, r);
        return new int[]{r.left, r.top, r.right, r.bottom};
    }

    public int width() {
        int[] r = rect();
        return r[2] - r[0];
    }

    public int height() {
        int[] r = rect();
        return r[3] - r[1];
    }

    /** Capture this window into a PNG using PrintWindow + GDI. */
    public File screenshot(Path dir, String fileName) throws IOException {
        int[] r = rect();
        int width = r[2] - r[0];
        int height = r[3] - r[1];
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid window rect for screenshot: " + this);
        }
        Gdi gdi = Gdi.INSTANCE;
        Pointer hdcWin = null;
        Pointer hdcMem = null;
        Pointer hbm = null;
        Pointer old = null;
        try {
            hdcWin = Win32.INSTANCE.GetDC(handle);
            if (hdcWin == null) {
                throw new IOException("GetDC returned null for " + handle);
            }
            hdcMem = gdi.CreateCompatibleDC(hdcWin);
            hbm = gdi.CreateCompatibleBitmap(hdcWin, width, height);
            old = gdi.SelectObject(hdcMem, hbm);
            boolean ok = Win32.INSTANCE.PrintWindow(handle, hdcMem, Win32.PW_RENDERFULLCONTENT);
            if (!ok) {
                System.err.println("[win.driver] PrintWindow returned false for " + handle);
            }
            int stride = width * 4;
            Memory bits = new Memory((long) stride * height);
            Gdi.BITMAPINFO bmi = new Gdi.BITMAPINFO();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = 0;
            bmi.bmiHeader.write();
            gdi.GetDIBits(hdcMem, hbm, 0, height, bits, bmi, 0);

            byte[] bytes = bits.getByteArray(0, (int) (stride * height));
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                int row = y * stride;
                for (int x = 0; x < width; x++) {
                    int p = row + x * 4;
                    int b = bytes[p] & 0xFF;
                    int g = bytes[p + 1] & 0xFF;
                    int r2 = bytes[p + 2] & 0xFF;
                    int a = bytes[p + 3] & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r2 << 16) | (g << 8) | b);
                }
            }
            Files.createDirectories(dir);
            File out = dir.resolve(fileName).toFile();
            ImageIO.write(img, "png", out);
            return out;
        } finally {
            if (old != null) {
                gdi.SelectObject(hdcMem, old);
            }
            if (hbm != null) {
                gdi.DeleteObject(hbm);
            }
            if (hdcMem != null) {
                gdi.DeleteDC(hdcMem);
            }
            if (hdcWin != null) {
                Win32.INSTANCE.ReleaseDC(handle, hdcWin);
            }
        }
    }

    // ----- internals -----------------------------------------------------

    protected Pointer topLevelOwner() {
        Pointer p = handle;
        Pointer parent = Win32.INSTANCE.GetParent(p);
        int guard = 0;
        while (parent != null && guard++ < 32) {
            p = parent;
            parent = Win32.INSTANCE.GetParent(p);
        }
        return p;
    }

    /** Process id that owns this window handle. */
    public long processId() {
        IntByReference pid = new IntByReference();
        Win32.INSTANCE.GetWindowThreadProcessId(handle, pid);
        return pid.getValue() & 0xFFFFFFFFL;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== keyboard / control-key sending ==================================
    // goToThisWindow must be true when the control belongs to another process
    // and keybd_event (which targets the foreground window) must reach it.

    /**
     * Send a single control key (Enter, Tab, Delete, Esc, F1..F12, arrows...).
     * Uses keybd_event which targets the FOREGROUND window; call goToThisWindow()
     * first to make sure this window is foreground.
     */
    public void pressKey(int vk) {
        WinKey.press(vk);
    }

    /** Convenience: press Enter (VK_RETURN). */
    public void enter() {
        pressKey(WinKey.VK_RETURN);
    }

    /** Convenience: press Delete (VK_DELETE). */
    public void del() {
        pressKey(WinKey.VK_DELETE);
    }

    /** Convenience: press Escape. */
    public void escape() {
        pressKey(WinKey.VK_ESCAPE);
    }

    /** Convenience: press Tab. */
    public void tab() {
        pressKey(WinKey.VK_TAB);
    }

    /** Convenience: press Backspace. */
    public void back() {
        pressKey(WinKey.VK_BACK);
    }

    /** Convenience: press an arrow key (WinKey.VK_LEFT/UP/RIGHT/DOWN). */
    public void arrow(int vk) {
        pressKey(vk);
    }

    /**
     * Bring this window's top-level owner to the foreground so synthetic
     * keyboard input (keybd_event) lands here.  See clickFocus().
     */
    public void goToThisWindow() {
        foreground();
    }

    // ===== checkboxes ======================================================

    /** True if this control is a Win32 button with the BS_CHECKBOX style. */
    public boolean isCheckBox() {
        long style = Win32.INSTANCE.GetWindowLongW(handle, Win32.GWL_STYLE);
        // BS_CHECKBOX (0x2) / BS_AUTOCHECKBOX (0x3) / BS_3STATE (0x5) / BS_AUTO3STATE (0x6)
        long bsType = style & 0x0000000FL;
        return bsType == 0x0002L || bsType == 0x0003L
                || bsType == 0x0005L || bsType == 0x0006L;
    }

    /** True if this control is a Win32 button with the BS_AUTORADIOBUTTON style. */
    public boolean isRadio() {
        long style = Win32.INSTANCE.GetWindowLongW(handle, Win32.GWL_STYLE);
        long bsType = style & 0x0000000FL;
        return bsType == 0x0004L || bsType == 0x0009L; // BS_RADIOBUTTON | BS_AUTORADIOBUTTON
    }

    /** Read the check state of a checkbox button (BST_CHECKED / BST_INDETERMINATE / BST_UNCHECKED). */
    public int checkState() {
        return (int) Win32.INSTANCE.SendMessageW(handle, Win32.BM_GETCHECK, 0, 0);
    }

    /** True if the checkbox is currently checked. */
    public boolean isChecked() {
        return checkState() == Win32.BST_CHECKED;
    }

    /** Set the check state of a checkbox without sending a BN_CLICKED notification. */
    public void setCheck(boolean value) {
        Win32.INSTANCE.SendMessageW(handle, Win32.BM_SETCHECK,
                value ? Win32.BST_CHECKED : Win32.BST_UNCHECKED, 0);
    }

    /** Toggle a checkbox by clicking it (sends BN_CLICKED, so OnClick fires). */
    public void clickCheck() {
        click();
    }

    /** Set a checkbox to the given state, clicking only if it differs. */
    public void setChecked(boolean value) {
        if (isChecked() != value) {
            click(); // real click => OnClick event
        }
    }

    // ===== list / combo helpers =============================================

    /** Number of items in the list box. --1 if the control is not a list box. */
    public int itemCount() {
        int[] res = new int[1];
        long ret = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.LB_GETCOUNT, 0, 0,
                Win32.SMTO_NORMAL, 3000, res);
        return ret == 0 ? -1 : res[0];
    }

    /** Currently selected index in the list box, or -1. */
    public int selectedIndex() {
        return (int) Win32.INSTANCE.SendMessageW(handle, Win32.LB_GETCURSEL, 0, 0);
    }

    /** Text of the currently selected list-box row, or "" if none. */
    public String selectedItem() {
        int idx = selectedIndex();
        if (idx < 0) {
            return "";
        }
        return listBoxItem(idx);
    }


    /**
     * A checklist box (Lazarus TCheckListBox) is a list box whose rows each carry
     * a check state, i.e. a "checkbox inside a table".  The rows are ordinary
     * list items; their check state is read/written with the list-box check
     * messages, so you address each cell like a normal list row and toggle its
     * check flag directly.  These helpers are no-ops for a plain list box.
     */

    private static final int LB_GETCHECK = 0x01B0; // get check state of an item
    private static final int LB_SETCHECK = 0x01B1; // set check state of an item

    /** Is this a checklist box (a list whose rows have check boxes)? */
    public boolean isCheckListBox() {
        return className().equals("LCLCheckListBox");
    }

    /** Get the check state (BST_CHECKED / BST_UNCHECKED) of a checklist row. */
    public int itemCheckState(int index) {
        return (int) Win32.INSTANCE.SendMessageW(handle, LB_GETCHECK, index, 0);
    }

    /** True if the given checklist row is checked. */
    public boolean isItemChecked(int index) {
        return itemCheckState(index) == Win32.BST_CHECKED;
    }

    /** Set the check state of a checklist row (does NOT fire OnClick). */
    public void setItemChecked(int index, boolean value) {
        Win32.INSTANCE.SendMessageW(handle, LB_SETCHECK, index,
                value ? Win32.BST_CHECKED : Win32.BST_UNCHECKED);
    }

    /** Toggle the check state of a checklist row (fires OnClick like a real click). */
    public void clickItemCheck(int index) {
        setItemChecked(index, !isItemChecked(index));
    }

    /** Text of the given list-box row. */
    public String listBoxItem(int index) {
        int[] lenRes = new int[1];
        long tr = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.LB_GETTEXTLEN, index, 0,
                Win32.SMTO_NORMAL, 3000, lenRes);
        int len = (tr == 0) ? 0 : lenRes[0];
        if (len <= 0) {
            return "";
        }
        char[] buf = new char[len + 1];
        int[] got = new int[1];
        long gr = Win32.INSTANCE.SendMessageTimeoutW(handle, Win32.LB_GETTEXT, index, buf,
                Win32.SMTO_NORMAL, 3000, got);
        if (gr == 0) {
            return "";
        }
        int n = Math.min(len, got[0]);
        return new String(buf, 0, n < 0 ? 0 : n);
    }

    /** Select the given list-box row (LB_SETCURSEL). Returns the new selection. */
    public void selectItem(int index) {
        Win32.INSTANCE.SendMessageW(handle, Win32.LB_SETCURSEL, index, 0);
    }

    /** All item texts of a list box. */
    public List<String> itemTexts() {
        List<String> out = new ArrayList<>();
        int n = itemCount();
        if (n < 0) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(listBoxItem(i));
        }
        return out;
    }

    // ===== combo box ========================================================

    /** True if this is a Win32 combo box (class "ComboBox"). */
    public boolean isComboBox() {
        return className().equals("ComboBox") || className().equals("ComboLBox");
    }

    /** Number of items in a combo box. */
    public int comboItemCount() {
        return (int) Win32.INSTANCE.SendMessageW(handle, Win32.CB_GETCOUNT, 0, 0);
    }

    /** Current selected index in a combo box, or -1. */
    public int comboSelectedIndex() {
        return (int) Win32.INSTANCE.SendMessageW(handle, Win32.CB_GETCURSEL, 0, 0);
    }

    /** Text of the given combo-box item. */
    public String comboItem(int index) {
        int len = (int) Win32.INSTANCE.SendMessageW(handle, Win32.CB_GETLBTEXTLEN, index, 0);
        if (len <= 0) {
            return "";
        }
        char[] buf = new char[len + 1];
        Win32.INSTANCE.SendMessageW(handle, Win32.CB_GETLBTEXT, index, buf);
        return new String(buf, 0, len);
    }

    /** Select a combo-box item by index. */
    public void comboSelect(int index) {
        Win32.INSTANCE.SendMessageW(handle, Win32.CB_SETCURSEL, index, 0);
    }

    /** Show/download the combo-box drop-down list. */
    public void comboShowDropDown(boolean show) {
        Win32.INSTANCE.SendMessageW(handle, Win32.CB_SHOWDROPDOWN, show ? 1 : 0, 0);
    }

    // ===== menu ==============================================================

    /**
     * Enumerate top-level menu items of THIS window (if it has a menu bar).
     * A menu is not a child window; it is owned by the frame window.  Returns
     * the menu item texts in position order.
     */
    public List<String> menuItems() {
        List<String> out = new ArrayList<>();
        Pointer menu = Win32.INSTANCE.GetMenu(handle);
        if (menu == null || menu == Pointer.NULL) {
            return out;
        }
        int count = Win32.INSTANCE.GetMenuItemCount(menu);
        if (count < 0) {
            return out;
        }
        for (int i = 0; i < count; i++) {
            char[] buf = new char[256];
            int n = Win32.INSTANCE.GetMenuStringW(menu, i, buf, buf.length, Win32.MF_BYPOSITION);
            out.add(n > 0 ? new String(buf, 0, n) : "");
        }
        return out;
    }

    /** True if the window has a menu bar. */
    public boolean hasMenu() {
        Pointer menu = Win32.INSTANCE.GetMenu(handle);
        return menu != null && menu != Pointer.NULL;
    }

    // ===== hierarchy dump / text search ======================================

    /**
     * Return a human-readable, indented dump of this window and ALL descendant
     * controls: each line has the element type (Win32 class name) and its
     * window text/caption.  Used to log the full control tree of a window.
     */
    public String dumpHierarchy() {
        StringBuilder sb = new StringBuilder();
        appendHierarchy(sb, this, 0);
        return sb.toString();
    }

    private static void appendHierarchy(StringBuilder sb, WinWindow w, int depth) {
        String indent = "    ".repeat(depth);
        sb.append(indent)
          .append("- ").append(w.className())
          .append(" : ").append(escape(w.text()))
          .append(" [visible=").append(w.isVisible()).append("]")
          .append(System.lineSeparator());
        for (WinWindow child : w.children()) {
            appendHierarchy(sb, child, depth + 1);
        }
    }

    /**
     * Depth-first search across this window and all descendants, returning the
     * controls whose window text EQUALS the given text (case-insensitive).
     */
    public List<WinWindow> findAllByText(String text) {
        List<WinWindow> out = new ArrayList<>();
        collectByText(this, text, out, false);
        return out;
    }

    /** Case-insensitive substring variant of {@link #findAllByText(String)}. */
    public List<WinWindow> findAllByTextContains(String text) {
        List<WinWindow> out = new ArrayList<>();
        collectByText(this, text, out, true);
        return out;
    }

    /** First control (this window or a descendant) whose text equals {@code text}. */
    public WinWindow findByText(String text) {
        List<WinWindow> all = findAllByText(text);
        return all.isEmpty() ? null : all.get(0);
    }

    private static void collectByText(WinWindow w, String text, List<WinWindow> out, boolean contains) {
        String t = w.text();
        if (contains ? t.toLowerCase().contains(text.toLowerCase()) : t.equalsIgnoreCase(text)) {
            out.add(w);
        }
        for (WinWindow child : w.children()) {
            collectByText(child, text, out, contains);
        }
    }

    private static String escape(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    @Override
    public String toString() {
        return "WinWindow[" + handle + ", class=" + className() + ", text=" + text()
                + ", visible=" + isVisible() + "]";
    }
}
