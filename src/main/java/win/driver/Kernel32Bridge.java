package win.driver;

import com.sun.jna.platform.win32.Kernel32;

/** Internal holder for kernel32 calls (GetCurrentThreadId lives in kernel32). */
final class Kernel32Bridge {
    private Kernel32Bridge() {
    }

    static long currentThreadId() {
        return Kernel32.INSTANCE.GetCurrentThreadId();
    }
}
