package com.retro.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

class LogTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    private PrintStream originalOut, originalErr;
    private Log.Level originalLevel;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        originalLevel = Log.getLevel();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Log.setLevel(Log.Level.INFO);
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        Log.setLevel(originalLevel);
    }

    @Test
    void infoKeepsTheExistingLineFormat() {
        Log.info("Tag", "hello");
        assertEquals("[Tag] hello" + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void warningsAndErrorsGoToStderrWithTheirLevel() {
        Log.warn("Tag", "careful");
        Log.error("Tag", "broken");
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[Tag] WARN: careful"), text);
        assertTrue(text.contains("[Tag] ERROR: broken"), text);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void thresholdDropsLowerLevels() {
        Log.debug("Tag", "hidden");
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertFalse(Log.isEnabled(Log.Level.DEBUG));

        Log.setLevel(Log.Level.ERROR);
        Log.warn("Tag", "hidden");
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void errorPrintsTheStackTrace() {
        Log.error("Tag", "broken", new IllegalStateException("cause text"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cause text"));
    }

    @Test
    void levelNamesParseInAnyCaseAndFallBack() {
        assertEquals(Log.Level.WARN, Log.parseLevel(" warn ", Log.Level.INFO));
        assertEquals(Log.Level.INFO, Log.parseLevel("nonsense", Log.Level.INFO));
        assertEquals(Log.Level.INFO, Log.parseLevel(null, Log.Level.INFO));
    }
}
