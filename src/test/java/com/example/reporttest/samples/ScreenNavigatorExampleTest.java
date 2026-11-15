package com.example.reporttest.samples;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import win.win32.driver.WinDriver;
import win.win32.driver.WinProcess;
import win.win32.driver.WinWindow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Рабочий пример скриншот-навигации на приложении
 * {@code D:\workspace_delphi\project_34\DiasoftClient.exe}.
 *
 * <p>Демонстрирует все три возможности {@link ScreenNavigator} на реальном
 * диалоге «Образцы отчетов»:
 * <ul>
 *   <li><b>findText</b> — находим текст на скриншоте (OCR);</li>
 *   <li><b>findByTypeNear</b> — от найденного текста ищем контрол нужного
 *       типа в нужную сторону (4 стороны). Примеры: пустое поле «Сокращение»
 *       — это Edit СПРАВА от метки; комбо «Филиал» — LCLComboBox СПРАВА от
 *       метки; грид — SysListView32 НИЖЕ фильтров;</li>
 *   <li><b>findByImage</b> — находим кнопку тулбара «Обновить» по её картинке
 *       (кнопки тулбара custom-drawn, у них нет своего HWND и текста, поэтому
 *       только по шаблону).</li>
 * </ul>
 *
 * <p>Важные факты, выясненные по дереву контролов диалога:
 * в Lazarus Edit-ы имеют класс {@code Edit}, комбо — {@code LCLComboBox}, грид —
 * {@code SysListView32} (строки которого custom-drawn и не являются отдельными
 * HWND — потому их и ищем либо по OCR-тексту, либо по контейнеру).
 */
@Slf4j
class ScreenNavigatorExampleTest {

    // ---- app under test ---------------------------------------------------
    private static final String EXE     = "D:\\workspace_delphi\\project_34\\DiasoftClient.exe";
    private static final String WORKDIR = "D:\\workspace_delphi\\project_34";
    private static final String MAIN    = "Клиент версии: 11.1.5. НРБ (Dia.autotest 09.08.2026)";
    private static final String DIALOG  = "Образцы отчетов";

    // ---- control classes (Lazarus / Win32) -------------------------------
    private static final String EDIT   = "Edit";
    private static final String COMBO  = "LCLComboBox";
    private static final String LIST   = "SysListView32";

    private static final Path TEMPLATES = Path.of("src", "test", "resources", "templates");

    @Test
    @DisplayName("ScreenNavigator: findText + findByTypeNear + findByImage на реальном приложении")
    void navigateSamplesDialog() throws Exception {
        ScreenNavigator nav = new ScreenNavigator(new OcrDriver());

        WinProcess app = WinDriver.launch(EXE, WORKDIR);
        try {
            // --- open main window -----------------------------------------
            WinWindow main = app.mainWindow(MAIN, 15000);
            assertNotNull(main, "main window");
            assertTrue(main.clickMenuByText("Отчетность и формы"), "menu 'Отчетность и формы'");
            WinWindow dialog = waitForPrefix(app, DIALOG, 15000);
            assertNotNull(dialog, "samples dialog");
            WinDriver.sleep(600);

            // ===== 1. findText: где на экране «Сокращение» ================
            ScreenNavigator.Point anchor = nav.findText(dialog, "Сокращение");
            log.info("[EX] findText('Сокращение') -> {}", anchor);
            assertNotNull(anchor, "anchor text 'Сокращение'");
            assertTrue(anchor.screenX > 0 && anchor.screenY > 0, "anchor in screen coords");

            // ===== 2a. findByTypeNear: пустой Edit СПРАВА от «Сокращение» ==
            WinWindow shrt = nav.findByTypeNear(dialog, "Сокращение", EDIT, ScreenNavigator.Direction.RIGHT);
            assertNotNull(shrt, "empty Edit to the right of 'Сокращение'");
            assertTrue(shrt.text().isEmpty(), "expected empty Edit (edShrt), got '" + shrt.text() + "'");
            log.info("[EX] findByTypeNear('Сокращение', Edit, RIGHT) -> class={} text='{}'", shrt.className(), shrt.text());
            shrt.fill("nrb_Pasp1trp");
            log.info("[EX] filled 'Сокращение' with 'nrb_Pasp1trp'");

            // ===== 2b. findByTypeNear: LCLComboBox СПРАВА от «Филиал» =====
            WinWindow branch = nav.findByTypeNear(dialog, "Филиал", COMBO, ScreenNavigator.Direction.RIGHT);
            assertNotNull(branch, "LCLComboBox to the right of 'Филиал'");
            log.info("[EX] findByTypeNear('Филиал', LCLComboBox, RIGHT) -> class={} count={}",
                    branch.className(), branch.comboItemCount());

            // ===== 2c. findByTypeNear: грид НИЖЕ фильтров =================
            WinWindow grid = nav.findByTypeNear(dialog, "Филиал", LIST, ScreenNavigator.Direction.BELOW);
            assertNotNull(grid, "SysListView32 below the filter row");
            log.info("[EX] findByTypeNear('Филиал', SysListView32, BELOW) -> class={}", grid.className());

            // ===== 3. findByImage: кнопка тулбара по картинке =============
            // Кнопки тулбара кастомные (нет HWND/текста) — находим по шаблону.
            // Шаблон берём из живого скриншота: обрезаем область кнопки
            // «Обновить» и сохраняем в ресурсы, как это делают вручную.
            Path template = saveToolbarButtonTemplate(dialog);
            log.info("[EX] template saved -> {}", template.toAbsolutePath());

            ScreenNavigator.Match imgMatch = nav.findByImage(
                    dialog, template, new java.awt.Rectangle(0, 0, 400, 60), 0.6f);
            assertNotNull(imgMatch, "toolbar button found by image");
            log.info("[EX] findByImage(btn_update.png, region=top toolbar) -> {}", imgMatch);
            assertTrue(imgMatch.score > 0.6f, "image score should exceed threshold, got " + imgMatch.score);

            log.info("[EX] RESULT: findText + findByTypeNear(Edit/LCLComboBox/SysListView32) + findByImage OK");
        } finally {
            app.terminate();
        }
    }

    /**
     * Снимает диалог, обрезает область кнопки тулбара «Обновить» (левый верх
     * окна) и сохраняет как PNG-шаблон в test resources.  Возвращает путь.
     */
    private Path saveToolbarButtonTemplate(WinWindow dialog) throws Exception {
        File shot = dialog.screenshot(Path.of("target", "example"), "toolbar.png");
        BufferedImage img = ImageIO.read(shot);
        // Кнопка «Обновить» — последняя (~7-я) на тулбаре, идёт слева направо.
        // Обрезаем правую часть полосы тулбара, где находится «Обновить».
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