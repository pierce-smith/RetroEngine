package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorPanelManager.DropdownMenu;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.EditorPanelManager.ModalState;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.List;

/**
 * EditorInputRouter routes high-level mouse clicks, cursor movement, and drag events
 * to dedicated region handlers (Gizmo, Inspector, AssetImport, TopBar, Hierarchy, Modal).
 */
public class EditorInputRouter {

    public enum PanelRegion { TOP_BAR, SCENE_HIERARCHY, VIEWPORT_3D, INSPECTOR, ASSET_DIRECTORY, MODAL_OVERLAY, NONE }

    private final GizmoInteractionHandler gizmoHandler = new GizmoInteractionHandler();
    private final InspectorInputHandler inspectorHandler = new InspectorInputHandler();
    private final AssetImportHandler assetHandler = new AssetImportHandler();
    private final TransformUndoTracker undoTracker = new TransformUndoTracker();

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;

    public PanelRegion determineRegion(double mouseX, double mouseY, EditorPanelManager manager) {
        if (manager.getActiveModal() != ModalState.NONE) return PanelRegion.MODAL_OVERLAY;
        if (manager.getActiveDropdownMenu() != DropdownMenu.NONE && mouseY >= 30.0 && mouseY <= 200.0) return PanelRegion.TOP_BAR;

        if (mouseY < 30.0) return PanelRegion.TOP_BAR;
        if (mouseY > 520.0) return PanelRegion.ASSET_DIRECTORY;
        if (mouseX < 280.0) return PanelRegion.SCENE_HIERARCHY;
        if (mouseX > 1000.0) return PanelRegion.INSPECTOR;
        return PanelRegion.VIEWPORT_3D;
    }

    public void handleMouseClick(double mouseX, double mouseY, int button, int action, EditorPanelManager manager) {
        if (button != 0) return; // Primary Left Click only

        if (action == 1) { // GLFW_PRESS
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;

            // 1. Modal Overlay Clicks
            if (manager.getActiveModal() == ModalState.ASSET_PICKER) {
                boolean handled = processModalClick(mouseX, mouseY, manager);
                if (handled) return;
            }

            // 2. Dropdown overlay active
            if (manager.getActiveDropdownMenu() != DropdownMenu.NONE) {
                boolean handled = processDropdownOverlayClick(mouseX, mouseY, manager.getActiveDropdownMenu(), manager);
                if (handled) {
                    manager.setActiveDropdownMenu(DropdownMenu.NONE);
                    return;
                }
            }

            PanelRegion region = determineRegion(mouseX, mouseY, manager);

            switch (region) {
                case TOP_BAR -> processTopBarClick(mouseX, mouseY, manager);
                case SCENE_HIERARCHY -> processHierarchyClick(mouseX, mouseY, manager);
                case VIEWPORT_3D -> {
                    boolean toolbarHit = processViewportToolbarClick(mouseX, mouseY, manager);
                    if (!toolbarHit) {
                        gizmoHandler.processViewportGizmoClick(mouseX, mouseY, manager, undoTracker);
                    }
                }
                case INSPECTOR -> inspectorHandler.processInspectorClick(mouseX, mouseY, 1000.0, manager, undoTracker);
                case ASSET_DIRECTORY -> assetHandler.processAssetDirectoryClick(mouseX, mouseY, manager);
                default -> {}
            }
        } else if (action == 0) { // GLFW_RELEASE
            if (manager.getActiveGizmoAxis() != GizmoAxis.NONE) {
                manager.setActiveGizmoAxis(GizmoAxis.NONE);
                undoTracker.commit(manager.getCommandHistory());
            }

            if (inspectorHandler.getActiveField() != InspectorInputHandler.InspectorField.NONE) {
                inspectorHandler.releaseField();
                undoTracker.commit(manager.getCommandHistory());
            }
        }
    }

    public boolean onCursorPos(double mouseX, double mouseY, EditorPanelManager manager) {
        double deltaX = mouseX - lastMouseX;
        double deltaY = mouseY - lastMouseY;
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        if (manager.getActiveGizmoAxis() != GizmoAxis.NONE) {
            return gizmoHandler.handleGizmoDrag(mouseX, mouseY, deltaX, deltaY, manager);
        }

        if (inspectorHandler.getActiveField() != InspectorInputHandler.InspectorField.NONE) {
            return inspectorHandler.handleFieldScrub(deltaX, manager);
        }

        return false;
    }

