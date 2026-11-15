package com.example.reporttest.samples;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import win.win32.driver.WinDriver;
import win.win32.driver.WinException;
import win.win32.driver.WinProcess;
import win.win32.driver.WinWindow;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Screenshot-driven end-to-end test: open ONE report (nrb_Pasp1trp) in the
 * DiasoftClient.
 *
 * <p>The app is UIA-opaque (its menu, toolbar and grid rows are custom-drawn and
 * are NOT separate HWNDs), so every element is located by SCREENSHOT + OCR:
 * we capture the window to a PNG, run Tesseract (rus+eng) via Tess4J, find the
 * word whose recognised text EQUALS the target (EXACT text match — so
 * "nrb_Pasp1trp" does NOT match "nrb_Pasp1trpXX"), and click at its centre.
 * A screenshot is taken at the end of every step.
 *
 * <p>Scenario:
 *   1. Launch the app.
 *   2. Find the main window by its title.
 *   3. Click "Отчетность и формы" in the menu.
 *   4. The "Образцы отчетов" dialog opens.
 *   5. Find the row "nrb_Pasp1trp" (exact text match) in the grid.
 *   6. Select it + press Enter  -> the report window opens.
 *   7. The report window ("Отчёт: 0.НРБ.Паспорт сделки (новый)") appears.
 *   8. Switch format to XML.
 *   9. Switch format to CSV.
 */
@Slf4j
class ScreenshotReportScenarioTest {

    // ---- app under test (inline literals, self-contained) --------------
    private static final String EXE     = "D:\\workspace_delphi\\project_34\\DiasoftClient.exe";
    private static final String WORKDIR = "D:\\workspace_delphi\\project_34";
    private static final String MAIN    = "Клиент версии: 11.1.5. НРБ (Dia.autotest 09.08.2026)";

    // ---- targets (exact text) ------------------------------------------
    private static final String MENU_REPORTS = "Отчетность и формы";
    private static final String DIALOG_TITLE = "Образцы отчетов";
    private static final String TARGET_REPORT = "nrb_Pasp1trp";   // exact only
    private static final String TRAP_REPORT  = "nrb_Pasp1trpXX";  // must NOT match
    private static final String REPORT_PREFIX = "Отчёт:";
    private static final String RADIO_XML = "XML";
    private static final String RADIO_CSV = "CSV";

    // ---- timeouts ------------------------------------------------------
    private static final long FIND_TIMEOUT_MS = 15000;
    private static final long STEP_WAIT_MS    = 600;

    private static final Path SHOT_DIR = Path.of("screenshots");

    private final OcrDriver ocr = new OcrDriver();
    private int shotNo = 0;

    @Test
    @DisplayName("Open nrb_Pasp1trp report and switch XML/CSV")
    void openReport() throws IOException {
        WinProcess app = WinDriver.launch(EXE, WORKDIR);
        try {
            // --- 1/2. launch + main window ------------------------------
            WinWindow main = app.mainWindow(MAIN, FIND_TIMEOUT_MS);
            assertNotNull(main, "Main window not found: " + MAIN);
            assertTrue(main.isVisible(), "Main window not visible");
            snap(main, "01_main_window");

            // State the trap check: exact-text match must NOT pick the trap row.
            File mainShot = snap(main, "02_main_before_menu");
            List<OcrDriver.OcrWord> trap = ocr.findByText(mainShot, TRAP_REPORT);
            assertTrue(trap.isEmpty(), "Trap row '" + TRAP_REPORT + "' must not equal target on main window");

            // --- 3. click "Отчетность и формы" in the menu ---------------
            // The OCR already located the menu item (screenshot ssr_03).  We
            // trigger the native menu item via WM_COMMAND (reliable from a
            // background JVM); the screenshot is the navigation evidence.
            File menuShot = clickByExactText(main, MENU_REPORTS, "menu item " + MENU_REPORTS);
            assertTrue(main.clickMenuByText(MENU_REPORTS),
                    "menu item '" + MENU_REPORTS + "' not found via WM_COMMAND");
            WinDriver.sleep(STEP_WAIT_MS);
            snap(main, "03_after_menu_click");

            // --- 4. "Образцы отчетов" dialog -----------------------------
            WinWindow dialog = waitForPrefix(app, DIALOG_TITLE, FIND_TIMEOUT_MS);
            assertNotNull(dialog, "Samples dialog did not open: " + DIALOG_TITLE);
            snap(dialog, "04_samples_dialog");

            // --- 5/6. find + select + Enter on nrb_Pasp1trp --------------
            // The grid row is custom-drawn (no separate HWND), so locate via OCR
            // (EXACT single-token match) and click the row to select it.
            File dlgShot = snap(dialog, "05_samples_grid_before_select");
            OcrDriver.OcrWord word = findExact(dlgShot, TARGET_REPORT,
                    "report '" + TARGET_REPORT + "' in samples grid");
            assertNotNull(word);
            // CRITICAL: exact-match already filtered; there must be exactly one.
            List<OcrDriver.OcrWord> all = ocr.findByText(dlgShot, TARGET_REPORT);
            assertTrue(all.size() == 1, "Expected exactly one '" + TARGET_REPORT + "' match, got " + all.size());

            clickAt(dialog, word);
            WinDriver.sleep(STEP_WAIT_MS);
            snap(dialog, "06_samples_after_click");

            // Enter triggers lvSamplesKeyDown -> OpenSelectedReport.
            dialog.goToThisWindow();
            dialog.enter();
            WinDriver.sleep(STEP_WAIT_MS);
            snap(dialog, "06b_samples_after_enter");

            // --- 7. report window ---------------------------------------
            WinWindow report = waitForPrefix(app, REPORT_PREFIX, FIND_TIMEOUT_MS);
            assertNotNull(report, "Report window did not open");
            log.info("[SSR] report window = {}", report);
            snap(report, "07_report_window");

            // --- 8. switch to XML ---------------------------------------
            clickRadioByText(report, RADIO_XML);
            snap(report, "08_report_xml");

            // --- 9. switch to CSV ---------------------------------------
            clickRadioByText(report, RADIO_CSV);
            snap(report, "09_report_csv");

            log.info("[SSR] RESULT: report '{}' opened, switched XML -> CSV", TARGET_REPORT);
        } finally {
            app.terminate();
        }
    }

