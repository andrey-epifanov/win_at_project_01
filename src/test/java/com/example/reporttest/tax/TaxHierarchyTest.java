package com.example.reporttest.tax;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import win.driver.By;
import win.driver.WinDriver;
import win.driver.WinProcess;
import win.driver.WinWindow;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the hierarchy introspection and text-search helpers on TaxForms.exe.
 *
 *   1. Dump the whole control tree of the main window (types + text) into the
 *      log via Lombok {@code Slf4j}.
 *   2. Search the hierarchy for a control by text ("Обзор...") and assert it
 *      is found.
 */
@Slf4j
class TaxHierarchyTest {

    private static final String EXE     = "D:\\workspace_delphi\\project_3\\TaxForms.exe";
    private static final String WORKDIR = "D:\\workspace_delphi\\project_3";
    private static final String MAIN    = "Программа просмотра налоговых форм 2.0";

    @Test
    @DisplayName("dump main window hierarchy and search a control by text")
    void dumpAndSearch() throws IOException {
        WinProcess app = WinDriver.launch(EXE, WORKDIR);
        try {
            WinWindow main = app.mainWindow(MAIN, 15000);
            assertNotNull(main, "main window not found");

            // --- 1. log the full hierarchy (types + text) ------------------
            String hierarchy = main.dumpHierarchy();
            log.info("=== WINDOW HIERARCHY: main [{}] ===", main.title());
            log.info("\n{}", hierarchy);
            assertFalse(hierarchy.isEmpty(), "hierarchy dump must not be empty");
            // sanity: the dump contains the known class names from the app
            assertTrue(hierarchy.contains("Button"), "dump should contain Button controls");
            assertTrue(hierarchy.contains("Edit"), "dump should contain Edit controls");

            // --- 2. search the whole tree by text --------------------------
            List<WinWindow> all = main.findAllByText("Обзор...");
            log.info("findAllByText('Обзор...') -> {} element(s)", all.size());
            assertFalse(all.isEmpty(), "expected to find 'Обзор...' in the hierarchy");
            WinWindow found = main.findByText("Обзор...");
            assertNotNull(found, "findByText failed to locate 'Обзор...'");
            assertEquals("Button", found.className(), "the 'Обзор...' control should be a Button");
            log.info("found 'Обзор...' as {}", found);

            // --- 3. substring search across the tree -------------------------
            List<WinWindow> contains = main.findAllByTextContains("Открыть");
            log.info("findAllByTextContains('Открыть') -> {} element(s)", contains.size());
            assertTrue(contains.stream().anyMatch(w -> w.text().equals("Открыть форму")),
                    "substring search should find 'Открыть форму'");

            log.info("=== TAX HIERARCHY TEST PASSED ===");
        } finally {
            app.terminate();
        }
    }
}