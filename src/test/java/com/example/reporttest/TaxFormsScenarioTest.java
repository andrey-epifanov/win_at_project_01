package com.example.reporttest;

import win.driver.By;
import win.driver.WinDriver;
import win.driver.WinProcess;
import win.driver.WinWindow;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end UI automation for TaxForms.exe ("Программа просмотра налоговых форм 2.0").
 *
 * This test drives the app purely through the generic win.driver toolkit.  All
 * values are inline literals (no Spring / application.properties), so the test
 * is self-contained and does not touch the shared config file.
 *
 * Scenario:
 *   0. Launch TaxForms.exe (arguably with the built-in forms folder).
 *   1. Main window appears with the correct title and a menu bar
 *      (Файл / Редактирование / Помощь).
 *   2. Address child controls: search edit, buttons "Найти", "Обзор...",
 *      "Открыть форму", and the reports ListView.
 *   3. Click "Открыть форму" -> a form window opens (title prefix
 *      "Налоговая форма").
 *   4. Form window has buttons "Сохранить", "Помощь", "Закрыть" and two radio
 *      buttons "XML" / "Удобно для человека".
 *   5. Both radios are real radio-button controls; toggle the second one and
 *      verify it becomes checked.
 *   6. Press "Закрыть" -> the form window closes.
 *   7. Close the main window (X) -> the process exits.
 *
 * Screenshots are written to screenshots/taxforms_*.png.
 */
@Slf4j
class TaxFormsScenarioTest {

    // ---- app under test (inline) -----------------------------------------
    private static final String EXE      = "D:\\workspace_delphi\\project_3\\TaxForms.exe";
    private static final String WORKDIR  = "D:\\workspace_delphi\\project_3";
    private static final String MAIN    = "Программа просмотра налоговых форм 2.0";
    private static final String PREFIX  = "Налоговая форма";

    // ---- timeouts ---------------------------------------------------------
    private static final long FIND_TIMEOUT_MS = 15000;
    private static final long EXIT_TIMEOUT_MS = 8000;
    private static final long STEP_WAIT_MS    = 600;

    @org.junit.jupiter.api.Test
    void driveTaxForms() throws IOException {
        WinProcess app = WinDriver.launch(EXE, WORKDIR);
        WinWindow main = null;
        try {
            // --- 0/1. main window + menu bar ------------------------------
            main = app.mainWindow(MAIN, FIND_TIMEOUT_MS);
            assertNotNull(main, "Main window not found: " + MAIN);
            assertTrue(main.isVisible(), "Main window not visible");
            assertTrue(main.hasMenu(), "Main window should have a menu bar");

            List<String> items = main.menuItems();
            log.info("[TAX] menu items = {}", items);
            assertTrue(items.stream().anyMatch(s -> s.startsWith("Файл")), "menu has File");
            assertTrue(items.stream().anyMatch(s -> s.startsWith("Редактирование")), "menu has Edit");
            assertTrue(items.stream().anyMatch(s -> s.startsWith("Помощь")), "menu has Help");

            // --- 2. child controls ----------------------------------------
            int editCount = main.children(By.className("Edit")).size();
            int buttonCount = main.children(By.className("Button")).size();
            log.info("[TAX] main child controls: edits={} buttons={}", editCount, buttonCount);
            assertTrue(editCount > 0, "no Edit found in main");
            assertTrue(buttonCount > 0, "no Button found in main");

            WinWindow openBtn = main.child(By.text("Открыть форму"));
            log.info("[TAX] open button = {}", openBtn);
            // The ListView (TListView) is present; we do not enumerate its rows
            // (not a list box), but the app auto-selects row 0 when empty.
            main.child(By.text("Найти"));
            main.child(By.text("Обзор..."));

            // --- 3. click "Открыть форму" -> form window opens -------------
            main.goToThisWindow();
            openBtn.click();
            WinWindow form = waitForPrefix(app, PREFIX, FIND_TIMEOUT_MS);
            assertNotNull(form, "Form window did not open after 'Открыть форму'");
            log.info("[TAX] form window = {}", form);
            assertTrue(form.text().startsWith(PREFIX),
                    "Unexpected form window title: " + form.text());

            // --- 4. form window buttons -----------------------------------
            form.child(By.text("Сохранить"));
            form.child(By.text("Помощь"));
            WinWindow closeBtn = form.child(By.text("Закрыть"));

            // Reread buttons each time the form is queried.
            List<WinWindow> formButtons = form.children(By.className("Button"));
            log.info("[TAX] form button count = {}", formButtons.size());

            // --- 5. radio buttons ------------------------------------------
            List<WinWindow> radios = form.children(By.className("Button")).stream()
                    .filter(WinWindow::isRadio)
                    .toList();
            log.info("[TAX] radios = {}", radios.size());
            assertEquals(2, radios.size(), "Expected 2 radio buttons");

            WinWindow rdoXml = radios.stream()
                    .filter(w -> w.text().equals("XML")).findFirst().orElse(null);
            WinWindow rdoHuman = radios.stream()
                    .filter(w -> w.text().equals("Удобно для человека")).findFirst().orElse(null);
            assertNotNull(rdoXml, "radio 'XML' not found");
            assertNotNull(rdoHuman, "radio 'Удобно для человека' not found");
            assertTrue(rdoXml.isRadio(), "'XML' should be a radio button");
            assertTrue(rdoHuman.isRadio(), "'Удобно для человека' should be a radio button");

            // Default is XML checked; switch to human-friendly and verify.
            log.info("[TAX] xml checked={} human checked={}", rdoXml.isChecked(), rdoHuman.isChecked());
            rdoHuman.setChecked(true);
            assertTrue(rdoHuman.isChecked(), "human-friendly radio should be checked");
            assertFalse(rdoXml.isChecked(), "XML radio should no longer be checked");

            form.screenshot(Path.of("screenshots"), "taxforms_01_form.png");

            // --- 6. close the form window ----------------------------------
            closeBtn.click();
            assertNull(waitForPrefix(app, PREFIX, 3000),
                    "Form window still visible after 'Закрыть'");

            // --- 7. close main window (X) -> app exits ----------------------
            main.close();
            boolean exited = app.waitForExit(EXIT_TIMEOUT_MS);
            assertTrue(exited, "Application did not exit after closing the main window");

            log.info("[TAX] RESULT: ALL STEPS PASSED");
        } finally {
            if (app != null) {
                app.terminate();
            }
        }
    }

    /** Poll for a top-level window owned by {@code app} whose title starts with {@code prefix}. */
    private WinWindow waitForPrefix(WinProcess app, String prefix, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            WinWindow w = app.windowByPidAndTitlePrefix(prefix);
            if (w != null && w.isVisible()) {
                return w;
            }
            WinDriver.sleep(150);
        }
        return null;
    }
}