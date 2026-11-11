package com.example.reporttest.driver;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.List;

/**
 * Minimal GDI32 mapping used to render a window into a raster buffer
 * (PrintWindow into a memory DC, then GetDIBits into a BMP) without needing a
 * screen grab.  Works even when java.awt.Robot is denied in the forked JVM.
 */
public interface Gdi extends StdCallLibrary {

    Gdi INSTANCE = com.sun.jna.Native.load("gdi32", Gdi.class);

    Pointer CreateCompatibleDC(Pointer hdc);
    boolean DeleteDC(Pointer hdc);
    Pointer CreateCompatibleBitmap(Pointer hdc, int width, int height);
    Pointer SelectObject(Pointer hdc, Pointer obj);
    boolean DeleteObject(Pointer obj);
    int GetDeviceCaps(Pointer hdc, int index);
    int SRCCOPY = 0x00CC0020;

    /**
     * GetDIBits: copy a bitmap into a DIB (device-independent bitmap) buffer in
     * 32-bit top-down BGRA form.
     */
    int GetDIBits(Pointer hdc, Pointer hbm, int start, int cLines,
                  Pointer lpvBits, BITMAPINFO lpbmi, int usage);

    class BITMAPINFOHEADER extends Structure {
        public int biSize;
        public int biWidth;
        public int biHeight;
        public short biPlanes;
        public short biBitCount;
        public int biCompression;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;

        public BITMAPINFOHEADER() {
            biSize = 40;
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
                    "biCompression", "biSizeImage", "biXPelsPerMeter",
                    "biYPelsPerMeter", "biClrUsed", "biClrImportant");
        }
    }

    class BITMAPINFO extends Structure {
        public BITMAPINFOHEADER bmiHeader;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bmiHeader");
        }
    }
}
