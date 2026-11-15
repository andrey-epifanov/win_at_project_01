package win.win32.driver;

/** Runtime exception thrown by the win.driver toolkit. */
public class WinException extends RuntimeException {
    public WinException(String message) {
        super(message);
    }

    public WinException(String message, Throwable cause) {
        super(message, cause);
    }
}
