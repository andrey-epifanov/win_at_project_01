package com.example.reporttest;

import com.example.reporttest.driver.NativeWindow;
import com.example.reporttest.driver.ReportSearchDriver;
import com.example.reporttest.driver.Screenshot;
import com.example.reporttest.driver.WindowFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end UI automation test for ReportSearch.exe.
 *
 * Scenario (as requested by the user):
 *   0. Launch the app
 *   1. Open the menu and click 'Open' (File -> Open, Ctrl+O)
 *   2. In the 'ReportSearch' dialog click OK -> the dialog closes
 *   3. Open the reports folder
 *   4. Find the report 'otchet_kassa_may_2026'
 *   5. Select the row and toggle its check box
 *   6. Open it ('Open checked')
 *   7. Press 'Close' -> the viewer window (window 2) closes
 *   8. Press the window close (X) on the main window -> it closes too (app exits)
 *
 * IMPORTANT: the app searches file CONTENT, not filenames.  The filename
 * 'otchet_kassa_may_2026' never appears in the file content, so the needle is a
 * unique content substring of the kassa report (see application.properties):
 *   app.search.needle = a unique content substring of the kassa report.
 *
 * All addressing goes through the native Win32 driver (JNA in the
 * com.example.reporttest.driver package): window, child controls, typing,
 * clicking, menu command, checkbox clicking, screenshots.
 */
@SpringBootTest
class ReportSearchScenarioTest {

    @Autowired
    private ReportSearchDriver driver;

    private static final long FIND_TIMEOUT_MS = 15000;
    private static final long EXIT_TIMEOUT_MS = 8000;

