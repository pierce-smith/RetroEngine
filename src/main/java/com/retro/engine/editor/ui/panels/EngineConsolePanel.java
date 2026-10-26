package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.input.PlatformShortcuts;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;

/**
 * Engine Console & Real-Time Profiler HUD Overlay Panel.
 * Redirects System.out / System.err streams to a ring-buffer log viewer,
 * and renders live frame time, draw calls, vertex counts, and JVM heap telemetry.
 * Zero-allocation per frame execution on hot rendering paths.
 */
public class EngineConsolePanel {

    public enum LogLevel { INFO, WARN, ERROR }

    public record LogEntry(String timestamp, String message, LogLevel level) {}

    private static final int MAX_LOG_ENTRIES = 100;
    private static final Deque<LogEntry> logBuffer = new ArrayDeque<>();
    private static boolean consoleVisible = false;

    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static boolean redirected = false;

    // Live Telemetry Counters from SceneRenderPipeline & RHI
    private static int liveDrawCalls = 0;
    private static int liveActiveVertices = 0;

    // Static zero-allocation formatting buffers for Profiler HUD
    private static final StringBuilder fpsSb = new StringBuilder(64);
    private static final StringBuilder statsSb = new StringBuilder(64);
    private static final StringBuilder memSb = new StringBuilder(64);

    private static final String CONSOLE_HINT = "TOGGLE  " + PlatformShortcuts.TOGGLE_CONSOLE.label();

    public static boolean isConsoleVisible() { return consoleVisible; }
    public static void setConsoleVisible(boolean visible) { consoleVisible = visible; }
    public static void toggleConsole() { consoleVisible = !consoleVisible; }

    public static void updateTelemetry(int drawCalls, int activeVertices) {
        liveDrawCalls = Math.max(0, drawCalls);
        liveActiveVertices = Math.max(0, activeVertices);
    }

    public static int getLiveDrawCalls() { return liveDrawCalls; }
    public static int getLiveActiveVertices() { return liveActiveVertices; }

    public static synchronized void installConsoleRedirect() {
        if (redirected) return;
        originalOut = System.out;
        originalErr = System.err;

        System.setOut(new PrintStream(new LoggingOutputStream(originalOut, LogLevel.INFO), true));
        System.setErr(new PrintStream(new LoggingOutputStream(originalErr, LogLevel.ERROR), true));
        redirected = true;
        log(LogLevel.INFO, "[EngineConsole] Standard I/O redirected to in-editor console stream.");
    }

    public static synchronized void log(LogLevel level, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String timeStr = new SimpleDateFormat("HH:mm:ss").format(new Date());
        if (logBuffer.size() >= MAX_LOG_ENTRIES) {
            logBuffer.removeFirst();
        }
        logBuffer.addLast(new LogEntry(timeStr, message.trim(), level));
    }

    public static Deque<LogEntry> getLogEntries() { return logBuffer; }

