package com.example.reporttest.driver;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.RECT;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Captures a window using Win32 PrintWindow into a memory DC + GetDIBits and
 * writes it as a PNG.  This works from a forked/test JVM where
 * java.awt.Robot.createScreenCapture is denied (no interactive desktop),
 * because PrintWindow asks the window itself to paint into a supplied DC.
 */
public final class Screenshot {

    private Screenshot() {
    }

    public static File captureWindow(NativeWindow w, Path dir, String fileName) throws IOException {
        RECT r = w.getRect();
        int width = r.right - r.left;
        int height = r.bottom - r.top;
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid window rect for screenshot: " + r);
        }
        return printWindowCapture(w, dir, fileName, width, height);
    }

    private static File printWindowCapture(NativeWindow w, Path dir, String fileName,
                                            int width, int height) throws IOException {
        Pointer hwnd = w.handle();
        Gdi gdi = Gdi.INSTANCE;
        Pointer hdcWin = null;
        Pointer hdcMem = null;
        Pointer hbm = null;
        Pointer old = null;
        try {
            // window DC (for the target window)
            hdcWin = Win32.INSTANCE.GetDC(hwnd);
            if (hdcWin == null) {
                throw new IOException("GetDC returned null for " + hwnd);
            }
            hdcMem = gdi.CreateCompatibleDC(hdcWin);
            hbm = gdi.CreateCompatibleBitmap(hdcWin, width, height);
            old = gdi.SelectObject(hdcMem, hbm);
            // ask the window to paint itself into our memory DC
            boolean ok = Win32.INSTANCE.PrintWindow(hwnd, hdcMem, Win32.PW_RENDERFULLCONTENT);
            if (!ok) {
                System.err.println("[WARN] PrintWindow returned false for " + hwnd);
            }
            // extract pixels via GetDIBits into a BGRA top-down buffer
            int stride = width * 4;
            Memory bits = new Memory((long) stride * height);
            Gdi.BITMAPINFO bmi = new Gdi.BITMAPINFO();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height; // negative => top-down
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = 0; // BI_RGB
            bmi.bmiHeader.write();
            gdi.GetDIBits(hdcMem, hbm, 0, height, bits, bmi, 0);

            byte[] bytes = bits.getByteArray(0, (int) (stride * height));
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                int row = y * stride;
                for (int x = 0; x < width; x++) {
                    int p = row + x * 4;
                    int b = bytes[p] & 0xFF;
                    int g = bytes[p + 1] & 0xFF;
                    int r2 = bytes[p + 2] & 0xFF;
                    int a = bytes[p + 3] & 0xFF;
                    int rgb = (a << 24) | (r2 << 16) | (g << 8) | b;
                    img.setRGB(x, y, rgb);
                }
            }
            Files.createDirectories(dir);
            File out = Paths.get(dir.toString(), fileName).toFile();
            ImageIO.write(img, "png", out);
            return out;
        } finally {
            if (old != null) {
                gdi.SelectObject(hdcMem, old);
            }
            if (hbm != null) {
                gdi.DeleteObject(hbm);
            }
            if (hdcMem != null) {
                gdi.DeleteDC(hdcMem);
            }
            if (hdcWin != null) {
                Win32.INSTANCE.ReleaseDC(hwnd, hdcWin);
            }
        }
    }
}
