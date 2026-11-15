package com.example.reporttest.samples;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the screenshot navigation building blocks:
 * <ul>
 *   <li>{@link TemplateMatcher} — finds a text-less button by image (NCC);</li>
 *   <li>{@link ScreenNavigator#distance} — direction-aware nearest-selection.</li>
 * </ul>
 * All images are synthesised in memory; no app / OS window is needed.
 */
@Slf4j
class ScreenNavigatorTest {

    // ---- TemplateMatcher --------------------------------------------------

    @Test
    @DisplayName("template matcher finds an embedded glyph in a canvas")
    void templateFindsGlyph() {
        // Build a "button" template: a solid rounded-ish rectangle with a glyph.
        BufferedImage template = new BufferedImage(40, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D tg = template.createGraphics();
        tg.setColor(Color.LIGHT_GRAY);
        tg.fillRoundRect(0, 0, 40, 24, 8, 8);
        tg.setColor(Color.BLACK);
        tg.setFont(new Font("SansSerif", Font.BOLD, 14));
        tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        tg.drawString("X", 16, 17);
        tg.dispose();

        // Big canvas; place the button at a known location.
        BufferedImage canvas = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.drawImage(template, 120, 40, null); // place at (120,40)
        g.dispose();

        TemplateMatcher matcher = TemplateMatcher.fromImage(template);
        List<TemplateMatcher.Match> matches = matcher.match(canvas);
        assertNotNull(matches);
        assertTrue(!matches.isEmpty(), "expected at least one match");
        TemplateMatcher.Match best = matches.get(0);
        log.info("best match = {}", best);
        assertEquals(120, best.x, "x should be at the placed location");
        assertEquals(40, best.y, "y should be at the placed location");
        assertTrue(best.score > 0.9f, "NCC score should be very high, got " + best.score);
    }

    @Test
    @DisplayName("template matcher respects a search region")
    void templateFindsInRegion() {
        BufferedImage template = new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D tg = template.createGraphics();
        tg.setColor(Color.BLUE);
        tg.fillRect(0, 0, 30, 30);
        tg.setColor(Color.RED);
        tg.fillRect(0, 0, 30, 10);   // non-uniform pattern (uniform patches have zero variance -> NCC undefined)
        tg.setColor(Color.WHITE);
        tg.fillRect(0, 20, 30, 10);
        tg.dispose();

        BufferedImage canvas = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 300);
        g.drawImage(template, 200, 200, null); // top-left at (200,200)
        g.dispose();

        TemplateMatcher matcher = TemplateMatcher.fromImage(template);
        // A world region that does NOT contain the glyph -> no match.
        assertNull(matcher.bestIn(canvas, new Rectangle(0, 0, 100, 100)));
        // A region that DOES contain it -> match.
        TemplateMatcher.Match m = matcher.bestIn(canvas, new Rectangle(150, 150, 100, 120));
        assertNotNull(m);
        assertEquals(200, m.x);
        assertEquals(200, m.y);
    }

    // ---- ScreenNavigator.distance ---------------------------------------

    @Test
    @DisplayName("distance picks the nearest control in the requested direction")
    void directionPicksNearest() {
        Rectangle anchor = new Rectangle(100, 100, 40, 20);       // text "Найти"
        Rectangle nearBelow = new Rectangle(100, 220, 60, 24);    // Edit below, close
        Rectangle farBelow = new Rectangle(100, 400, 60, 24);     // Edit below, far
        Rectangle leftEarly = new Rectangle(20, 110, 40, 20);     // Edit to the left
        Rectangle rightBox = new Rectangle(300, 100, 40, 20);     // Edit to the right

        // Choose the candidate that is nearest below.
        // We cannot call private method directly; emulate the distance logic:
        int dNear = ScreenNavigator.distance(anchor, nearBelow, ScreenNavigator.Direction.BELOW);
        int dFar = ScreenNavigator.distance(anchor, farBelow, ScreenNavigator.Direction.BELOW);
        assertTrue(dNear >= 0 && dFar >= 0, "both are below the anchor");
        assertTrue(dNear < dFar, "near-below is closer than far-below");

        // A left candidate is NOT below -> negative (skip).
        assertTrue(ScreenNavigator.distance(anchor, leftEarly, ScreenNavigator.Direction.BELOW) < 0,
                "left candidate must not be considered below");

        // Right direction: only the rightBox qualifies.
        assertTrue(ScreenNavigator.distance(anchor, rightBox, ScreenNavigator.Direction.RIGHT) >= 0);
        assertTrue(ScreenNavigator.distance(anchor, leftEarly, ScreenNavigator.Direction.RIGHT) < 0);
    }

    @Test
    @DisplayName("distance finds the right-most/left-most candidate correctly")
    void directionEdges() {
        Rectangle anchor = new Rectangle(200, 200, 50, 20);
        Rectangle leftA = new Rectangle(50, 210, 30, 20);
        Rectangle leftB = new Rectangle(120, 210, 30, 20); // nearer to anchor
        Rectangle up = new Rectangle(180, 50, 40, 20);

        // For RIGHT, nothing is to the right -> negative for all.
        assertTrue(ScreenNavigator.distance(anchor, leftA, ScreenNavigator.Direction.RIGHT) < 0);
        assertTrue(ScreenNavigator.distance(anchor, up, ScreenNavigator.Direction.RIGHT) < 0);

        // For LEFT, both qualify; B is nearer.
        int dA = ScreenNavigator.distance(anchor, leftA, ScreenNavigator.Direction.LEFT);
        int dB = ScreenNavigator.distance(anchor, leftB, ScreenNavigator.Direction.LEFT);
        assertTrue(dA > 0 && dB > 0);
        assertTrue(dB < dA, "leftB is nearer than leftA");

        // For ABOVE, only `up` qualifies.
        assertTrue(ScreenNavigator.distance(anchor, up, ScreenNavigator.Direction.ABOVE) > 0);
        assertTrue(ScreenNavigator.distance(anchor, leftA, ScreenNavigator.Direction.ABOVE) < 0);
    }
}