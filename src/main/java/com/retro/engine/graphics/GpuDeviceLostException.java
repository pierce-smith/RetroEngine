package com.retro.engine.graphics;

import com.retro.engine.api.Internal;

/**
 * Thrown from a render backend's frame calls once the GPU device is gone: a driver crash or reset
 * ({@code VK_ERROR_DEVICE_LOST}, Metal's device-removed / access-revoked command buffer errors), an unplugged GPU, or a
 * GPU that stopped finishing frames for {@link #HANG_TIMEOUT_SECONDS}. Nothing the backend owns can be used after this,
 * so the engine does not try to keep rendering: {@code Main} backs up the editor scene, tells the user in plain words and
 * exits with {@code EXIT_GPU_LOST}.
 *
 * <p>Backends detect the loss wherever it surfaces (fence waits, submits, presents, upload threads, Metal's completion
 * handler thread), record it, and throw this from the next {@code beginFrame} on the main thread.</p>
 */
@Internal
public final class GpuDeviceLostException extends RuntimeException {

    /** A GPU that has not finished a frame for this long is treated as hung; Windows' own driver watchdog fires at 2 s. */
    public static final long HANG_TIMEOUT_SECONDS = 10L;
    public static final long HANG_TIMEOUT_NS = HANG_TIMEOUT_SECONDS * 1_000_000_000L;

    private final GraphicsAPI api;
    private final String reason;

    public GpuDeviceLostException(GraphicsAPI api, String reason) {
        super("GPU device lost on " + api + ": " + reason);
        this.api = api;
        this.reason = reason;
    }

    public GraphicsAPI getApi() { return api; }

    /** The technical cause, for the log (e.g. "vkQueueSubmit2 returned VK_ERROR_DEVICE_LOST"). */
    public String getReason() { return reason; }

    /** What to tell a player or editor user; {@code appName} is the game's or the editor's name. */
    public String userMessage(String appName) {
        String name = (appName == null || appName.isBlank()) ? "The program" : appName;
        return "The graphics card stopped responding, so " + name + " has to close.\n\n"
                + "This usually means the graphics driver crashed or was updated, or the GPU was reset or unplugged. "
                + "Updating the graphics driver usually fixes it.\n\nDetails: " + reason;
    }
}
