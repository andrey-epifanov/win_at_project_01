package com.example.reporttest.driver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Drives the ReportSearch.exe (Lazarus/LCL) application through the tested
 * scenario using native Win32 calls via JNA.  Configuration comes from
 * application.properties (@Value).
 *
 * Controls of the main window (LCL):
 *   - Edit[1] : reports folder
 *   - Edit[0] : search needle
 *   - Button <search>        : run search
 *   - Button <open XML>      : open the selected result
 *   - LCLListBox             : result list
 *   - msctls_statusbar32     : status bar (read "XML: N ..." summary)
 * Controls of the viewer window:
 *   - Button <close>         : close the viewer
 *
 * Cyrillic property values are stored as unicode escape tokens inside
 * application.properties (Spring does NOT decode them), so they are decoded
 * here at construction time with UnicodeEscapes.
 */
@Component
public class ReportSearchDriver {

    private final String exePath;
    private final String workDir;
    private final String reportsDir;
    private final String mainTitle;
    private final String needle;
    private final String expectedFile;
    private final String viewerPrefix;
    private final String searchBtnText;
    private final String openBtnText;
    private final String closeBtnText;
    private final long stepWaitMs;
    private final long searchWaitMs;
    private final long afterClickWaitMs;
    private final long launchWaitMs;
    private final String screenshotDir;
    private final String reportSearchDialogTitle;
    private final int fileOpenMenuId;

    private Process process;
    private long pid = -1L;

    public ReportSearchDriver(
            @Value("${app.exe.path}") String exePath,
            @Value("${app.exe.workdir}") String workDir,
            @Value("${app.reports.dir}") String reportsDir,
            @Value("${app.main.title}") String mainTitle,
            @Value("${app.search.needle}") String needle,
            @Value("${app.report.expected.file}") String expectedFile,
            @Value("${app.viewer.title.prefix}") String viewerPrefix,
            @Value("${app.button.search}") String searchBtnText,
            @Value("${app.button.open}") String openBtnText,
            @Value("${app.button.close}") String closeBtnText,
            @Value("${app.wait.step.ms}") long stepWaitMs,
            @Value("${app.wait.search.ms}") long searchWaitMs,
            @Value("${app.wait.after.click.ms}") long afterClickWaitMs,
            @Value("${app.launch.wait.ms}") long launchWaitMs,
            @Value("${app.screenshot.dir}") String screenshotDir,
            @Value("${app.reportsearch.dialog.title}") String reportSearchDialogTitle,
            @Value("${app.menu.open.command.id}") int fileOpenMenuId) {
        this.exePath = exePath;
        this.workDir = workDir;
        this.reportsDir = reportsDir;
        this.mainTitle = UnicodeEscapes.decode(mainTitle);
        this.needle = UnicodeEscapes.decode(needle);
        this.expectedFile = expectedFile;
        this.viewerPrefix = UnicodeEscapes.decode(viewerPrefix);
        this.searchBtnText = UnicodeEscapes.decode(searchBtnText);
        this.openBtnText = UnicodeEscapes.decode(openBtnText);
        this.closeBtnText = UnicodeEscapes.decode(closeBtnText);
        this.stepWaitMs = stepWaitMs;
        this.searchWaitMs = searchWaitMs;
        this.afterClickWaitMs = afterClickWaitMs;
        this.launchWaitMs = launchWaitMs;
        this.screenshotDir = screenshotDir;
        this.reportSearchDialogTitle = UnicodeEscapes.decode(reportSearchDialogTitle);
        this.fileOpenMenuId = fileOpenMenuId;
    }

    public void launch() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exePath);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);
        process = pb.start();
        pid = process.pid();
        WindowFinder.sleep(launchWaitMs);
    }

    public long pid() {
        return pid;
    }

    public String mainTitle() {
        return mainTitle;
    }

    public String reportsDir() {
        return reportsDir;
    }

    public String needle() {
        return needle;
    }

    public String expectedFile() {
        return expectedFile;
    }

    public String viewerPrefix() {
        return viewerPrefix;
    }

    public String buttonSearch() {
        return searchBtnText;
    }

    public String buttonOpen() {
        return openBtnText;
    }

    public String closeBtnText() {
        return closeBtnText;
    }

    public long stepWaitMs() {
        return stepWaitMs;
    }

    public long afterClickWaitMs() {
        return afterClickWaitMs;
    }

    public long searchWaitMs() {
        return searchWaitMs;
    }

    public Path screenshotDir() {
        return Paths.get(screenshotDir);
    }

    public String reportSearchDialogTitle() {
        return reportSearchDialogTitle;
    }

    public int fileOpenMenuId() {
        return fileOpenMenuId;
    }

    /** Wait (until timeoutMs) for the visible modal 'ReportSearch' dialog (File->Open). */
    public NativeWindow waitForReportSearchDialog(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            NativeWindow w = WindowFinder.findDialog(pid, reportSearchDialogTitle);
            if (w != null) {
                return w;
            }
            WindowFinder.sleep(200);
        }
        return null;
    }

    /** True if the ReportSearch dialog is currently visible. */
    public boolean isReportSearchDialogVisible() {
        return WindowFinder.findDialog(pid, reportSearchDialogTitle) != null;
    }

    public NativeWindow waitForMain(long timeoutMs) {
        return WindowFinder.waitForTitle(pid, mainTitle, timeoutMs);
    }

    public NativeWindow waitForViewer(long timeoutMs) {
        return WindowFinder.waitForTitlePrefix(pid, viewerPrefix, timeoutMs);
    }

    public boolean isViewerVisible() {
        return WindowFinder.isTitlePrefixVisible(pid, viewerPrefix);
    }

    public boolean isMainVisible() {
        return WindowFinder.isTitleVisible(pid, mainTitle);
    }

    /** True as long as the launched process is still alive. */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Wait (until timeoutMs) for the launched process to exit. */
    public boolean awaitExit(long timeoutMs) {
        if (process == null) {
            return true;
        }
        try {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    public void terminate() {
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}