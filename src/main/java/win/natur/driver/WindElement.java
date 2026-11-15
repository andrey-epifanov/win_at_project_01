package win.natur.driver;

import lombok.extern.slf4j.Slf4j;
import win.win32.driver.WinWindow;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WindElement — результат скриншот-навигации: координаты элемента и базовые
 * действия с ним.
 *
 * <p>Координаты хранятся в «клиентских» пикселях окна (то есть в системе
 * координат скриншота), но вся работа с мышью и скриншотами идёт в экранных
 * координатах (к ним мы прибавляем top-left видимого окна из
 * {@link WinWindow#rect()}).
 *
 * <p>Действия:
 * <ul>
 *   <li>{@link #position()} — экранные координаты верхнего левого угла
 *       (по аналогии с Playwright {@code options.position});</li>
 *   <li>{@link #click()} / {@link #doubleClick()} — клик и двойной клик по
 *       центру элемента (реальным курсором);</li>
 *   <li>{@link #type(String)} — ввод текста в элемент (для Edit — через
 *       WM_CHAR, что надёжнее в фоновом JVM);</li>
 *   <li>{@link #text()} — OCR текста из обрезанного скриншота границ;</li>
 *   <li>{@link #screenshot(Path, String)} — сохранить скриншот элементa
 *       в заданных границах;</li>
 *   <li>{@link #bounds()} / {@link #withBounds(FinishPoint, int, int)} /
 *       {@link #detectBounds()} — работа с границами (в т.ч. задание через
 *       Finish + Высоту).</li>
 * </ul>
 *
 * <p>Границы (bounds) — это прямоугольник в клиентских пикселях окна. Их можно
 * задать целиком ({@link #withBounds(Rectangle)}) или через точку-финиш и
 * высоту ({@link #withBounds(FinishPoint, int)}). Метод {@link #detectBounds()}
 * пытается уточнить границы, вычислив содержащий всё содержимое прямоугольник
 * (например, убрав пустые поля по краям обрезка).
 */
@Slf4j
public final class WindElement {

    private final WinWindow window;
    private final OcrDriver ocr;
    private Rectangle bounds; // клиентские пиксели окна (= координаты скриншота)

    public WindElement(WinWindow window, Rectangle bounds, OcrDriver ocr) {
        this.window = window;
        this.ocr = ocr;
        this.bounds = new Rectangle(bounds);
    }

    // ===== координаты / position =========================================

    /** Экранные координаты верхнего левого угла элемента (аналог Playwright position). */
    public Position position() {
        int[] r = window.rect();
        return new Position(r[0] + bounds.x, r[1] + bounds.y);
    }

    /** Экранные координаты центра элемента. */
    public int centerScreenX() {
        int[] r = window.rect();
        return r[0] + bounds.x + bounds.width / 2;
    }

    /** Экранные координаты центра элемента. */
    public int centerScreenY() {
        int[] r = window.rect();
        return r[1] + bounds.y + bounds.height / 2;
    }

    /** Базовый прямоугольник элемента в клиентских пикселях окна. */
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    /** Переприсвоить границы. */
    public WindElement withBounds(Rectangle newBounds) {
        this.bounds = new Rectangle(newBounds);
        return this;
    }

    /** Задать границы через точку-финиш (правый нижний угол) и размеры. */
    public WindElement withBounds(FinishPoint finish, int width, int height) {
        this.bounds = boundsFromFinish(finish, width, height);
        return this;
    }

    /** Вычислить прямоугольник из точки-финиша (правый нижний угол) и размеров. */
    public static Rectangle boundsFromFinish(FinishPoint finish, int width, int height) {
        if (finish == null) {
            throw new NullPointerException("finish");
        }
        return new Rectangle(finish.x - width, finish.y - height, width, height);
    }

    // ===== действия =====================================================

    /** Клик по центру элемента (реальным курсором). */
    public WindElement click() {
        window.clickAt(centerScreenX(), centerScreenY());
        return this;
    }

    /** Двойной клик по центру элемента (реальным курсором). */
    public WindElement doubleClick() {
        window.doubleClickAt(centerScreenX(), centerScreenY());
        return this;
    }

    /**
     * Ввести текст в элемент. Для Edit используем WM_CHAR (надёжно в фоновом
     * JVM); иначе — синтетические нажатия клавиш в foreground.
     */
    public WindElement type(String value) {
        if (isEdit()) {
            window.fill(value);
        } else {
            window.goToThisWindow();
            win.win32.driver.WinKey.type(value);
        }
        return this;
    }

    /** Нажатие одной клавиши (например, Enter). */
    public WindElement pressKey(int vk) {
        window.goToThisWindow();
        window.pressKey(vk);
        return this;
    }

    /** Ввести текст методом нажатия клавиш (без WM_CHAR). */
    public WindElement typeKey(String value) {
        window.goToThisWindow();
        win.win32.driver.WinKey.type(value);
        return this;
    }

    // ===== текст из скриншота и границ ==================================

    /** OCR-текст, прочитанный из обрезанного скриншота {@link #bounds()}. */
    public String text() throws IOException {
        BufferedImage crop = crop();
        return ocr.readWordsOfImage(crop).stream()
                .map(w -> w.text)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    // ===== скриншот по границам =========================================

    /** Обрезанный скриншот элемента ({@link #bounds()}) в виде {@link BufferedImage}. */
    public BufferedImage crop() throws IOException {
        File full = window.screenshot(Path.of("target", "windelement"), "full.png");
        BufferedImage img = ImageIO.read(full);
        return img.getSubimage(clamp(bounds.x, 0, Math.max(0, img.getWidth() - 1)),
                clamp(bounds.y, 0, Math.max(0, img.getHeight() - 1)),
                Math.min(bounds.width, Math.max(1, img.getWidth() - bounds.x)),
                Math.min(bounds.height, Math.max(1, img.getHeight() - bounds.y)));
    }

    /** Сохранить скриншот элемента в заданном файле. */
    public File screenshot(Path dir, String name) throws IOException {
        File full = window.screenshot(dir, "full_" + name);
        BufferedImage img = ImageIO.read(full);
        int x = clamp(bounds.x, 0, Math.max(0, img.getWidth() - 1));
        int y = clamp(bounds.y, 0, Math.max(0, img.getHeight() - 1));
        int w = Math.min(bounds.width, Math.max(1, img.getWidth() - x));
        int h = Math.min(bounds.height, Math.max(1, img.getHeight() - y));
        BufferedImage crop = img.getSubimage(x, y, w, h);
        File out = dir.resolve(name).toFile();
        Files.createDirectories(dir.toAbsolutePath());
        ImageIO.write(crop, "png", out);
        return out;
    }

    // ===== границы: попытка определения =================================

    /**
     * Попытка уточнить границы элемента: обрезаем окно, берём регион
     * {@link #bounds()} и сжимаем его, отбрасывая однотонные поля по краям,
     * чтобы остался только содержательный фрагмент (текст/иконка).  Возвращает
     * новые границы (не меняет текущие).  Для экранов с большими пустыми
     * полями это даёт более точный прямоугольник содержимого.
     */
    public Rectangle detectBounds() throws IOException {
        BufferedImage crop = crop();
        int l = 0, t = 0, r = crop.getWidth(), b = crop.getHeight();

        // Убираем пустые (однотонные) строки/столбцы по краям.
        int[] colEmpty = new int[crop.getWidth()];
        int[] rowEmpty = new int[crop.getHeight()];
        java.util.Arrays.fill(colEmpty, 1);
        java.util.Arrays.fill(rowEmpty, 1);
        for (int y = 0; y < crop.getHeight(); y++) {
            for (int x = 0; x < crop.getWidth(); x++) {
                int rgb = crop.getRGB(x, y);
                int lum = luma(rgb);
                // считаем столбец/строку "непустыми", если есть заметный пиксель
                if (lum < 200) {
                    colEmpty[x] = 0;
                    rowEmpty[y] = 0;
                }
            }
        }
        while (l < r && colEmpty[l] == 1) {
            l++;
        }
        while (r > l && colEmpty[r - 1] == 1) {
            r--;
        }
        while (t < b && rowEmpty[t] == 1) {
            t++;
        }
        while (b > t && rowEmpty[b - 1] == 1) {
            b--;
        }
        if (l >= r || t >= b) {
            return new Rectangle(bounds);
        }
        return new Rectangle(bounds.x + l, bounds.y + t, r - l, b - t);
    }

    // ===== helpers ======================================================

    private boolean isEdit() {
        return "Edit".equalsIgnoreCase(window.className());
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    // ===== value types ==================================================

    /** Экранная точка (аналог Playwright {@code options.position}). */
    public static final class Position {
        public final int x;
        public final int y;

        Position(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return "Position[" + x + "," + y + "]";
        }
    }

    /** Точка-финиш границы (правый нижний угол элемента). */
    public static final class FinishPoint {
        public final int x;
        public final int y;

        public FinishPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}