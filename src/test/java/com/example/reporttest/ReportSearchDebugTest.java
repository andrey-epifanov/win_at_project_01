package com.example.reporttest;

import com.example.reporttest.driver.NativeWindow;
import com.example.reporttest.driver.ReportSearchDriver;
import com.example.reporttest.driver.WindowFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug-only harness for exercising the core Win32 driver pieces one at a time
 * so each can be stepped through in the IDE debugger:
 *   1. find a Win32 top-level window (search by PID + title)
 *   2. find a child element inside that window (by class / by text)
 *   3. type data into an Edit control
 *
 * Unlike ReportSearchScenarioTest this does NOT run the full 5-step scenario.
 * Every method launches the app fresh, does exactly one thing, prints debug
 * diagnostics, then terminates the process.  All addressing goes through the
 * existing JNA driver (NativeWindow / WindowFinder / ReportSearchDriver).
 */
@SpringBootTest
class ReportSearchDebugTest {

    @Autowired
    private ReportSearchDriver driver;

    private static final long FIND_TIMEOUT_MS = 10000;

    // ---- 1. Find a Windows top-level window --------------------------------

    @Test
    @DisplayName("debug: find the main window (Win32 top-level by PID + title)")
    void findMainWindow() throws IOException {
        driver.launch();
        try {
            long pid = driver.pid();
            System.out.println("[DBG] launched pid=" + pid + " title='" + driver.mainTitle() + "'");

            List<NativeWindow> tops = WindowFinder.topWindows();
            System.out.println("[DBG] top-level windows visible to EnumWindows: " + tops.size());

            // this is WindowFinder.findTitle(pid, title) honoring isVisible()
            NativeWindow main = driver.waitForMain(FIND_TIMEOUT_MS);
            System.out.println("[DBG] main=" + main);

            assertNotNull(main, "main window not found: " + driver.mainTitle());
            assertTrue(main.isVisible(), "main window not visible");

            // addressing check: the found HWND really belongs to our process
            long ownerPid = WindowFinder.processId(main);
            System.out.println("[DBG] owner pid=" + ownerPid + " handle=" + main.handle()
                    + " className='" + main.className() + "'");
            assertEquals(pid & 0xFFFFFFFFL, ownerPid, "window belongs to a different pid");
        } finally {
            driver.terminate();
        }
    }

    // ---- 2. Find a child element inside the window -------------------------

    @Test
    @DisplayName("debug: find child elements inside the main window")
    void findChildElements() throws IOException {
        driver.launch();
        try {
            NativeWindow main = driver.waitForMain(FIND_TIMEOUT_MS);
            assertNotNull(main, "main window not found");

            // dump every child control (class + text) so you can see the layout
            List<NativeWindow> children = main.children();
            System.out.println("[DBG] total children = " + children.size());
            for (int i = 0; i < children.size(); i++) {
                NativeWindow c = children.get(i);
                System.out.println("[DBG]   child#" + i
                        + " class='" + c.className()
                        + "' text='" + c.windowText()
                        + "' visible=" + c.isVisible());
            }

            // folder edit: Edit whose text looks like a drive path "D:..."
            NativeWindow folderEdit = findFolderEdit(main);
            assertNotNull(folderEdit, "folder edit not found");
            System.out.println("[DBG] folderEdit=" + folderEdit
                    + " text='" + folderEdit.windowText() + "'");

            // search edit: the (empty) Edit
            NativeWindow searchEdit = findSearchEdit(main);
            assertNotNull(searchEdit, "search edit not found");
            System.out.println("[DBG] searchEdit=" + searchEdit
                    + " text='" + searchEdit.windowText() + "'");

            // buttons by exact caption
            NativeWindow searchBtn = main.childByText(driver.buttonSearch(), "Button");
            System.out.println("[DBG] searchBtn=" + searchBtn + " text='" + searchBtn.windowText() + "'");

            // list box + status bar
            int lbs = main.childrenByClass("LCLListBox").size();
            int sbs = main.childrenByClass("msctls_statusbar32").size();
            System.out.println("[DBG] #LCLListBox=" + lbs + " #statusbar=" + sbs);
        } finally {
            driver.terminate();
        }
    }

    // ---- 3. Type data into an Edit control ---------------------------------

    @Test
    @DisplayName("debug: type data into the folder Edit control")
    void typeIntoEdit() throws IOException {
        driver.launch();
        try {
            NativeWindow main = driver.waitForMain(FIND_TIMEOUT_MS);
            assertNotNull(main, "main window not found");
            NativeWindow folderEdit = findFolderEdit(main);
            assertNotNull(folderEdit, "folder edit not found");

            String before = folderEdit.editText();
            System.out.println("[DBG] editText before typing = '" + before + "'");

            main.foreground();
            folderEdit.clickFocus();
            WindowFinder.sleep(150);
            folderEdit.typeText(driver.reportsDir());
            WindowFinder.sleep(driver.stepWaitMs());

            String after = folderEdit.editText();
            System.out.println("[DBG] editText after typing  = '" + after + "'");
            assertTrue(after.contains(driver.reportsDir()),
                    "folder edit was not set, actual='" + after + "'");
        } finally {
            driver.terminate();
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Identify the reports-folder Edit as the Edit whose text looks like a
     * drive path (e.g. "D:\...").  This is robust and independent of child
     * ordering, unlike found_index.
     */
    private NativeWindow findFolderEdit(NativeWindow win) {
        for (NativeWindow e : win.childrenByClass("Edit")) {
            String t = e.windowText();
            if (t.length() > 2 && t.charAt(1) == ':') {
                return e;
            }
        }
        return null;
    }

    /**
     * Identify the search Edit as the (initially empty) Edit control.
     */
    private NativeWindow findSearchEdit(NativeWindow win) {
        for (NativeWindow e : win.childrenByClass("Edit")) {
            if (e.windowText().isEmpty()) {
                return e;
            }
        }
        return null;
    }
}
