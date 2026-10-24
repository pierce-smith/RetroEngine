package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.ui.batch.UIGeometryBatch;

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

        // Floating console overlay bounds
        float minX = -0.85f, maxX = 0.85f;
        float minY = -0.85f, maxY = -0.20f;

        // Panel Background & Border
        UIGeometryBatch.addQuad(buffer, minX, minY, maxX, maxY, 0.08f, 0.08f, 0.12f, 0.95f);
        UIGeometryBatch.addFrameBorder(buffer, minX, minY, maxX, maxY, 0.003f, 0.0f, 0.55f, 0.90f, 1.0f);

        // Header Title
        UIGeometryBatch.addQuad(buffer, minX, maxY - 0.05f, maxX, maxY, 0.12f, 0.14f, 0.20f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "ENGINE CONSOLE & SYSTEM LOGS (Cmd + ~)", minX + 0.02f, maxY - 0.035f, maxX - 0.02f, 1.1f, 0.3f, 0.8f, 1.0f, 1.0f);

        // Render Log Lines from Ring Buffer
        float lineY = maxY - 0.09f;
        int maxVisibleLines = 8;

        LogEntry[] entries = logBuffer.toArray(new LogEntry[0]);
        int startIndex = Math.max(0, entries.length - maxVisibleLines);

        for (int i = startIndex; i < entries.length; i++) {
            if (lineY < minY + 0.02f) break;
            LogEntry entry = entries[i];

            float r = 0.9f, g = 0.9f, b = 0.9f;
            if (entry.level() == LogLevel.WARN) { r = 1.0f; g = 0.85f; b = 0.2f; }
            else if (entry.level() == LogLevel.ERROR) { r = 1.0f; g = 0.3f; b = 0.3f; }

            String lineText = "[" + entry.timestamp() + "] " + entry.message();
            UIGeometryBatch.addBoundedText(buffer, lineText, minX + 0.02f, lineY, maxX - 0.02f, 0.95f, r, g, b, 1.0f);
            lineY -= 0.07f;
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

        // Panel Background & Border
        UIGeometryBatch.addQuad(buffer, minX, minY, maxX, maxY, 0.08f, 0.09f, 0.13f, 0.85f);
        UIGeometryBatch.addFrameBorder(buffer, minX, minY, maxX, maxY, 0.002f, 0.25f, 0.30f, 0.40f, 1.0f);

        // Header Title
        UIGeometryBatch.addBoundedText(buffer, "SYSTEM PROFILER TELEMETRY", minX + 0.015f, maxY - 0.035f, maxX - 0.015f, 1.0f, 0.25f, 0.8f, 1.0f, 1.0f);

        // FPS Metric
        fpsSb.setLength(0);
        fpsSb.append("FPS: ").append(fps).append(" (");
        appendFloat1Decimal(fpsSb, frameTimeMs);
        fpsSb.append(" ms)");

        float fpsR = (fps >= 50) ? 0.3f : (fps >= 30 ? 1.0f : 1.0f);
        float fpsG = (fps >= 50) ? 1.0f : (fps >= 30 ? 0.85f : 0.3f);
        float fpsB = (fps >= 50) ? 0.4f : (fps >= 30 ? 0.2f : 0.3f);
        UIGeometryBatch.addBoundedText(buffer, fpsSb, minX + 0.015f, maxY - 0.08f, maxX - 0.015f, 0.95f, fpsR, fpsG, fpsB, 1.0f);

        // Draw Calls & Vertex Count Metric
        statsSb.setLength(0);
        statsSb.append("DRAWS: ").append(drawCalls).append(" | VERTS: ").append(activeVertices);
        UIGeometryBatch.addBoundedText(buffer, statsSb, minX + 0.015f, maxY - 0.125f, maxX - 0.015f, 0.95f, 0.85f, 0.85f, 0.9f, 1.0f);

        // JVM Heap Metric (Used / Currently Allotted Committed)
        memSb.setLength(0);
        memSb.append("JVM HEAP: ");
        appendFloat1Decimal(memSb, usedMb);
        memSb.append(" / ");
        appendFloat1Decimal(memSb, totalMb);
        memSb.append(" MB");
        UIGeometryBatch.addBoundedText(buffer, memSb, minX + 0.015f, maxY - 0.170f, maxX - 0.015f, 0.95f, 0.9f, 0.75f, 0.3f, 1.0f);
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