    private boolean processModalClick(double mouseX, double mouseY, EditorPanelManager manager) {
        double minX = 352.0, maxX = 928.0;
        double minY = 198.0, maxY = 522.0;

        // Background click outside modal window closes modal
        if (mouseX < minX || mouseX > maxX || mouseY < minY || mouseY > maxY) {
            manager.closeModal();
            return true;
        }

        // Close [X] Button (Top-Right)
        if (mouseX >= 870.0 && mouseX <= 915.0 && mouseY >= 198.0 && mouseY <= 230.0) {
            manager.closeModal();
            return true;
        }

        // [NONE / UNBIND] Button
        if (mouseX >= 367.0 && mouseX <= 600.0 && mouseY >= 233.0 && mouseY <= 270.0) {
            SceneEntity selected = manager.getSelectedEntity();
            if (selected != null) {
                if (manager.getPickerFilterType() == AssetType.TEXTURE) {
                    selected.setTextureAsset(null, "DEFAULT_CHECKER");
                    System.out.println("[AssetPicker] Unbound texture from selected entity.");
                } else if (manager.getPickerFilterType() == AssetType.MESH_3D) {
                    selected.setMeshAssetPath(null);
                    System.out.println("[AssetPicker] Unbound 3D mesh from selected entity.");
                }
            }
            manager.closeModal();
            return true;
        }

        // [IMPORT DISK...] Button
        if (mouseX >= 680.0 && mouseX <= 915.0 && mouseY >= 233.0 && mouseY <= 270.0) {
            manager.closeModal();
            if (manager.getPickerFilterType() == AssetType.TEXTURE) {
                promptImportTexture(manager);
            } else if (manager.getPickerFilterType() == AssetType.MESH_3D) {
                promptImportMesh(manager);
            }
            return true;
        }

        // Asset Directory Item List inside Modal
        List<AssetEntry> filtered = manager.getAssetDirectory().stream()
                .filter(a -> a.type == manager.getPickerFilterType())
                .toList();

        if (!filtered.isEmpty()) {
            double startItemY = 285.0;
            double itemHeight = 36.0;

            for (int i = 0; i < filtered.size(); i++) {
                double itemY = startItemY + (i * itemHeight);
                if (mouseY >= itemY && mouseY <= itemY + itemHeight) {
                    AssetEntry selectedAsset = filtered.get(i);
                    if (manager.getPickerFilterType() == AssetType.TEXTURE) {
                        manager.assignTextureToSelectedEntity(selectedAsset);
                    } else if (manager.getPickerFilterType() == AssetType.MESH_3D) {
                        manager.assignMeshToSelectedEntity(selectedAsset);
                    }
                    manager.closeModal();
                    return true;
                }
            }
        }

        return true;
    }

