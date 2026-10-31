package com.retro.engine.editor.input;

import com.retro.engine.editor.ui.panels.InspectorPanel;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.ui.panels.AssetBrowserPanel;
import com.retro.engine.editor.ui.panels.HierarchyPanel;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.panels.MenuItem;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.cooker.RpackCooker;
import com.retro.engine.assets.cooker.RpackCookerCli;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AssetBrowserInputDelegate {

    // --- Hierarchy press / drag-and-drop state ---
    private static final float HIERARCHY_DRAG_THRESHOLD = 0.025f; // design-space Y travel before a press becomes a drag
    private static Entity hierarchyPressEntity = null;
    private static float hierarchyPressY = 0.0f;
    private static float hierarchyDragY = 0.0f;
    private static boolean hierarchyDragging = false;
    private static boolean hierarchyPendingToggle = false;
    private static boolean draggingHierarchyScrollbar = false;
    private static float hierarchyScrollbarGrabOffset = 0.0f;

    /**
     * Press in the Hierarchy (design space). Selects the row under the cursor, or arms an expand/collapse toggle when
     * the row is already selected; the toggle fires on release only if the press never turned into a drag.
     */
    public static void processHierarchyClick(float ndcX, float ndcY, EditorWorkspace manager) {
        resetHierarchyPress();

        // Scrollbar: grab the thumb, or jump-to-click on the track, then drag
        if (HierarchyPanel.inScrollbar(ndcX, ndcY)) {
            float top = HierarchyPanel.thumbTop();
            float h = HierarchyPanel.thumbHeight();
            if (ndcY > top || ndcY < top - h) {
                HierarchyPanel.setThumbTop(ndcY + h * 0.5f);
                top = HierarchyPanel.thumbTop();
            }
            hierarchyScrollbarGrabOffset = top - ndcY;
            draggingHierarchyScrollbar = true;
            return;
        }

        List<Entity> entities = manager.getSceneEntities();
        if (entities == null || entities.isEmpty()) return;

        List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
        if (visibleTree.isEmpty()) return;

        int index = HierarchyPanel.rowAt(ndcY, visibleTree.size());
        if (index >= 0) {
            HierarchyPanel.HierarchyNode node = visibleTree.get(index);
            Entity clickedEntity = node.entity;
            UISoundPlayer.play(UISound.CLICK);
            if (node.hasChildren && manager.getSelectedEntity() == clickedEntity) {
                hierarchyPendingToggle = true;
            } else {
                manager.selectEntity(clickedEntity);
            }
            hierarchyPressEntity = clickedEntity;
            hierarchyPressY = ndcY;
            hierarchyDragY = ndcY;
        } else if (ndcY <= HierarchyPanel.VIEW_TOP && ndcY >= HierarchyPanel.VIEW_BOTTOM) {
            manager.selectEntity(null);
        }
    }

    /** Cursor move in Hierarchy design space; drives scrollbar drags and row drag-and-drop. */
    public static boolean processHierarchyDrag(float ndcY) {
        if (draggingHierarchyScrollbar) {
            HierarchyPanel.setThumbTop(ndcY + hierarchyScrollbarGrabOffset);
            return true;
        }
        if (hierarchyPressEntity == null) return false;

        hierarchyDragY = ndcY;
        if (!hierarchyDragging && Math.abs(ndcY - hierarchyPressY) > HIERARCHY_DRAG_THRESHOLD) {
            hierarchyDragging = true;
            hierarchyPendingToggle = false;
        }
        if (hierarchyDragging) {
            HierarchyPanel.setDrag(hierarchyPressEntity, ndcY);
        }
        return true;
    }

    /** Mouse wheel over the Hierarchy: same rate as the Inspector and Asset Browser. */
    public static boolean handleHierarchyScroll(double yOffset) {
        HierarchyPanel.scroll((float) -yOffset * 0.18f);
        if (hierarchyDragging) HierarchyPanel.setDrag(hierarchyPressEntity, hierarchyDragY);
        return true;
    }

    private static void processHierarchyRelease(EditorWorkspace manager) {
        if (manager != null && hierarchyPressEntity != null) {
            if (hierarchyDragging) {
                List<Entity> entities = manager.getSceneEntities();
                List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
                applyHierarchyDrop(manager, hierarchyPressEntity, HierarchyPanel.computeDrop(hierarchyDragY, hierarchyPressEntity, visibleTree, entities));
            } else if (hierarchyPendingToggle) {
                hierarchyPressEntity.expandedInHierarchy = !hierarchyPressEntity.expandedInHierarchy;
            }
        }
        resetHierarchyPress();
    }

    private static void resetHierarchyPress() {
        hierarchyPressEntity = null;
        hierarchyDragging = false;
        hierarchyPendingToggle = false;
        draggingHierarchyScrollbar = false;
        HierarchyPanel.clearDrag();
    }

    /**
     * Commits a drop as one undoable move: re-parent (cycle-checked at any depth) plus a scene-list reorder, since
     * list order is sibling order in the tree and in saved .rscene files.
     */
    private static void applyHierarchyDrop(EditorWorkspace manager, Entity source, HierarchyPanel.DropTarget drop) {
        if (drop.kind == HierarchyPanel.DropKind.NONE || !drop.valid) return;
        List<Entity> entities = manager.getSceneEntities();
        int from = entities.indexOf(source);
        if (from < 0) return;

        Entity oldParent = HierarchyPanel.findEntity(entities, source.parentId);
        Entity newParent;
        int toIndex;
        switch (drop.kind) {
            case INTO -> {
                newParent = drop.target;
                toIndex = entities.size() - 1; // last among its new siblings
            }
            case ROOT_END -> {
                newParent = null;
                toIndex = entities.size() - 1;
            }
            default -> {
                newParent = drop.newParent;
                int targetIndex = entities.indexOf(drop.target);
                if (targetIndex < 0) return;
                // moveEntity removes the source first, which shifts later indices down by one
                int before = (from < targetIndex) ? targetIndex - 1 : targetIndex;
                toIndex = (drop.kind == HierarchyPanel.DropKind.AFTER) ? before + 1 : before;
            }
        }

        if (!HierarchyPanel.isCycleSafeParent(source, newParent, entities)) return;
        if (newParent == oldParent && toIndex == from) return;

        if (newParent != null) newParent.expandedInHierarchy = true;
        manager.getCommandHistory().executeCommand(new EditorCommands.HierarchyMoveCommand(manager, source, oldParent, from, newParent, toIndex));
        manager.selectEntity(source);
        UISoundPlayer.play(UISound.CLICK);
    }

    public static void processAssetDirectoryClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;

        // 1. Header Buttons
        // [+ NEW FOLDER] Button
        if (ndcX >= AssetBrowserPanel.NEW_FOLDER_X0 && ndcX <= AssetBrowserPanel.NEW_FOLDER_X1 && ndcY >= AssetBrowserPanel.HEADER_Y0 && ndcY <= AssetBrowserPanel.HEADER_Y1) {
            UISoundPlayer.play(UISound.CLICK);
            manager.getAssetDatabase().createNewFolder();
            return;
        }

        // [+ IMPORT ASSET] Button
        if (ndcX >= AssetBrowserPanel.IMPORT_X0 && ndcX <= AssetBrowserPanel.IMPORT_X1 && ndcY >= AssetBrowserPanel.HEADER_Y0 && ndcY <= AssetBrowserPanel.HEADER_Y1) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            InspectorInputDelegate.promptImportAssetForDirectory(manager);
            return;
        }

        // Scrollbar: grab the thumb, or jump-to-click on the track, then drag
        if (AssetBrowserPanel.inScrollbar(ndcX, ndcY)) {
            float top = AssetBrowserPanel.thumbTop();
            float h = AssetBrowserPanel.thumbHeight();
            if (ndcY > top || ndcY < top - h) {
                AssetBrowserPanel.setThumbTop(ndcY + h * 0.5f);
                top = AssetBrowserPanel.thumbTop();
            }
            scrollbarGrabOffset = top - ndcY;
            draggingScrollbar = true;
            return;
        }

        String activePath = manager.getAssetDatabase().getCurrentFolderPath();
        List<AssetMeta> visibleAssets = visibleAssets(manager.getAssetDatabase());

        boolean hasUpLevel = !activePath.isEmpty();
        if (visibleAssets.isEmpty() && !hasUpLevel) {
            return;
        }

        // 2. Click specific Asset or Folder Cards (grid slot under cursor, scroll offset applied)
        int slot = AssetBrowserPanel.slotAt(ndcX, ndcY, visibleAssets.size() + (hasUpLevel ? 1 : 0));
        if (slot < 0) return;

        // Check if clicked the Up Level card
        if (hasUpLevel) {
            if (slot == 0) {
                UISoundPlayer.play(UISound.CLICK);
                manager.getAssetDatabase().navigateUpFolder();
                return;
            }
            slot--;
        }

        AssetMeta asset = visibleAssets.get(slot);
        if (asset.type == AssetType.FOLDER) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String newPath = activePath.isEmpty() ? asset.name : activePath + "/" + asset.name;
            manager.getAssetDatabase().setCurrentFolderPath(newPath);
        } else if (asset.type == AssetType.PREFAB) {
            UISoundPlayer.play(UISound.SPAWN);
            List<Entity> spawned = manager.getSceneManager().instantiatePrefab(asset.absolutePath, manager.getAssetDatabase());
            if (spawned != null && !spawned.isEmpty()) {
                manager.getAssetDatabase().addAssetDirectoryIfNotExists(asset.absolutePath, AssetType.PREFAB);
            }
        } else if (asset.type == AssetType.MESH_3D) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.getSceneManager().spawnEntityFromAsset(asset);
            Entity newlySpawned = manager.getSelectedEntity();
            if (newlySpawned != null) {
                EditorCommands.EntitySpawnCommand cmd = new EditorCommands.EntitySpawnCommand(manager, newlySpawned);
                manager.getCommandHistory().executeCommand(cmd);
            }
        } else if (asset.type == AssetType.TEXTURE) {
            UISoundPlayer.play(UISound.CLICK);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                Material mat = selected.getComponent(Material.class);
                if (mat != null) {
                    try {
                        Field field = Material.class.getDeclaredField("textureAssetPath");
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mat, field, mat.textureAssetPath, asset.absolutePath, "TEXTURE");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } else if (asset.type == AssetType.SCENE) {
            // Scene loads swap entities only; the project asset registry is left untouched
            UISoundPlayer.play(UISound.SPAWN);
            manager.getSceneManager().loadSceneFromFile(asset.absolutePath, manager.getAssetDatabase());
        } else if (asset.type == AssetType.SCRIPT) {
            UISoundPlayer.play(UISound.CLICK);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                ScriptComponent sc = selected.getComponent(ScriptComponent.class);
                String oldPath = (sc != null) ? sc.scriptAssetPath : null;
                String oldName = (sc != null) ? sc.scriptName : null;
                EditorCommands.ScriptAssignCommand cmd = new EditorCommands.ScriptAssignCommand(selected, oldPath, oldName, asset.absolutePath, asset.name);
                manager.getCommandHistory().executeCommand(cmd);
                InspectorPanel.resetScroll();
            }
        }
    }

    private static boolean draggingScrollbar = false;
    private static float scrollbarGrabOffset = 0.0f;

    /** Mouse wheel over the Asset Browser: smooth scroll, same rate as the Inspector. */
    public static boolean handleScroll(double yOffset) {
        AssetBrowserPanel.scroll((float) -yOffset * 0.18f);
        return true;
    }

    /** Cursor move in Asset Browser design space; drives an active scrollbar drag. */
    public static boolean processDrag(float ndcY) {
        if (!draggingScrollbar) return false;
        AssetBrowserPanel.setThumbTop(ndcY + scrollbarGrabOffset);
        return true;
    }

    public static void onMouseRelease(EditorWorkspace manager) {
        draggingScrollbar = false;
        processHierarchyRelease(manager);
    }

    /** Files and folders in the browser's active folder, in grid order (after the Up Level card, when shown). */
    private static List<AssetMeta> visibleAssets(AssetDatabase db) {
        String activePath = db.getCurrentFolderPath();
        List<AssetMeta> assetDirectory = db.getAssetDirectory();
        return (assetDirectory != null) ? assetDirectory.stream()
                .filter(a -> a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath))
                .toList() : List.of();
    }

    // --- Right-click context menu (OverlaySystem routes the press/release and opens the ContextMenuOverlay) ---

    /**
     * Asset card under the cursor (design space, scroll applied), or null over empty space, the header, the
     * scrollbar, or the Up Level card. Uses the same grid as {@link #processAssetDirectoryClick}.
     */
    public static AssetMeta assetAt(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null || AssetBrowserPanel.inScrollbar(ndcX, ndcY)) return null;
        AssetDatabase db = manager.getAssetDatabase();
        List<AssetMeta> visibleAssets = visibleAssets(db);
        boolean hasUpLevel = !db.getCurrentFolderPath().isEmpty();

        int slot = AssetBrowserPanel.slotAt(ndcX, ndcY, visibleAssets.size() + (hasUpLevel ? 1 : 0));
        if (hasUpLevel) slot--;
        return (slot >= 0) ? visibleAssets.get(slot) : null;
    }

    /** Menu for empty Asset Browser space. */
    public static List<MenuItem> backgroundMenuItems(EditorWorkspace manager) {
        AssetDatabase db = manager.getAssetDatabase();
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("+ Create Java Script", () -> promptCreateScript(manager)));
        items.add(new MenuItem("+ New Folder", db::createNewFolder));
        items.add(new MenuItem("+ Import Asset...", () -> InspectorInputDelegate.promptImportAssetForDirectory(manager)));
        items.add(new MenuItem("Refresh Browser", db::rescanProjectAssets));
        if (!db.getCurrentFolderPath().isEmpty()) {
            items.add(new MenuItem(".. Up Level (" + db.getCurrentFolderPath() + ")", db::navigateUpFolder));
        }
        return items;
    }

    /** Entries shared by every file card, appended after its type-specific actions. */
    public static void addFileMenuItems(List<MenuItem> items, AssetMeta asset, EditorWorkspace manager) {
        items.add(new MenuItem(revealLabel(), () -> revealInFileManager(asset)));
        if (isCookable(asset)) {
            items.add(new MenuItem("Re-cook Asset", () -> recookAsset(manager, asset)));
        }
        items.add(new MenuItem("Delete Asset", () -> manager.getAssetDatabase().removeAssetFromDirectory(asset)));
    }

    public static String revealLabel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (PlatformShortcuts.IS_MAC) return "Show in Finder";
        return os.contains("win") ? "Show in Explorer" : "Show in File Manager";
    }

    /** Opens the OS file manager with the asset selected (macOS / Windows) or its containing folder (Linux). */
    public static void revealInFileManager(AssetMeta asset) {
        if (asset == null || asset.absolutePath == null) return;
        File file = new File(asset.absolutePath).getAbsoluteFile();
        if (!file.exists()) {
            NativeFileDialog.showError(revealLabel(), "File not found on disk:\n" + file.getPath());
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (PlatformShortcuts.IS_MAC) {
            pb = new ProcessBuilder("open", "-R", file.getPath());
        } else if (os.contains("win")) {
            pb = new ProcessBuilder("explorer.exe", "/select,", file.getPath());
        } else {
            pb = new ProcessBuilder("xdg-open", file.getParentFile().getPath());
        }
        try {
            pb.start();
        } catch (IOException e) {
            NativeFileDialog.showError(revealLabel(), "Could not open the file manager: " + e.getMessage());
        }
    }

    private static boolean isCookable(AssetMeta asset) {
        return asset.type == AssetType.MESH_3D || asset.type == AssetType.TEXTURE;
    }

    /**
     * Rebuilds the asset's .rpack in the project's Cooked/ mirror (the layout StandaloneExporter and runtime builds
     * use), keeping its registry UUID. Runs off the main thread since mesh imports can take seconds.
     */
    public static void recookAsset(EditorWorkspace manager, AssetMeta asset) {
        AssetDatabase db = manager.getAssetDatabase();
        if (!db.hasActiveProject() || asset == null || !isCookable(asset)) return;
        File assetsRoot = new File(db.getProjectAssetsPath()).getAbsoluteFile();
        File raw = new File(asset.absolutePath).getAbsoluteFile();
        if (!raw.toPath().normalize().startsWith(assetsRoot.toPath().normalize())) return;
        File out = RpackCookerCli.cookedPathFor(assetsRoot, new File(db.getActiveProjectPath(), AssetDatabase.COOKED_DIR), raw);
        boolean mesh = asset.type == AssetType.MESH_3D;
        String uuid = asset.uuid;

        Thread worker = new Thread(() -> {
            out.getParentFile().mkdirs();
            boolean ok = mesh
                    ? RpackCooker.cookMeshToRpack(raw.getPath(), out.getPath(), uuid)
                    : RpackCooker.cookTextureToRpack(raw.getPath(), out.getPath(), uuid);
            if (ok) {
                System.out.println("[AssetBrowser] Re-cooked " + asset.name + " -> " + out.getPath());
            } else {
                System.err.println("[AssetBrowser] Re-cook failed for " + asset.name);
            }
        }, "AssetRecook-" + asset.name);
        worker.setDaemon(true);
        worker.start();
    }

    /** Asks for a class name and writes an EntityScript boilerplate into Assets/Scripts/, then shows that folder. */
    public static void promptCreateScript(EditorWorkspace manager) {
        AssetDatabase db = manager.getAssetDatabase();
        if (!db.hasActiveProject()) return;
        String input = NativeFileDialog.promptInput("Create Java Script", "Enter script class name:", "NewScript");
        if (input == null) return;
        String className = input.trim();
        if (className.toLowerCase(Locale.ROOT).endsWith(".java")) className = className.substring(0, className.length() - 5);
        if (!isValidClassName(className)) {
            NativeFileDialog.showError("Create Java Script", "'" + className + "' is not a valid script class name (letters, digits and _ only, starting with an uppercase letter).");
            return;
        }

        File scriptsDir = new File(db.getProjectAssetsPath(), SCRIPTS_FOLDER);
        File target = new File(scriptsDir, className + ".java");
        if (target.exists()) {
            NativeFileDialog.showError("Create Java Script", "A script named " + target.getName() + " already exists.");
            return;
        }
        try {
            Files.createDirectories(scriptsDir.toPath());
            Files.writeString(target.toPath(), scriptBoilerplate(className), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            NativeFileDialog.showError("Create Java Script", "Could not write script: " + e.getMessage());
            return;
        }
        UISoundPlayer.play(UISound.SPAWN);
        db.rescanProjectAssets();
        db.setCurrentFolderPath(SCRIPTS_FOLDER);
        System.out.println("[AssetBrowser] Created script Assets/" + SCRIPTS_FOLDER + "/" + target.getName());
    }

    private static final String SCRIPTS_FOLDER = "Scripts";

    /** Plain Java identifier starting with an uppercase letter (keywords are lowercase, so they can't match). */
    private static boolean isValidClassName(String name) {
        if (name.isEmpty() || !Character.isUpperCase(name.charAt(0))) return false;
        for (char c : name.toCharArray()) {
            if (!Character.isJavaIdentifierPart(c) || c == '$') return false;
        }
        return true;
    }

    private static String scriptBoilerplate(String className) {
        return """
                package com.retro.engine.scripting.user;

                import com.retro.engine.scripting.EntityScript;

                public class %s extends EntityScript {

                    @Override
                    public void onStart() {
                    }

                    @Override
                    public void onUpdate(float deltaTime) {
                    }
                }
                """.formatted(className);
    }
}
