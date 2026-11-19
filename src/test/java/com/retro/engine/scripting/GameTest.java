package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Game: script requests are reported at once but reach the host only between frames. */
class GameTest {

    private static final class FakeHost implements Game.Host {
        final List<String> calls = new ArrayList<>();
        String title = "My Game";
        boolean fullscreen, vsync = true;
        int width = 1280, height = 720, cap;

        @Override public boolean isEditor() { return false; }
        @Override public String getTitle() { return title; }
        @Override public void setTitle(String t) { title = t; calls.add("title " + t); }
        @Override public void quit() { calls.add("quit"); }
        @Override public boolean isFullscreen() { return fullscreen; }
        @Override public void setFullscreen(boolean f) { fullscreen = f; calls.add("fullscreen " + f); }
        @Override public int getWindowWidth() { return width; }
        @Override public int getWindowHeight() { return height; }
        @Override public void setResolution(int w, int h) { width = w; height = h; calls.add("resolution " + w + "x" + h); }
        @Override public List<Game.DisplayMode> getDisplayModes() { return List.of(new Game.DisplayMode(1920, 1080, 60)); }
        @Override public boolean isVsync() { return vsync; }
        @Override public void setVsync(boolean v) { vsync = v; calls.add("vsync " + v); }
        @Override public int getFrameRateCap() { return cap; }
        @Override public void setFrameRateCap(int fps) { cap = fps; calls.add("cap " + fps); }
    }

    private FakeHost host;

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        Game.bind(host);
    }

    @AfterEach
    void tearDown() {
        Game.bind(null);
    }

    @Test
    void requestsWaitForTheFrameBoundaryButGettersSeeThemAtOnce() {
        Game.setTitle("Chapter 2");
        Game.setFullscreen(true);
        Game.setResolution(1920, 1080);
        Game.setVsync(false);
        Game.setFrameRateCap(144);

        assertTrue(host.calls.isEmpty(), "nothing touches the window mid-frame");
        assertEquals("Chapter 2", Game.getTitle());
        assertTrue(Game.isFullscreen());
        assertEquals(1920, Game.getWindowWidth());
        assertFalse(Game.isVsync());
        assertEquals(144, Game.getFrameRateCap());

        Game.applyPending();
        assertEquals(List.of("title Chapter 2", "fullscreen true", "resolution 1920x1080", "vsync false", "cap 144"), host.calls);

        host.calls.clear();
        Game.applyPending();
        assertTrue(host.calls.isEmpty(), "each request is applied once");
        assertEquals("Chapter 2", Game.getTitle(), "after applying, getters read the host");
    }

    @Test
    void quitIsAppliedLast() {
        Game.quit();
        Game.setTitle("Bye");
        Game.applyPending();
        assertEquals(List.of("title Bye", "quit"), host.calls);
    }

    @Test
    void frameCapFollowsTheProjectRule() {
        Game.setFrameRateCap(5);
        assertEquals(30, Game.getFrameRateCap());
        Game.setFrameRateCap(-1);
        assertEquals(0, Game.getFrameRateCap(), "0 or below means no cap");
    }

    @Test
    void badSizesAreIgnored() {
        Game.setResolution(0, 600);
        Game.applyPending();
        assertTrue(host.calls.isEmpty());
        assertEquals(1280, Game.getWindowWidth());
    }

    @Test
    void bindingANewHostDropsRequestsFromTheLastRun() {
        Game.quit();
        Game.setTitle("stale");
        FakeHost next = new FakeHost();
        Game.bind(next);
        Game.applyPending();
        assertTrue(next.calls.isEmpty());
    }

    @Test
    void withoutAHostEverythingIsHarmless() {
        Game.bind(null);
        Game.setTitle("x");
        Game.quit();
        Game.applyPending();
        assertEquals("", Game.getTitle());
        assertTrue(Game.getDisplayModes().isEmpty());
        assertFalse(Game.isEditor());
    }
}