    private void promptImportTexture(EditorPanelManager manager) {
        String defaultDir = new File("Assets/Textures").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog(
                "Select Texture Asset", defaultDir, "Texture Images (*.png, *.jpg, *.tga)", "png", "jpg", "tga"
        );
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            File file = new File(importedPath);
            AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.TEXTURE);
            manager.onFilesDropped(List.of(importedPath));
            manager.assignTextureToSelectedEntity(newAsset);
            System.out.println("[AssetPicker] Imported & Bound Texture: " + file.getName());
        }
    }

    private void promptImportMesh(EditorPanelManager manager) {
        String defaultDir = new File("Assets/Models").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog(
                "Select 3D Mesh Asset", defaultDir, "3D Models (*.obj, *.fbx, *.gltf)", "obj", "fbx", "gltf", "glb"
        );
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            File file = new File(importedPath);
            AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.MESH_3D);
            manager.onFilesDropped(List.of(importedPath));
            manager.assignMeshToSelectedEntity(newAsset);
            System.out.println("[AssetPicker] Imported & Bound 3D Mesh: " + file.getName());
        }
    }

    private void processTopBarClick(double mouseX, double mouseY, EditorPanelManager manager) {
        if (mouseX >= 170.0 && mouseX <= 245.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.FILE) ? DropdownMenu.NONE : DropdownMenu.FILE;
            manager.setActiveDropdownMenu(next);
        } else if (mouseX >= 250.0 && mouseX <= 315.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.EDIT) ? DropdownMenu.NONE : DropdownMenu.EDIT;
            manager.setActiveDropdownMenu(next);
        } else if (mouseX >= 320.0 && mouseX <= 395.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.ENTITY) ? DropdownMenu.NONE : DropdownMenu.ENTITY;
            manager.setActiveDropdownMenu(next);
        } else if (mouseX >= 400.0 && mouseX <= 465.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.VIEW) ? DropdownMenu.NONE : DropdownMenu.VIEW;
            manager.setActiveDropdownMenu(next);
        } else if (mouseX >= 540.0 && mouseX <= 625.0) {
            manager.play();
        } else if (mouseX >= 633.0 && mouseX <= 710.0) {
            manager.pause();
        } else if (mouseX >= 716.0 && mouseX <= 787.0) {
            manager.stop();
        }
    }

    private boolean processDropdownOverlayClick(double mouseX, double mouseY, DropdownMenu menu, EditorPanelManager manager) {
        double minX = 175.0;
        if (menu == DropdownMenu.EDIT) minX = 250.0;
        else if (menu == DropdownMenu.ENTITY) minX = 320.0;
        else if (menu == DropdownMenu.VIEW) minX = 400.0;

        double maxX = minX + 210.0;

        if (mouseX >= minX && mouseX <= maxX && mouseY >= 30.0 && mouseY <= 180.0) {
            int itemIndex = (int) ((mouseY - 30.0) / 32.0);

            if (menu == DropdownMenu.FILE) {
                if (itemIndex == 0) manager.newScene();
                else if (itemIndex == 1) manager.promptOpenSceneWithNativeDialog();
                else if (itemIndex == 2) manager.promptSaveSceneWithNativeDialog();
            } else if (menu == DropdownMenu.EDIT) {
                if (itemIndex == 0) manager.undo();
                else if (itemIndex == 1) manager.redo();
                else if (itemIndex == 2) manager.deleteSelectedEntity();
            } else if (menu == DropdownMenu.ENTITY) {
                if (itemIndex == 0) manager.spawnDefaultCube();
                else if (itemIndex == 1) manager.spawnLightSource();
            } else if (menu == DropdownMenu.VIEW) {
                if (itemIndex == 1) {
                    manager.toggle3DGrid();
                }
            }
            return true;
        }
        return false;
    }

    private void processHierarchyClick(double mouseX, double mouseY, EditorPanelManager manager) {
        List<SceneEntity> entities = manager.getSceneEntities();
        double startY = 75.0;
        double rowHeight = 28.0;

        int index = (int) ((mouseY - startY) / rowHeight);
        if (index >= 0 && index < entities.size()) {
            manager.selectEntity(entities.get(index));
        } else {
            manager.selectEntity(null);
        }
    }

    private boolean processViewportToolbarClick(double mouseX, double mouseY, EditorPanelManager manager) {
        if (mouseY >= 72.0 && mouseY <= 115.0) {
            if (mouseX >= 307.0 && mouseX <= 390.0) {
                manager.setCurrentGizmoMode(GizmoMode.TRANSLATE);
                return true;
            } else if (mouseX >= 396.0 && mouseX <= 480.0) {
                manager.setCurrentGizmoMode(GizmoMode.ROTATE);
                return true;
            } else if (mouseX >= 486.0 && mouseX <= 570.0) {
                manager.setCurrentGizmoMode(GizmoMode.SCALE);
                return true;
            }
        }
        return false;
    }

    public GizmoInteractionHandler getGizmoHandler() { return gizmoHandler; }
    public InspectorInputHandler getInspectorHandler() { return inspectorHandler; }
    public AssetImportHandler getAssetHandler() { return assetHandler; }
    public TransformUndoTracker getUndoTracker() { return undoTracker; }
}
