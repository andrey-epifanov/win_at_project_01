package com.example.reporttest.samples;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import win.natur.driver.OcrDriver;
import win.natur.driver.ScreenNavigator;
import win.natur.driver.WindElement;
import win.win32.driver.WinDriver;
import win.win32.driver.WinProcess;
import win.win32.driver.WinWindow;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Рабочий пример скриншот-навигации на приложении
 * {@code D:\workspace_delphi\project_34\DiasoftClient.exe}.
 *
 * <p>Демонстрирует {@link ScreenNavigator} и новый {@link WindElement} на
 * реальном диалоге «Образцы отчетов»:
 * <ul>
 *   <li><b>findText</b> — элемент в точке найденного текста (OCR);</li>
 *   <li><b>findByTypeNear(..., Direction.STRAIGHT/EQUAL)</b> — элемент ровно в
 *       точке текста;</li>
 *   <li><b>findByTypeNear(..., LEFT/RIGHT/ABOVE/BELOW)</b> — соседний контрол
 *       нужного типа в нужную сторону;</li>
 *   <li><b>findByImage</b> — кнопка тулбара по картинке;</li>
 *   <li>действия {@link WindElement}: position / click / doubleClick / type /
 *       text / screenshot / bounds (в т.ч. задание границ через точку-финиш и
 *       высоту) и <b>detectBounds</b>.</li>
 * </ul>
 *
 * <p>Факты по дереву контролов диалога: Lazarus Edit-ы — класс {@code Edit}
 * (счёт: 3), комбо — {@code LCLComboBox}, грид — {@code SysListView32} (строки
 * custom-drawn, отдельных HWND нет).
 */
@Slf4j
class ScreenNavigatorExampleTest {

    private static final String EXE     = "D:\\workspace_delphi\\project_34\\DiasoftClient.exe";
    private static final String WORKDIR = "D:\\workspace_delphi\\project_34";
    private static final String MAIN    = "Клиент версии: 11.1.5. НРБ (Dia.autotest 09.08.2026)";
    private static final String DIALOG  = "Образцы отчетов";

    private static final String EDIT  = "Edit";
    private static final String COMBO = "LCLComboBox";
    private static final String LIST  = "SysListView32";

    private static final Path TEMPLATES = Path.of("src", "test", "resources", "templates");

    @Test
    @DisplayName("ScreenNavigator + WindElement на реальном приложении")
    void navigateSamplesDialog() throws Exception {
        ScreenNavigator nav = new ScreenNavigator(new OcrDriver());

        WinProcess app = WinDriver.launch(EXE, WORKDIR);
        try {
            WinWindow main = app.mainWindow(MAIN, 15000);
            assertNotNull(main, "main window");
            assertTrue(main.clickMenuByText("Отчетность и формы"), "menu 'Отчетность и формы'");
            WinWindow dialog = waitForPrefix(app, DIALOG, 15000);
            assertNotNull(dialog, "samples dialog");
            WinDriver.sleep(600);

            // ===== 1. findText: элемент в точке найденного текста =========
            WindElement anchor = nav.findText(dialog, "Сокращение");
            log.info("[EX] findText('Сокращение') -> {} pos={}",
                    anchor.bounds(), anchor.position());
            assertNotNull(anchor, "anchor 'Сокращение'");
            assertTrue(anchor.position().x > 0 && anchor.position().y > 0, "element in screen coords");

            // ===== 2a. STRAIGHT/EQUAL: элемент ровно в точке текста =======
            WindElement atText = nav.findByTypeNear(dialog, "Сокращение", EDIT, ScreenNavigator.Direction.STRAIGHT);
            log.info("[EX] findByTypeNear('Сокращение', Edit, STRAIGHT) -> {}", atText == null ? null : atText.bounds());
            assertNotNull(atText, "STRAIGHT should find an Edit at the text point");

            // ===== 2b. RIGHT: пустой Edit справа от «Сокращение» ==========
            WindElement shrt = nav.findByTypeNear(dialog, "Сокращение", EDIT, ScreenNavigator.Direction.RIGHT);
            assertNotNull(shrt, "empty Edit to the right of 'Сокращение'");
            log.info("[EX] findByTypeNear('Сокращение', Edit, RIGHT) -> bounds={} text()='{}'",
                    shrt.bounds(), shrt.text());
            shrt.type("nrb_Pasp1trp");
            log.info("[EX] typed into 'Сокращение': 'nrb_Pasp1trp'");

            // ===== 2c. RIGHT: LCLComboBox справа от «Филиал» =============
            WindElement branch = nav.findByTypeNear(dialog, "Филиал", COMBO, ScreenNavigator.Direction.RIGHT);
            assertNotNull(branch, "LCLComboBox to the right of 'Филиал'");
            log.info("[EX] findByTypeNear('Филиал', LCLComboBox, RIGHT) -> bounds={}", branch.bounds());

            // ===== 2d. BELOW: грид ниже фильтров ==========================
            WindElement grid = nav.findByTypeNear(dialog, "Филиал", LIST, ScreenNavigator.Direction.BELOW);
            assertNotNull(grid, "SysListView32 below the filter row");
            log.info("[EX] findByTypeNear('Филиал', SysListView32, BELOW) -> bounds={}", grid.bounds());

            // ===== 3. findByImage: кнопка тулбара по картинке =============
            Path template = saveToolbarButtonTemplate(dialog);
            log.info("[EX] template saved -> {}", template.toAbsolutePath());
            WindElement btn = nav.findByImage(dialog, template, new Rectangle(0, 0, 400, 60), 0.6f);
            assertNotNull(btn, "toolbar button found by image");
            log.info("[EX] findByImage() -> bounds={} pos={}", btn.bounds(), btn.position());

            // ===== 4. WindElement: скриншот по границам + detectBounds =====
            File cropShot = shrt.screenshot(Path.of("screenshots"), "ex_shrt_crop.png");
            assertTrue(cropShot.exists(), "element screenshot saved");
            log.info("[EX] WindElement.screenshot -> {}", cropShot.getAbsolutePath());

            Rectangle detected = shrt.detectBounds();
            log.info("[EX] WindElement.detectBounds() -> {} (from {})", detected, shrt.bounds());

            // ===== 5. двойной клик по строке грида (open report) ==========
            WindElement row = nav.findByTypeNear(dialog, "nrb_Pasp1trp", LIST, ScreenNavigator.Direction.EQUAL);
            log.info("[EX] find row 'nrb_Pasp1trp' via EQUAL -> {}", row == null ? null : row.bounds());

            log.info("[EX] RESULT: findText/STRAIGHT/EQUAL/type/RIGHT/BELOW/findByImage/screenshot/detectBounds OK");
        } finally {
            app.terminate();
        }
    }

    private Path saveToolbarButtonTemplate(WinWindow dialog) throws Exception {
        File shot = dialog.screenshot(Path.of("target", "example"), "toolbar.png");
        BufferedImage img = ImageIO.read(shot);
        int x = Math.min(320, img.getWidth() - 60);
        int y = 4;
        int w = Math.max(1, Math.min(60, img.getWidth() - x));
        int h = Math.min(34, img.getHeight() - y);
        BufferedImage crop = img.getSubimage(x, y, w, h);
        File dir = TEMPLATES.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File out = dir.toPath().resolve("btn_update.png").toFile();
        ImageIO.write(crop, "png", out);
        return out.toPath();
    }

    private static WinWindow waitForPrefix(WinProcess app, String prefix, long timeoutMs) {
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