    public static void renderConsole(UIGeometryBatch.Buffer buffer) {
        if (!consoleVisible) return;

        // Console bounds (design space; the dock maps this onto the Console tile)
        float minX = -0.85f, maxX = 0.85f;
        float minY = -0.85f, maxY = -0.20f;
        float headerH = 0.05f;

        UIGeometryBatch.addQuad(buffer, minX, minY, maxX, maxY, EditorTheme.SURFACE, 1.0f);

        // Header strip: title left, toggle shortcut right
        UIGeometryBatch.addQuad(buffer, minX, maxY - headerH, maxX, maxY, EditorTheme.BACKGROUND, 1.0f);
        UIGeometryBatch.addQuad(buffer, minX, maxY - headerH, maxX, maxY - headerH + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);
        float headerMid = maxY - headerH * 0.5f;
        UIGeometryBatch.addBoundedText(buffer, "ENGINE CONSOLE", minX + 0.02f, headerMid + 0.009f, maxX - 0.3f, 1.0f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addRightAlignedText(buffer, CONSOLE_HINT, maxX - 0.02f, headerMid + 0.0081f, 0.9f, EditorTheme.TEXT_MUTED, 1.0f);

        // Log rows from the ring buffer (newest at the bottom)
        float rowH = 0.07f;
        int maxVisibleLines = 8;
        LogEntry[] entries = logBuffer.toArray(new LogEntry[0]);
        int startIndex = Math.max(0, entries.length - maxVisibleLines);

        float rowTop = maxY - headerH - 0.006f;
        for (int i = startIndex; i < entries.length; i++) {
            float rowBottom = rowTop - rowH;
            if (rowBottom < minY + 0.005f) break;
            LogEntry entry = entries[i];
            float mid = (rowTop + rowBottom) * 0.5f;

            if (((i - startIndex) & 1) == 1) {
                UIGeometryBatch.addQuad(buffer, minX + 0.004f, rowBottom, maxX - 0.004f, rowTop, EditorTheme.SURFACE_RAISED, 0.45f);
            }

            int levelColor;
            int textColor;
            String levelTag;
            switch (entry.level()) {
                case WARN -> { levelColor = EditorTheme.ORANGE; textColor = EditorTheme.YELLOW; levelTag = "WARN"; }
                case ERROR -> { levelColor = EditorTheme.RED; textColor = EditorTheme.RED; levelTag = "ERR"; }
                default -> { levelColor = EditorTheme.GRAPHITE; textColor = EditorTheme.TEXT; levelTag = "INFO"; }
            }

            // Level pill
            float pillX0 = minX + 0.02f, pillX1 = pillX0 + 0.075f;
            UIGeometryBatch.addPill(buffer, pillX0, mid - 0.016f, pillX1, mid + 0.016f, levelColor, 1.0f);
            float tagW = UIGeometryBatch.textWidth(buffer, levelTag, 0.75f);
            UIGeometryBatch.addBoundedText(buffer, levelTag, (pillX0 + pillX1 - tagW) * 0.5f, mid + 0.75f * 0.009f, pillX1, 0.75f,
                    entry.level() == LogLevel.INFO ? EditorTheme.TEXT : EditorTheme.BACKGROUND, 1.0f);

            // Timestamp (muted) + message
            float tsX = pillX1 + 0.018f;
            UIGeometryBatch.addBoundedText(buffer, entry.timestamp(), tsX, mid + 0.95f * 0.009f, maxX - 0.02f, 0.95f, EditorTheme.TEXT_MUTED, 1.0f);
            float msgX = tsX + UIGeometryBatch.textWidth(buffer, 8, 0.95f) + 0.02f;
            UIGeometryBatch.addTruncatedText(buffer, entry.message(), msgX, mid + 0.95f * 0.009f, maxX - 0.02f, 0.95f, textColor, 1.0f);

            rowTop = rowBottom;
        }
    }

    public static void renderProfilerHUD(UIGeometryBatch.Buffer buffer, float deltaTime) {
        renderProfilerHUD(buffer, deltaTime, liveDrawCalls, liveActiveVertices);
    }

    public static void renderProfilerHUD(UIGeometryBatch.Buffer buffer, float deltaTime, int drawCalls, int activeVertices) {
        // Right side of 3D Viewport canvas bounds (Viewport is -0.55f to 0.55f, Toolbar is -0.52f to -0.10f)
        float minX = 0.10f, maxX = 0.52f;
        float minY = 0.65f, maxY = 0.86f;

        long totalMem = Runtime.getRuntime().totalMemory();
        long freeMem = Runtime.getRuntime().freeMemory();
        long usedMemBytes = totalMem - freeMem;
        float usedMb = (float) (usedMemBytes / (1024.0 * 1024.0));
        float totalMb = (float) (totalMem / (1024.0 * 1024.0));

        int fps = (int) (1.0f / Math.max(0.0001f, deltaTime));
        float frameTimeMs = deltaTime * 1000.0f;

        // Translucent rounded HUD
        UIGeometryBatch.addRoundedCard(buffer, minX, minY, maxX, maxY, EditorTheme.RADIUS * 1.5f, EditorTheme.BACKGROUND, EditorTheme.BORDER, 0.82f);

        // Header Title
        UIGeometryBatch.addBoundedText(buffer, "PROFILER", minX + 0.015f, maxY - 0.030f, maxX - 0.015f, 0.9f, EditorTheme.TEXT_MUTED, 1.0f);

        // FPS Metric
        fpsSb.setLength(0);
        fpsSb.append("FPS: ").append(fps).append(" (");
        appendFloat1Decimal(fpsSb, frameTimeMs);
        fpsSb.append(" ms)");

        int fpsColor = (fps >= 50) ? EditorTheme.GREEN : (fps >= 30 ? EditorTheme.YELLOW : EditorTheme.RED);
        UIGeometryBatch.addBoundedText(buffer, fpsSb, minX + 0.015f, maxY - 0.08f, maxX - 0.015f, 0.95f, fpsColor, 1.0f);

        // Draw Calls & Vertex Count Metric
        statsSb.setLength(0);
        statsSb.append("DRAWS: ").append(drawCalls).append(" | VERTS: ").append(activeVertices);
        UIGeometryBatch.addBoundedText(buffer, statsSb, minX + 0.015f, maxY - 0.125f, maxX - 0.015f, 0.95f, EditorTheme.TEXT, 1.0f);

        // JVM Heap Metric (Used / Currently Allotted Committed)
        memSb.setLength(0);
        memSb.append("JVM HEAP: ");
        appendFloat1Decimal(memSb, usedMb);
        memSb.append(" / ");
        appendFloat1Decimal(memSb, totalMb);
        memSb.append(" MB");
        UIGeometryBatch.addBoundedText(buffer, memSb, minX + 0.015f, maxY - 0.170f, maxX - 0.015f, 0.95f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void appendFloat1Decimal(StringBuilder sb, float val) {
        if (val < 0) {
            sb.append('-');
            val = -val;
        }
        long intPart = (long) val;
        sb.append(intPart);
        sb.append('.');
        long decPart = Math.round((val - intPart) * 10.0f);
        if (decPart >= 10) {
            intPart++;
            decPart = 0;
        }
        sb.append(decPart);
    }

    private static class LoggingOutputStream extends OutputStream {
        private final PrintStream targetStream;
        private final LogLevel level;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

        public LoggingOutputStream(PrintStream targetStream, LogLevel level) {
            this.targetStream = targetStream;
            this.level = level;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.reset();
                if (targetStream != null) targetStream.println(line);
                log(level, line);
            } else if (b != '\r') {
                lineBuffer.write(b);
            }
        }
    }
}