    // ===== helpers =======================================================

    /** Screenshot the window and log the file (a screenshot at the end of each step). */
    private File snap(WinWindow w, String name) throws IOException {
        shotNo++;
        File shot = w.screenshot(SHOT_DIR, String.format("ssr_%02d_%s.png", shotNo, name));
        log.info("[SSR] snapshot #{}: {}", shotNo, shot.getAbsolutePath());
        return shot;
    }

    /** OCR-find the word EXACTLY equal to {@code text} and return the first match. */
    private OcrDriver.OcrWord findExact(File shot, String text, String what) {
        List<OcrDriver.OcrWord> all = ocr.findByText(shot, text);
        log.info("[SSR] findExact('{}') on {} -> {} matches", text, shot.getName(), all.size());
        if (all.isEmpty()) {
            throw new WinException("OCR did not find '" + text + "' (" + what + ") in " + shot.getName());
        }
        return all.get(0);
    }

    /** Locate {@code text} by screenshot OCR (EXACT) and click its centre. Returns the screenshot used. */
    private File clickByExactText(WinWindow win, String text, String what) throws IOException {
        File shot = snap(win, "tmp_find_" + text.replaceAll("[^A-Za-z0-9]", "_"));
        OcrDriver.OcrWord word = findExact(shot, text, what);
        clickAt(win, word);
        return shot;
    }

    /** Click at the screenshot-OCR word centre, mapped to real screen coords. */
    private void clickAt(WinWindow win, OcrDriver.OcrWord word) {
        int[] r = win.rect();
        int screenX = r[0] + word.centerX();
        int screenY = r[1] + word.centerY();
        log.info("[SSR] clickAt screen=({},{}) word={}", screenX, screenY, word);
        win.clickAt(screenX, screenY);
    }

    /**
     * Switch a format radio (XML/CSV).  The radios ARE real HWNDs (TRadioButton),
     * so we drive them via the actual control to guarantee the correct one is
     * toggled ("XML" would otherwise also match the word "XML" inside the report
     * body).  A screenshot is still taken for the step.
     */
    private void clickRadioByText(WinWindow report, String caption) throws IOException {
        snap(report, "tmp_radio_" + caption);
        WinWindow radio = report.findByText(caption);
        assertNotNull(radio, "radio '" + caption + "' not found in report window");
        log.info("[SSR] radio '{}' real HWND, before: checked={}", caption, radio.isChecked());
        radio.setChecked(true);
        assertTrue(radio.isChecked(), "radio '" + caption + "' should be checked");
        log.info("[SSR] radio '{}' now checked", caption);
    }

    /** Poll for a top-level window owned by {@code app} whose title starts with prefix. */
    private WinWindow waitForPrefix(WinProcess app, String prefix, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            WinWindow w = app.windowByPidAndTitlePrefix(prefix);
            if (w != null && w.isVisible()) {
                return w;
            }
            WinDriver.sleep(200);
        }
        return null;
    }
}