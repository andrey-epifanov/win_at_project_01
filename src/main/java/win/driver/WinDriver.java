package win.driver;

import java.io.IOException;

/**
 * Playwright-like entry point for driving Win32 applications.
 *
 * <pre>
 *   WinProcess app = WinDriver.launch(
 *       "D:\\app\\MyApp.exe", "D:\\app");
 *   WinWindow main = app.mainWindow(By.title("My App"), 10000);
 *
 *   main.locator(By.className("Edit").nth(1)).fill("hello");
 *   main.locator(By.text("OK")).click();
 *
 *   main.screenshot(Path.of("shots"), "main.png");
 *
 *   app.terminate();
 * </pre>
 */
public final class WinDriver {

    private WinDriver() {
    }

    /** Launch an executable in the given working directory. */
    public static WinProcess launch(String exePath, String workDir) throws IOException {
        return WinProcess.start(exePath, workDir);
    }

    /** Sleep helper available to the whole toolkit. */
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
