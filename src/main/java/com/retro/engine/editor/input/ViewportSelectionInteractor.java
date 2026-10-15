package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;

import java.util.List;

/**
 * ViewportSelectionInteractor encapsulating Option A Screen-Space Entity Selection
 * and Viewport Gizmo Proximity Protection to prevent accidental deselections.
 */
public class ViewportSelectionInteractor {

    /**
     * Option A: Screen-Space Distance Entity Selection (Fast, Zero GC Overhead).
     */
    public static SceneEntity pickViewportEntity(double refX, double refY, EditorPanelManager manager) {
        if (manager == null) return null;
        List<SceneEntity> entities = manager.getSceneEntities();
        if (entities == null || entities.isEmpty()) return null;

        SceneEntity closestEntity = null;
        double minSqDist = Double.MAX_VALUE;
        double baseThreshold = 48.0; // Dynamic hit radius in pixels
        double baseThresholdSq = baseThreshold * baseThreshold;

        for (SceneEntity entity : entities) {
            TransformComponent tc = entity.getTransform();
            if (tc == null) continue;

            float[] p = GizmoInteractor.projectWorldToNdc(tc.posX, tc.posY, tc.posZ, manager);
            double cx = ((p[0] + 1.0) / 2.0) * 1280.0;
            double cy = ((1.0 - p[1]) / 2.0) * 720.0;

            double dx = refX - cx;
            double dy = refY - cy;
            double sqDist = dx * dx + dy * dy;

            double maxScale = Math.max(Math.abs(tc.scaleX), Math.max(Math.abs(tc.scaleY), Math.abs(tc.scaleZ)));
            double scaleMultiplier = Math.max(0.6, Math.min(2.5, maxScale));
            double effectiveThresholdSq = baseThresholdSq * scaleMultiplier;

            if (sqDist <= effectiveThresholdSq && sqDist < minSqDist) {
                minSqDist = sqDist;
                closestEntity = entity;
            }
        }
        return closestEntity;
    }

    /**
     * Gizmo Proximity Protection: Checks if a click occurred near the active entity's gizmo center.
     */
    public static boolean isNearGizmoCenter(double refX, double refY, SceneEntity selectedEntity, EditorPanelManager manager, double protectiveRadiusPx) {
        if (selectedEntity == null || manager == null) return false;
        TransformComponent tc = selectedEntity.getTransform();
        if (tc == null) return false;

        float[] p = GizmoInteractor.projectWorldToNdc(tc.posX, tc.posY, tc.posZ, manager);
        double cx = ((p[0] + 1.0) / 2.0) * 1280.0;
        double cy = ((1.0 - p[1]) / 2.0) * 720.0;

        double dx = refX - cx;
        double dy = refY - cy;
        return (dx * dx + dy * dy) <= (protectiveRadiusPx * protectiveRadiusPx);
    }
}
