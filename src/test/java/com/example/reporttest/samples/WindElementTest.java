package com.example.reporttest.samples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import win.natur.driver.ScreenNavigator;
import win.natur.driver.WindElement;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WindElement} geometry and the {@code STRAIGHT}/{@code EQUAL}
 * point-selection rule.  Pure value logic — no app / OS window is needed.
 *
 * <ul>
 *   <li>{@link WindElement#boundsFromFinish(FinishPoint, int, int)} — прямоугольник
 *       из точки-финиша (правый нижний угол) и размеров;</li>
 *   <li>{@link ScreenNavigator#selectAtPoint(List, int, int)} — выбор элемента,
 *       который содержит точку текста (STRAIGHT/EQUAL), с fallback на ближайший.</li>
 * </ul>
 */
class WindElementTest {

    @Test
    @DisplayName("bounds from a finish point (right-bottom) and size")
    void boundsFromFinishPoint() {
        // Финиш-точка (правый нижний угол) в (100, 60), размеры 40x20
        // => левый верхний угол (100-40, 60-20) = (60, 40).
        WindElement.FinishPoint finish = new WindElement.FinishPoint(100, 60);
        Rectangle r = WindElement.boundsFromFinish(finish, 40, 20);
        assertEquals(60, r.x);
        assertEquals(40, r.y);
        assertEquals(40, r.width);
        assertEquals(20, r.height);
    }

    @Test
    @DisplayName("copy semantics: bounds() returns an independent Rectangle")
    void boundsIsDefensiveCopy() {
        Rectangle original = new Rectangle(10, 10, 40, 20);
        Rectangle r1 = WindElement.boundsFromFinish(new WindElement.FinishPoint(50, 30), 40, 20);
        assertEquals(original, r1);

        // мутируем результат — исходные арифметические данные не должны меняться
        WindElement.FinishPoint f = new WindElement.FinishPoint(100, 100);
        Rectangle r2 = WindElement.boundsFromFinish(f, 50, 30);
        r2.x = 999;
        assertEquals(50, WindElement.boundsFromFinish(f, 50, 30).x);
    }

    @Test
    @DisplayName("STRAIGHT/EQUAL: element whose box contains the anchor point wins")
    void selectAtPointContains() {
        List<Rectangle> boxes = new ArrayList<>();
        Rectangle edit = new Rectangle(200, 80, 60, 22);
        Rectangle combo = new Rectangle(300, 80, 80, 22);
        boxes.add(edit);
        boxes.add(combo);

        // Точка текста строго внутри Edit -> should return index 0.
        int idx = ScreenNavigator.selectAtPoint(boxes, 210, 90);
        assertEquals(0, idx);

        // Точка внутри Combo -> index 1.
        assertEquals(1, ScreenNavigator.selectAtPoint(boxes, 320, 90));
    }

    @Test
    @DisplayName("STRAIGHT/EQUAL: falls back to the nearest box when none contains the point")
    void selectAtPointNearestFallback() {
        List<Rectangle> boxes = new ArrayList<>();
        boxes.add(new Rectangle(200, 80, 60, 22));  // центр (230,91)
        boxes.add(new Rectangle(400, 80, 60, 22));  // центр (430,91)

        // Точка между ними, ближе к первому -> index 0.
        assertEquals(0, ScreenNavigator.selectAtPoint(boxes, 250, 91));

        // Точка значительно правее, ближе ко второму -> index 1.
        assertEquals(1, ScreenNavigator.selectAtPoint(boxes, 390, 91));
    }

    @Test
    @DisplayName("STRAIGHT/EQUAL: empty list gives -1")
    void selectAtPointEmpty() {
        assertEquals(-1, ScreenNavigator.selectAtPoint(new ArrayList<>(), 0, 0));
    }
}