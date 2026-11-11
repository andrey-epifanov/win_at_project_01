package com.example.reporttest;

import org.junit.jupiter.api.Test;
import win.driver.By;
import win.driver.WinDriver;
import win.driver.WinProcess;
import win.driver.WinWindow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the reusable win.driver toolkit (Playwright-like API).
 * Launches ReportSearch.exe and drives it purely through win.driver:
 *   - launch a process
 *   - find the main window (by title)
 *   - address child controls (folders/buttons) by class/text
 *   - type into an Edit control
 *   - take a screenshot
 */
class WinDriverSmokeTest {

    private static final String EXE = "D:\\workspace_delphi\\project_01\\ReportSearch.exe";
    private static final String WORKDIR = "D:\\workspace_delphi\\project_01";
    private static final String REPORTS = "D:\\workspace_delphi\\project_01\\reports";
    private static final String MAIN_TITLE = "Поиск по отчётным формам";

    @Test
    void driveMainWindowLikePlaywright() throws IOException {
        WinProcess proc = WinDriver.launch(EXE, WORKDIR);
        try {
            // 1. Find the main window (by title), waiting up to 10s.
            WinWindow main = proc.mainWindow(MAIN_TITLE, 10000);
            assertNotNull(main, "main window not found");
            assertTrue(main.isVisible(), "main window not visible");
            System.out.println("[SMOKE] main=" + main);

            // 2. Address child controls by class / text.
            int buttonCount = main.children(By.className("Button")).size();
            int editCount = main.children(By.className("Edit")).size();
            assertTrue(buttonCount > 0, "no Button found");
            assertTrue(editCount > 0, "no Edit found");
            System.out.println("[SMOKE] child controls: buttons=" + buttonCount + " edits=" + editCount);

            // 3. Find the reports-folder Edit (the Edit whose text looks like "D:...").
            WinWindow folder = main.children(By.className("Edit")).stream()
                    .filter(e -> e.text().length() > 2 && e.text().charAt(1) == ':')
                    .findFirst().orElse(null);
            assertNotNull(folder, "folder edit not found");
            System.out.println("[SMOKE] folder before='" + folder.text() + "'");

            // 4. Type data into the Edit control.
            folder.clickFocus();
            folder.fill(REPORTS);
            String after = folder.editText();
            System.out.println("[SMOKE] folder after='" + after + "'");
            assertTrue(after.contains(REPORTS), "folder was not filled, got '" + after + "'");

            // 5. Screenshot through the toolkit.
            Path shotDir = Path.of("screenshots");
            java.io.File shot = main.screenshot(shotDir, "win_driver_smoke.png");
            System.out.println("[SMOKE] screenshot=" + shot.getAbsolutePath());
            assertTrue(Files.exists(shot.toPath()) && Files.size(shot.toPath()) > 0, "screenshot not written");

            // 6. Locator-style query on the button.
            var searchBtn = main.locator(By.text("Поиск"));
            assertEquals(1, searchBtn.count(), "search button count");
            System.out.println("[SMOKE] searchBtn locator count=" + searchBtn.count());
        } finally {
            proc.terminate();
        }
    }
}
