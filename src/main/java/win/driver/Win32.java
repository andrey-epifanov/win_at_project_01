package win.driver;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Thin JNA mapping of the Win32 user32 functions needed to drive windows.
 *
 * IMPORTANT: JNA maps a Java method name straight to the native symbol, so all
 * method names MUST be the real user32 export (e.g. SendMessageW, GetClassNameW).
 * Overloaded SendMessageW variants are distinguished by argument type, which
 * JNA resolves correctly.
 */
public interface Win32 extends StdCallLibrary {

    Win32 INSTANCE = Native.load("user32", Win32.class);

    // Generic window messages
    int WM_SETTEXT    = 0x000C;
    int WM_GETTEXT    = 0x000D;
    int WM_GETTEXTLENGTH = 0x000E;
    int WM_CHAR       = 0x0102;
    int WM_COMMAND    = 0x0111;
    int WM_CLOSE      = 0x0010;

    // Keyboard messages
    int WM_KEYDOWN    = 0x0100;
    int WM_KEYUP      = 0x0101;

    // Button messages / states
    int BM_CLICK      = 0x00F5;
    int BM_GETCHECK   = 0x00F0;
    int BM_SETCHECK   = 0x00F1;
    int BN_CLICKED    = 0x0000;
    int BST_UNCHECKED = 0x0000;
    int BST_CHECKED   = 0x0001;
    int BST_INDETERMINATE = 0x0002;

    // Edit control messages
    int EM_SETSEL     = 0x00B1;
    int EM_REPLACESEL = 0x00C2;

    // List box messages
    int LB_GETCOUNT   = 0x018B;
    int LB_GETTEXTLEN = 0x018A;
    int LB_GETTEXT    = 0x0189;
    int LB_GETCURSEL  = 0x0188;
    int LB_SETCURSEL  = 0x0186;

    // Combo box messages
    int CB_GETCOUNT   = 0x0146;
    int CB_GETCURSEL  = 0x0147;
    int CB_SETCURSEL  = 0x014E;
    int CB_GETLBTEXT  = 0x0148;
    int CB_GETLBTEXTLEN = 0x0149;
    int CB_SHOWDROPDOWN = 0x014F;

    // Status bar messages
    int SB_GETTEXTLENGTH = 0x0401;
    int SB_GETTEXT       = 0x0402;

    // ShowWindow commands
    int SW_RESTORE = 9;
    int SW_SHOW    = 5;

    // SendMessageTimeout flags
    int SMTO_NORMAL = 0x0000;

    // PrintWindow flags
    int PW_RENDERFULLCONTENT = 0x00000002;

    // GetWindowLong indices
    int GWL_STYLE = -16;

    // GetMenuItemInfo / GetMenuString flags
    int MF_BYPOSITION = 0x0400;

    interface WNDENUMPROC extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer hWnd, Pointer lParam);
    }

    boolean EnumWindows(WNDENUMPROC lpEnumFunc, Pointer lParam);
    boolean EnumChildWindows(Pointer hWndParent, WNDENUMPROC lpEnumFunc, Pointer lParam);

    long GetWindowThreadProcessId(Pointer hWnd, com.sun.jna.ptr.IntByReference lpdwProcessId);
    int  GetWindowTextW(Pointer hWnd, char[] lpString, int nMaxCount);
    int  GetWindowTextLengthW(Pointer hWnd);
    int  GetClassNameW(Pointer hWnd, char[] lpString, int nMaxCount);
    int  GetWindowLongW(Pointer hWnd, int nIndex);
    boolean IsWindowVisible(Pointer hWnd);
    boolean IsWindowEnabled(Pointer hWnd);
    boolean GetWindowRect(Pointer hWnd, RECT lpRect);
    Pointer GetParent(Pointer hWnd);
    int     GetDlgCtrlID(Pointer hWnd);

    boolean SetForegroundWindow(Pointer hWnd);
    boolean ShowWindow(Pointer hWnd, int nCmdShow);
    Pointer SetFocus(Pointer hWnd);

    // Foreground / input-queue helpers (reliable synthetic keyboard input)
    boolean AttachThreadInput(long idAttach, long idAttachTo, boolean fAttach);
    boolean BringWindowToTop(Pointer hWnd);
    Pointer GetForegroundWindow();

    boolean SetWindowTextW(Pointer hWnd, WString lpString);

    // SendMessageW overloads (same native symbol, different lParam types)
    long SendMessageW(Pointer hWnd, int Msg, long wParam, long lParam);
    long SendMessageW(Pointer hWnd, int Msg, long wParam, WString lParam);
    long SendMessageW(Pointer hWnd, int Msg, long wParam, char[] lParam);

    // SendMessageTimeoutW: returns after a timeout even if the target thread is
    // busy, so a hung/blocking dialog never hangs the caller.
    long SendMessageTimeoutW(Pointer hWnd, int msg, long wParam, long lParam, int flags, int timeout, int[] result);
    long SendMessageTimeoutW(Pointer hWnd, int msg, long wParam, char[] lParam, int flags, int timeout, int[] result);

    // Menu API (menus are not child windows; they belong to the window bar).
    Pointer GetMenu(Pointer hWnd);
    int     GetMenuItemCount(Pointer hMenu);
    int     GetMenuItemID(Pointer hMenu, int nPos);
    int     GetMenuStringW(Pointer hMenu, int nPos, char[] lpString, int nMaxCount, int flags);

    Pointer GetDC(Pointer hWnd);
    int ReleaseDC(Pointer hWnd, Pointer hdc);
    boolean PostMessageW(Pointer hWnd, int Msg, long wParam, long lParam);
    boolean PrintWindow(Pointer hWnd, Pointer hdc, int nFlags);

    /** Map a wide char to a virtual-key + shift state; returns -1 if unmappable. */
    short VkKeyScanW(char ch);
    /** Low-level synthetic keyboard input (flags: 0 down, 2 up). */
    void keybd_event(byte bVk, byte bScan, int dwFlags, Pointer dwExtraInfo);
}
