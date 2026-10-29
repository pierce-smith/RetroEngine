package com.retro.engine.render.ui;

import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.panels.ViewportPanel;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.ui.UICanvasComponent;
import com.retro.engine.scene.components.ui.UIElement;
import com.retro.engine.scene.components.ui.UIImage;
import com.retro.engine.scene.components.ui.UIPanel;
import com.retro.engine.scene.components.ui.UIProgressBar;
import com.retro.engine.scene.components.ui.UIText;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds game UI (HUD) geometry from the scene's {@link UICanvasComponent}s into a private 2D batch, clipped to the
 * game viewport rect: the docked Scene tile in the editor, the whole window in standalone builds. The result is drawn
 * by the backends' UI pass ahead of any editor geometry, i.e. over the 3D scene and under editor overlays.
 * <p>
 * Screen-space overlays render only through the primary Camera: the canvas on the camera's entity or the nearest one
 * above it (its root canvas), plus the canvas it links ({@link Camera#activeCanvas}, else {@link Camera#uiCanvas}
 * resolved by entity id, then by name). UI elements on or under the camera entity with no canvas above them are drawn
 * on the camera's auto-bound default canvas ({@link Camera#getOrCreateAutoCanvas()}). World-space billboards render
 * for every enabled canvas in front of the view camera, facing it and shrinking with distance.
 * Each instance owns its buffer and scratch state; use one per thread.
 */
public final class GameUIRenderer {

    private static final int CIRCLE_SEGMENTS = 32;
    private static final float[] CIRCLE_COS = new float[CIRCLE_SEGMENTS + 1];
    private static final float[] CIRCLE_SIN = new float[CIRCLE_SEGMENTS + 1];
    static {
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double t = 2.0 * Math.PI * i / CIRCLE_SEGMENTS;
            CIRCLE_COS[i] = (float) Math.cos(t);
            CIRCLE_SIN[i] = (float) Math.sin(t);
        }
    }
    private static final int MAX_PARENT_DEPTH = 64;

    /** A canvas to draw and the entity its elements are keyed by (the camera entity for its auto-bound canvas). */
    private static final class CanvasRef {
        Entity owner;
        UICanvasComponent canvas;
    }

    private static final Comparator<CanvasRef> CANVAS_ORDER = (a, b) -> {
        UICanvasComponent ca = a.canvas, cb = b.canvas;
        int modeA = ca.renderMode == UICanvasComponent.RenderMode.WORLD_SPACE_BILLBOARD ? 0 : 1;
        int modeB = cb.renderMode == UICanvasComponent.RenderMode.WORLD_SPACE_BILLBOARD ? 0 : 1;
        if (modeA != modeB) return Integer.compare(modeA, modeB);
        return Float.compare(ca.sortOrder, cb.sortOrder);
    };
    private static final Comparator<UIElement> ELEMENT_ORDER = (a, b) -> Float.compare(a.sortOrder, b.sortOrder);

    private final UIGeometryBatch.Buffer buffer = new UIGeometryBatch.Buffer(8192);
    private final Map<String, Entity> entitiesById = new HashMap<>();
    private final List<CanvasRef> canvases = new ArrayList<>();
    private final List<CanvasRef> canvasPool = new ArrayList<>();
    private final float[] viewScratch = new float[16];
    // Primary camera entity whose subtree falls back to its auto canvas this frame (null = no fallback)
    private Entity autoCanvasCamera;
    // Every element in the scene paired with the canvas entity that owns it (null = no canvas ancestor)
    private final List<UIElement> elements = new ArrayList<>();
    private final List<Entity> elementCanvases = new ArrayList<>();
    private final List<UIElement> canvasElements = new ArrayList<>();

    // Per-frame target: viewport rect in window NDC, NDC units per window pixel, and its size in pixels
    private float rectX0, rectY0;
    private float ndcPerPxX, ndcPerPxY;
    private float targetW, targetH;

    /**
     * Rebuilds the game UI for one frame and returns the buffer holding it (valid until the next call).
     *
     * @param rectX0..rectY1 game viewport in window NDC; geometry is clipped to it
     * @param windowWidth    window size in pixels, which text and stroke sizes are measured in
     * @param view           camera the 3D scene is rendered from (for world-space billboards); may be null
     * @param aspect         projection aspect of the game viewport
     */
    public UIGeometryBatch.Buffer build(List<Entity> entities, SceneWorld world,
                                        float rectX0, float rectY0, float rectX1, float rectY1,
                                        int windowWidth, int windowHeight, ViewportCamera view, float aspect) {
        buffer.reset();
        if (entities == null || entities.isEmpty() || rectX1 <= rectX0 || rectY1 <= rectY0) return buffer;
        if (!collect(entities)) return buffer;

        buffer.setWindowSize(windowWidth, windowHeight);
        this.rectX0 = rectX0;
        this.rectY0 = rectY0;
        this.ndcPerPxX = 2.0f / buffer.getWindowWidth();
        this.ndcPerPxY = 2.0f / buffer.getWindowHeight();
        this.targetW = (rectX1 - rectX0) / ndcPerPxX;
        this.targetH = (rectY1 - rectY0) / ndcPerPxY;

        // Identity mapping: coordinates stay in window NDC, clipped to the game viewport
        buffer.setPanelMapping(rectX0, rectY0, rectX1, rectY1, rectX0, rectY0, rectX1, rectY1);
        try {
            for (int i = 0; i < canvases.size(); i++) {
                CanvasRef ref = canvases.get(i);
                Entity canvasEntity = ref.owner;
                UICanvasComponent canvas = ref.canvas;
                if (canvas.renderMode == UICanvasComponent.RenderMode.WORLD_SPACE_BILLBOARD) {
                    drawWorldCanvas(canvasEntity, canvas, world, view, aspect);
                } else {
                    float scale = Math.min(targetW / Math.max(1.0f, canvas.referenceWidth), targetH / Math.max(1.0f, canvas.referenceHeight));
                    drawCanvasElements(canvasEntity, 0.0f, 0.0f, targetW, targetH, scale);
                }
            }
        } finally {
            buffer.clearPanelMapping();
        }
        return buffer;
    }

    /** Gathers drawable canvases (in draw order) and every element's owning canvas; false when nothing can draw. */
    private boolean collect(List<Entity> entities) {
        canvases.clear();
        elements.clear();
        elementCanvases.clear();
        entitiesById.clear();
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity e = entities.get(i);
            if (e != null && e.id != null) entitiesById.put(e.id, e);
        }

        Entity cameraEntity = ViewportPanel.findMainCamera(entities);
        Camera camera = (cameraEntity != null) ? cameraEntity.getComponent(Camera.class) : null;
        UICanvasComponent linked = (camera != null) ? resolveLinkedCanvas(camera, entities) : null;
        autoCanvasCamera = null;
        // The camera's own canvas, or the nearest one above it, renders through the camera
        Entity cameraRootCanvas = (cameraEntity != null) ? owningCanvas(cameraEntity) : null;

        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity e = entities.get(i);
            if (e == null) continue;
            UICanvasComponent canvas = e.getComponent(UICanvasComponent.class);
            if (canvas == null || !canvas.enabled) continue;
            boolean show = canvas.renderMode == UICanvasComponent.RenderMode.WORLD_SPACE_BILLBOARD
                    || (camera != null && (canvas == linked || e == cameraRootCanvas));
            if (show) addCanvas(e, canvas);
        }
        if (camera != null && cameraRootCanvas == null) {
            UICanvasComponent auto = camera.getOrCreateAutoCanvas();
            if (auto.enabled) {
                autoCanvasCamera = cameraEntity;
                addCanvas(cameraEntity, auto);
            }
        }
        if (canvases.isEmpty()) return false;
        canvases.sort(CANVAS_ORDER);

        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity e = entities.get(i);
            if (e == null) continue;
            List<Component> components = e.getAllComponents();
            Entity owner = null;
            boolean ownerResolved = false;
            for (int c = 0; c < components.size(); c++) {
                if (!(components.get(c) instanceof UIElement element)) continue;
                if (!ownerResolved) {
                    owner = owningCanvas(e);
                    ownerResolved = true;
                }
                if (owner == null) break;
                elements.add(element);
                elementCanvases.add(owner);
            }
        }
        return true;
    }

    private UICanvasComponent resolveLinkedCanvas(Camera camera, List<Entity> entities) {
        if (camera.activeCanvas != null) return camera.activeCanvas;
        String ref = camera.uiCanvas;
        if (ref == null || ref.isBlank()) return null;
        Entity target = entitiesById.get(ref);
        if (target == null) {
            for (int i = 0, n = entities.size(); i < n; i++) {
                Entity e = entities.get(i);
                if (e != null && e.name != null && e.name.equalsIgnoreCase(ref.trim()) && e.getComponent(UICanvasComponent.class) != null) {
                    target = e;
                    break;
                }
            }
        }
        return (target != null) ? target.getComponent(UICanvasComponent.class) : null;
    }

    private void addCanvas(Entity owner, UICanvasComponent canvas) {
        int index = canvases.size();
        if (index == canvasPool.size()) canvasPool.add(new CanvasRef());
        CanvasRef ref = canvasPool.get(index);
        ref.owner = owner;
        ref.canvas = canvas;
        canvases.add(ref);
    }

    /**
     * Nearest entity (self included) up the parent chain carrying a UICanvasComponent. With no canvas on the chain,
     * elements on or under the auto-canvas camera resolve to that camera entity; otherwise null.
     */
    private Entity owningCanvas(Entity e) {
        Entity cur = e;
        boolean underCamera = false;
        for (int depth = 0; cur != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (cur.getComponent(UICanvasComponent.class) != null) return cur;
            if (cur == autoCanvasCamera) underCamera = true;
            cur = (cur.parentId != null) ? entitiesById.get(cur.parentId) : null;
        }
        return underCamera ? autoCanvasCamera : null;
    }

    /** Projects the canvas entity's world position with the view camera and draws the canvas as a facing billboard. */
    private void drawWorldCanvas(Entity canvasEntity, UICanvasComponent canvas, SceneWorld world, ViewportCamera view, float aspect) {
        if (view == null || world == null) return;
        SceneWorld.WorldTransform wt = world.getComputedWorldTransform(canvasEntity);

        // The view the scene renders with: a Play camera entity's inverse transform, or the editor orbit
        float[] v = viewScratch;
        view.computeViewMatrix(v);
        float viewX = v[0] * wt.px() + v[4] * wt.py() + v[8] * wt.pz() + v[12];
        float viewY = v[1] * wt.px() + v[5] * wt.py() + v[9] * wt.pz() + v[13];
        float viewZ = v[2] * wt.px() + v[6] * wt.py() + v[10] * wt.pz() + v[14];
        float depth = -viewZ;
        if (depth <= Math.max(0.01f, view.getNear())) return;

        float f = (float) (1.0 / Math.tan(Math.toRadians(view.getFov()) * 0.5));
        float ndcX = (f / Math.max(0.01f, aspect)) * viewX / depth;
        float ndcY = f * viewY / depth;

        float scale = canvas.worldUnitsPerPixel * f * (targetH * 0.5f) / depth;
        if (scale <= 1e-4f) return;
        float canvasW = canvas.referenceWidth * scale, canvasH = canvas.referenceHeight * scale;
        float centerX = (ndcX + 1.0f) * 0.5f * targetW, centerY = (ndcY + 1.0f) * 0.5f * targetH;
        drawCanvasElements(canvasEntity, centerX - canvasW * 0.5f, centerY - canvasH * 0.5f, canvasW, canvasH, scale);
    }

    /** Draws the canvas's elements in sort order inside a canvas rect given in target pixels (origin bottom-left). */
    private void drawCanvasElements(Entity canvasEntity, float originX, float originY, float canvasW, float canvasH, float scale) {
        canvasElements.clear();
        for (int i = 0, n = elements.size(); i < n; i++) {
            if (elementCanvases.get(i) == canvasEntity) canvasElements.add(elements.get(i));
        }
        canvasElements.sort(ELEMENT_ORDER);

        for (int i = 0, n = canvasElements.size(); i < n; i++) {
            UIElement el = canvasElements.get(i);
            if (!el.visible || el.alpha <= 0.0f) continue;
            float cxPx = originX + el.anchorX * canvasW + el.offsetX * scale;
            float cyPx = originY + el.anchorY * canvasH + el.offsetY * scale;
            float halfW = Math.max(0.0f, el.width) * scale * 0.5f, halfH = Math.max(0.0f, el.height) * scale * 0.5f;
            float x0 = toNdcX(cxPx - halfW), x1 = toNdcX(cxPx + halfW);
            float y0 = toNdcY(cyPx - halfH), y1 = toNdcY(cyPx + halfH);

            if (el instanceof UIProgressBar bar) drawProgressBar(bar, x0, y0, x1, y1, scale);
            else if (el instanceof UIText text) drawText(text, x0, y0, x1, y1, scale);
            else if (el instanceof UIImage image) drawImage(image, x0, y0, x1, y1, scale);
            else if (el instanceof UIPanel panel) drawPanel(panel, x0, y0, x1, y1, scale);
        }
    }

    private float toNdcX(float px) { return rectX0 + px * ndcPerPxX; }
    private float toNdcY(float py) { return rectY0 + py * ndcPerPxY; }

    private void drawPanel(UIPanel p, float x0, float y0, float x1, float y1, float scale) {
        float radius = Math.max(0.0f, p.cornerRadius) * scale * ndcPerPxY;
        float bx = Math.max(0.0f, p.borderThickness) * scale * ndcPerPxX;
        float by = Math.max(0.0f, p.borderThickness) * scale * ndcPerPxY;
        if (bx > 0.0f && by > 0.0f) {
            UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, radius, rgb(p.borderR, p.borderG, p.borderB), p.alpha);
            x0 += bx; x1 -= bx; y0 += by; y1 -= by;
            radius = Math.max(0.0f, radius - by);
        }
        if (x1 > x0 && y1 > y0) {
            UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, radius, rgb(p.colorR, p.colorG, p.colorB), p.alpha);
        }
    }

    private void drawText(UIText t, float x0, float y0, float x1, float y1, float scale) {
        if (t.text == null || t.text.isEmpty()) return;
        float size = UIGeometryBatch.textSizeForEmPixels(buffer, Math.max(0.0f, t.fontSize) * scale);
        if (size <= 0.01f) return;
        float w = UIGeometryBatch.textWidth(buffer, t.text, size);
        float x = switch (t.alignment) {
            case LEFT -> x0;
            case RIGHT -> x1 - w;
            case CENTER -> (x0 + x1 - w) * 0.5f;
        };
        // addBoundedText centers the cap height 0.0075 * size below startY
        float startY = (y0 + y1) * 0.5f + 0.0075f * size;
        UIGeometryBatch.addBoundedText(buffer, t.text, x, startY, x + w + 0.001f, size, t.colorR, t.colorG, t.colorB, t.alpha);
    }

    private void drawImage(UIImage img, float x0, float y0, float x1, float y1, float scale) {
        float r = img.colorR, g = img.colorG, b = img.colorB, a = img.alpha;
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
        float rx = (x1 - x0) * 0.5f, ry = (y1 - y0) * 0.5f;
        switch (img.shape) {
            case RECT -> UIGeometryBatch.addQuad(buffer, x0, y0, x1, y1, r, g, b, a);
            case ROUNDED_RECT -> UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, Math.min(rx, ry) * 0.5f, rgb(r, g, b), a);
            case CIRCLE -> {
                for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
                    UIGeometryBatch.addTriangle(buffer, cx, cy,
                            cx + rx * CIRCLE_COS[i], cy + ry * CIRCLE_SIN[i],
                            cx + rx * CIRCLE_COS[i + 1], cy + ry * CIRCLE_SIN[i + 1], r, g, b, a);
                }
            }
            case RING -> {
                float irx = Math.max(0.0f, rx - img.thickness * scale * ndcPerPxX);
                float iry = Math.max(0.0f, ry - img.thickness * scale * ndcPerPxY);
                for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
                    float ox0 = cx + rx * CIRCLE_COS[i], oy0 = cy + ry * CIRCLE_SIN[i];
                    float ox1 = cx + rx * CIRCLE_COS[i + 1], oy1 = cy + ry * CIRCLE_SIN[i + 1];
                    float ix0 = cx + irx * CIRCLE_COS[i], iy0 = cy + iry * CIRCLE_SIN[i];
                    float ix1 = cx + irx * CIRCLE_COS[i + 1], iy1 = cy + iry * CIRCLE_SIN[i + 1];
                    UIGeometryBatch.addTriangle(buffer, ox0, oy0, ox1, oy1, ix0, iy0, r, g, b, a);
                    UIGeometryBatch.addTriangle(buffer, ox1, oy1, ix1, iy1, ix0, iy0, r, g, b, a);
                }
            }
            case CROSSHAIR -> {
                float tx = img.thickness * scale * ndcPerPxX * 0.5f, ty = img.thickness * scale * ndcPerPxY * 0.5f;
                float gx = img.gap * scale * ndcPerPxX, gy = img.gap * scale * ndcPerPxY;
                if (rx > gx) {
                    UIGeometryBatch.addQuad(buffer, cx + gx, cy - ty, cx + rx, cy + ty, r, g, b, a);
                    UIGeometryBatch.addQuad(buffer, cx - rx, cy - ty, cx - gx, cy + ty, r, g, b, a);
                }
                if (ry > gy) {
                    UIGeometryBatch.addQuad(buffer, cx - tx, cy + gy, cx + tx, cy + ry, r, g, b, a);
                    UIGeometryBatch.addQuad(buffer, cx - tx, cy - ry, cx + tx, cy - gy, r, g, b, a);
                }
            }
        }
    }

    private void drawProgressBar(UIProgressBar bar, float x0, float y0, float x1, float y1, float scale) {
        UIGeometryBatch.addQuad(buffer, x0, y0, x1, y1, bar.colorR, bar.colorG, bar.colorB, bar.alpha);
        float px = Math.max(0.0f, bar.padding) * scale * ndcPerPxX, py = Math.max(0.0f, bar.padding) * scale * ndcPerPxY;
        float ix0 = x0 + px, ix1 = x1 - px, iy0 = y0 + py, iy1 = y1 - py;
        float fraction = bar.fraction();
        if (ix1 <= ix0 || iy1 <= iy0 || fraction <= 0.0f) return;
        switch (bar.fillDirection) {
            case LEFT_TO_RIGHT -> ix1 = ix0 + (ix1 - ix0) * fraction;
            case RIGHT_TO_LEFT -> ix0 = ix1 - (ix1 - ix0) * fraction;
            case BOTTOM_TO_TOP -> iy1 = iy0 + (iy1 - iy0) * fraction;
            case TOP_TO_BOTTOM -> iy0 = iy1 - (iy1 - iy0) * fraction;
        }
        UIGeometryBatch.addQuad(buffer, ix0, iy0, ix1, iy1, bar.fillR, bar.fillG, bar.fillB, bar.alpha);
    }

    private static int rgb(float r, float g, float b) {
        int ri = Math.round(Math.max(0.0f, Math.min(1.0f, r)) * 255.0f);
        int gi = Math.round(Math.max(0.0f, Math.min(1.0f, g)) * 255.0f);
        int bi = Math.round(Math.max(0.0f, Math.min(1.0f, b)) * 255.0f);
        return (ri << 16) | (gi << 8) | bi;
    }
}