    @Test
    @DisplayName("menu -> open dialog ok -> folder -> find kassa -> select+checkbox -> open -> close viewer -> close main (X)")
    void scenario() throws IOException {
        Path shots = driver.screenshotDir();

        try {
            // --- 0. Launch the app -----------------------------------------
            driver.launch();
            long pid = driver.pid();

            // --- 1. Open the menu, click 'Open' (File -> Open) -------------
            NativeWindow main = driver.waitForMain(FIND_TIMEOUT_MS);
            assertNotNull(main, "Main window not found: " + driver.mainTitle());
            assertTrue(main.isVisible(), "Main window is not visible");
            assertTrue(main.hasMenu(), "Main window should have a menu bar");

            main.foreground();
            main.menuCommand(driver.fileOpenMenuId());   // File -> Open (Ctrl+O)
            WindowFinder.sleep(driver.afterClickWaitMs());

            // --- 2. In 'ReportSearch' dialog click OK -> dialog closes -------
            NativeWindow dialog = driver.waitForReportSearchDialog(FIND_TIMEOUT_MS);
            assertNotNull(dialog, "'ReportSearch' dialog did not open after File->Open");
            dialog.clickFirstChildButton();   // the OK / action button
            WindowFinder.sleep(driver.afterClickWaitMs());
            assertFalse(driver.isReportSearchDialogVisible(),
                    "'ReportSearch' dialog is still visible after clicking OK");
            takeShot(shots, main, "step_02_dialog_closed.png");

            // --- 3. Open the reports folder ---------------------------------
            NativeWindow folderEdit = folderEdit(main);
            NativeWindow searchEdit = searchEdit(main);
            assertNotNull(folderEdit, "Reports-folder edit box not found");
            assertNotNull(searchEdit, "Search edit box not found");

            main.foreground();
            folderEdit.clickFocus();
            WindowFinder.sleep(150);
            folderEdit.typeText(driver.reportsDir());
            WindowFinder.sleep(driver.stepWaitMs());
            assertTrue(folderEdit.editText().contains(driver.reportsDir()),
                    "Reports folder was not set, actual: " + folderEdit.editText());
            takeShot(shots, main, "step_03_reports_folder.png");

            // --- 4. Find the kassa report ------------------------------------
            NativeWindow searchBtn = main.childByText(driver.buttonSearch(), "Button");
            NativeWindow listBox = main.childByClass("LCLCheckListBox");
            assertNotNull(searchBtn, "Search button not found");
            assertNotNull(listBox, "Result list box not found");

            main.foreground();
            searchEdit.clickFocus();
            WindowFinder.sleep(150);
            searchEdit.typeText(driver.needle());
            WindowFinder.sleep(driver.stepWaitMs());

            // Retry the search a couple of times to survive startup flakiness.
            int count = 0;
            for (int attempt = 0; attempt < 3; attempt++) {
                searchBtn.click();
                WindowFinder.sleep(driver.searchWaitMs());
                count = listBox.listBoxCount();
                if (count >= 1) {
                    break;
                }
            }

            assertEquals(1, count, "Expected exactly one result, got " + count);
            String resultPath = listBox.listBoxItem(0);
            assertTrue(resultPath.toLowerCase().endsWith(driver.expectedFile().toLowerCase()),
                    "Unexpected result: " + resultPath);
            takeShot(shots, main, "step_04_found_kassa.png");

            // --- 5. Select the row and toggle its check box ------------------
            listBox.selectListBoxItem(0);
            WindowFinder.sleep(driver.stepWaitMs());
            listBox.clickRowCheckbox(0);   // physical click on the row check box
            WindowFinder.sleep(driver.stepWaitMs());
            takeShot(shots, main, "step_05_checked.png");

            // --- 6. Open it ('Open checked') ---------------------------------
            NativeWindow openChecked = main.childByText("Открыть отмеченные", "Button");
            assertNotNull(openChecked, "'Open checked' button not found");
            openChecked.click();
            WindowFinder.sleep(driver.afterClickWaitMs());

            NativeWindow viewer = driver.waitForViewer(FIND_TIMEOUT_MS);
            assertNotNull(viewer, "Viewer window did not open after 'Open checked'");
            assertTrue(viewer.windowText().contains(driver.expectedFile()),
                    "Viewer does not reference " + driver.expectedFile()
                            + ", actual: " + viewer.windowText());
            takeShot(shots, viewer, "step_06_opened_xml.png");

            // --- 7. Press 'Close' -> viewer (window 2) closes ----------------
            NativeWindow closeBtn = viewer.childByText(driver.closeBtnText(), "Button");
            closeBtn.click();
            WindowFinder.sleep(driver.afterClickWaitMs());
            assertFalse(driver.isViewerVisible(),
                    "Viewer window is still visible after 'Close'");
            assertTrue(driver.isMainVisible(), "Main window unexpectedly closed at step 7");
            takeShot(shots, main, "step_07_viewer_closed.png");

            // --- 8. Press the X (close) on the main window -------------------
            main.postClose();
            WindowFinder.sleep(driver.afterClickWaitMs());
            assertFalse(driver.isMainVisible(),
                    "Main window is still visible after closing (X)");
            boolean exited = driver.awaitExit(EXIT_TIMEOUT_MS);
            assertTrue(exited, "Application process did not exit after closing the last window");

        } finally {
            driver.terminate();
        }
    }

    // ---- control indexing helpers -----------------------------------------

    /**
     * The LCL main form has two Edit controls: a search box (empty by default)
     * and a folder box (pre-filled with the working directory, e.g. "D:\...").
     * We identify the folder box as the Edit holding a drive path, and the
     * search box as the empty one.
     */
    private NativeWindow folderEdit(NativeWindow win) {
        for (NativeWindow e : win.childrenByClass("Edit")) {
            String t = e.windowText();
            if (t.length() > 2 && t.charAt(1) == ':') {
                return e;
            }
        }
        return null;
    }

    private NativeWindow searchEdit(NativeWindow win) {
        for (NativeWindow e : win.childrenByClass("Edit")) {
            if (e.windowText().isEmpty()) {
                return e;
            }
        }
        return null;
    }

    private void takeShot(Path dir, NativeWindow w, String name) {
        try {
            java.io.File f = Screenshot.captureWindow(w, dir, name);
            System.out.println("[SHOT] " + f.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[WARN] screenshot " + name + " failed: " + e.getMessage());
        }
    }
}
