package com.example.reporttest.driver;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Thin JNA mapping of the subset of Win32 user32 functions needed to drive an
 * LCL (Lazarus) window: enumerate windows/children, read and set text, click
 * buttons, read a list box, read the status bar, close a window and send
 * synthetic keyboard input.
 *
 * IMPORTANT: JNA maps a Java method name straight to the native symbol, so all
 * method names MUST be the real user32 export.  SendMessageW is overloaded by
 * argument type (long / WString / char[]), which JNA resolves correctly.
 */
public interface Win32 extends StdCallLibrary {

    Win32 INSTANCE = Native.load("user32", Win32.class);

    int WM_SETTEXT    = 0x000C;
    int WM_COMMAND    = 0x0111;
    int WM_CLOSE      = 0x0010;
    int BM_CLICK      = 0x00F5;
    int BN_CLICKED    = 0x0000;

    // Edit control messages
    int WM_CHAR      = 0x0102;
    int WM_GETTEXT    = 0x000D;
    int WM_GETTEXTLENGTH = 0x000E;
    int EM_SETSEL     = 0x00B1;
    int EM_REPLACESEL = 0x00C2;

    // List box messages
    int LB_GETCOUNT   = 0x018B;
    int LB_GETTEXTLEN = 0x018A;
    int LB_GETTEXT    = 0x0189;
    int LB_SETCURSEL  = 0x0186;
    int LB_GETITEMRECT = 0x0198;

    // Status bar messages
    int SB_GETTEXTLENGTH = 0x0401;
    int SB_GETTEXT       = 0x0402;

    interface WNDENUMPROC extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer hWnd, Pointer lParam);
    }

    boolean EnumWindows(WNDENUMPROC lpEnumFunc, Pointer lParam);
    boolean EnumChildWindows(Pointer hWndParent, WNDENUMPROC lpEnumFunc, Pointer lParam);

    long GetWindowThreadProcessId(Pointer hWnd, com.sun.jna.ptr.IntByReference lpdwProcessId);
    int  GetWindowTextW(Pointer hWnd, char[] lpString, int nMaxCount);
    int  GetWindowTextLengthW(Pointer hWnd);
    int  GetClassNameW(Pointer hWnd, char[] lpString, int nMaxCount);
    boolean IsWindowVisible(Pointer hWnd);
    boolean IsWindowEnabled(Pointer hWnd);
    boolean GetWindowRect(Pointer hWnd, RECT lpRect);
    Pointer GetParent(Pointer hWnd);
    int     GetDlgCtrlID(Pointer hWnd);
    Pointer GetMenu(Pointer hWnd);

    boolean SetForegroundWindow(Pointer hWnd);
    boolean ShowWindow(Pointer hWnd, int nCmdShow);
    Pointer SetFocus(Pointer hWnd);

    // --- Foreground/thread helpers (needed for reliable synthetic keyboard input) ---

    /** AttachThreadInput: temporarily bind this thread's input queue to another window's thread. */
    boolean AttachThreadInput(long idAttach, long idAttachTo, boolean fAttach);
    /** BringWindowToTop. */
    boolean BringWindowToTop(Pointer hWnd);
    /** SetForegroundWindow already declared above. */
    Pointer GetForegroundWindow();

    /** ShowWindow commands. */
    int SW_RESTORE = 9;
    int SW_SHOW = 5;
    /** Real user32: set a window's text (Unicode). */
    boolean SetWindowTextW(Pointer hWnd, WString lpString);

    /** SendMessageW overloads (same native symbol, different lParam types). */
    long SendMessageW(Pointer hWnd, int Msg, long wParam, long lParam);
    long SendMessageW(Pointer hWnd, int Msg, long wParam, WString lParam);
    long SendMessageW(Pointer hWnd, int Msg, long wParam, char[] lParam);

    long SendMessageTimeoutW(Pointer hWnd, int msg, long wParam, long lParam, int flags, int timeout, int[] result);
    long SendMessageTimeoutW(Pointer hWnd, int msg, long wParam, char[] lParam, int flags, int timeout, int[] result);
    long SendMessageTimeoutW(Pointer hWnd, int msg, long wParam, RECT lParam, int flags, int timeout, int[] result);
    int SMTO_NORMAL = 0x0000;


    Pointer GetDC(Pointer hWnd);
    int ReleaseDC(Pointer hWnd, Pointer hdc);
    boolean PostMessageW(Pointer hWnd, int Msg, long wParam, long lParam);
    boolean ClientToScreen(Pointer hWnd, com.sun.jna.platform.win32.WinDef.POINT lpPoint);
    boolean GetClientRect(Pointer hWnd, RECT lpRect);
    boolean PrintWindow(Pointer hWnd, Pointer hdc, int nFlags);
    int PW_RENDERFULLCONTENT = 0x00000002;



    // Menu command helper: a menu item id sent as WM_COMMAND wParam to the window.
    int MF_BYPOSITION = 0x00000400;

    // Real mouse input (SetCursorPos + mouse_event) to toggle an LCL TCheckListBox row.
    boolean SetCursorPos(int X, int Y);
    void mouse_event(int dwFlags, int dx, int dy, int dwData, Pointer dwExtraInfo);
    int  MOUSEEVENTF_LEFTDOWN  = 0x0002;
    int  MOUSEEVENTF_LEFTUP    = 0x0004;

    /** VkKeyScanW: map a wide char to a virtual-key + shift state; returns -1 if unmappable. */
    short VkKeyScanW(char ch);
    /** keybd_event: low-level synthetic keyboard input (flags: 0 down, 2 up). */
    void keybd_event(byte bVk, byte bScan, int dwFlags, Pointer dwExtraInfo);
}
