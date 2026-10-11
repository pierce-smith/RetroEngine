package com.retro.engine.editor;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.commands.CommandHistory;
import com.retro.engine.editor.commands.TransformCommand;
import com.retro.engine.serialization.SceneSerializer;

import java.io.File;
import java.util.*;

/**
 * EditorPanelManager orchestrates the editor panel layout, top menu dropdowns,
 * modal dialog overlays (Unity-Style Asset Pickers), command history (Undo/Redo),
 * and component-based Scene Entity management.
 * Target JDK: JDK 21+ / JDK 25 / macOS
 */
public class EditorPanelManager {
    public enum AssetType { MESH_3D, TEXTURE, SHADER, SCENE, UNKNOWN }
    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER }

    public static class SceneEntity {
        public String id;
        public String name;
        public boolean selected = false;

        private final Map<Class<? extends Component>, Component> components = new LinkedHashMap<>();

        public SceneEntity(String id, String name, String meshAssetPath) {
            this.id = id;
            this.name = name;

            TransformComponent tc = new TransformComponent();
            addComponent(tc);

            if (meshAssetPath != null) {
                addComponent(new MeshRendererComponent(meshAssetPath));
            }
            addComponent(new MaterialComponent());
        }

        @SuppressWarnings("unchecked")
        public <T extends Component> T getComponent(Class<T> type) {
            return (T) components.get(type);
        }

        public void addComponent(Component component) {
            if (component != null) {
                components.put(component.getClass(), component);
            }
        }

        public void removeComponent(Class<? extends Component> type) {
            components.remove(type);
        }

        public Collection<Component> getAllComponents() {
            return Collections.unmodifiableCollection(components.values());
        }

        public TransformComponent getTransform() {
            TransformComponent tc = getComponent(TransformComponent.class);
            if (tc == null) {
                tc = new TransformComponent();
                addComponent(tc);
            }
            return tc;
        }

        public String getMeshAssetPath() {
            MeshRendererComponent mc = getComponent(MeshRendererComponent.class);
            return mc != null ? mc.meshAssetPath : null;
        }

        public String getTextureAssetPath() {
            MaterialComponent mat = getComponent(MaterialComponent.class);
            return mat != null ? mat.textureAssetPath : null;
        }

        public String getTextureName() {
            MaterialComponent mat = getComponent(MaterialComponent.class);
            return mat != null ? mat.textureName : "DEFAULT_CHECKER";
        }

        public void setTextureAsset(String path, String name) {
            MaterialComponent mat = getComponent(MaterialComponent.class);
            if (mat == null) {
                mat = new MaterialComponent();
                addComponent(mat);
            }
            mat.textureAssetPath = path;
            mat.textureName = name;
        }

        public void setMeshAssetPath(String path) {
            MeshRendererComponent mc = getComponent(MeshRendererComponent.class);
            if (mc == null) {
                mc = new MeshRendererComponent(path);
                addComponent(mc);
            } else {
                mc.meshAssetPath = path;
            }
        }
    }

    public static class AssetEntry {
        public String name;
        public String absolutePath;
        public AssetType type;

        public AssetEntry(String name, String absolutePath, AssetType type) {
            this.name = name;
            this.absolutePath = absolutePath;
            this.type = type;
        }
    }

    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private final List<AssetEntry> assetDirectory = new ArrayList<>();
    private SceneEntity selectedEntity = null;

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;
    private DropdownMenu activeDropdownMenu = DropdownMenu.NONE;

    // Modal Overlays
    private ModalState activeModal = ModalState.NONE;
    private AssetType pickerFilterType = AssetType.TEXTURE;

    private final CommandHistory commandHistory = new CommandHistory();

    private boolean show3DGrid = true;
    private float[] cached3DGridVertices = null;
    private float[] cachedUiVertices = null;

    private String activeScenePath = "Assets/Scenes/MainLevel.scene";

    // Viewport Camera parameters
    private float camPanX = 0.0f;
    private float camPanY = -1.2f;
    private float camZoom = -8.5f;
    private float camPitch = 22.0f;
    private float camYaw = -35.0f;

    public EditorPanelManager() {}

    public boolean is3DGridVisible() { return show3DGrid; }
    public void toggle3DGrid() {
        this.show3DGrid = !this.show3DGrid;
        System.out.println("[View] Toggle 3D Grid -> " + (this.show3DGrid ? "VISIBLE" : "HIDDEN"));
    }

    public CommandHistory getCommandHistory() { return commandHistory; }

    public void undo() { commandHistory.undo(); }
    public void redo() { commandHistory.redo(); }

    public void newScene() {
        sceneEntities.clear();
        selectedEntity = null;
        activeScenePath = "Assets/Scenes/NewLevel.scene";
        System.out.println("[EditorPanelManager] Created New Empty Scene.");
    }

    public void promptOpenSceneWithNativeDialog() {
        String path = NativeFileDialog.openFileDialog("Open Retro Engine Scene", activeScenePath, "Retro Scene (*.scene)", "scene");
        if (path != null && !path.trim().isEmpty()) {
            loadSceneFromFile(path);
        }
    }

    public void promptSaveSceneWithNativeDialog() {
        String path = NativeFileDialog.saveFileDialog("Save Retro Engine Scene", activeScenePath, "Retro Scene (*.scene)", "scene");
        if (path != null && !path.trim().isEmpty()) {
            if (!path.endsWith(".scene")) path += ".scene";
            saveActiveScene(path);
        }
    }

    public void saveActiveScene() { saveActiveScene(this.activeScenePath); }

    public void saveActiveScene(String filePath) {
        if (filePath != null) this.activeScenePath = filePath;
        boolean ok = SceneSerializer.saveScene(this.activeScenePath, this.sceneEntities);
        if (ok) {
            System.out.println("[EditorPanelManager] Scene saved to: " + this.activeScenePath);
        }
    }

    public void loadSceneFromFile(String filePath) {
        if (filePath == null) return;
        List<SceneEntity> loaded = SceneSerializer.loadScene(filePath);
        if (!loaded.isEmpty()) {
            this.sceneEntities.clear();
            this.sceneEntities.addAll(loaded);
            this.selectedEntity = sceneEntities.get(0);

            for (int i = 0; i < sceneEntities.size(); i++) {
                sceneEntities.get(i).selected = (i == 0);
            }
            this.activeScenePath = filePath;
            System.out.println("[EditorPanelManager] Active scene loaded -> " + filePath);
        }
    }

    public void deleteSelectedEntity() {
        if (selectedEntity != null) {
            String name = selectedEntity.name;
            sceneEntities.remove(selectedEntity);
            selectedEntity = sceneEntities.isEmpty() ? null : sceneEntities.get(0);
            if (selectedEntity != null) selectedEntity.selected = true;
            System.out.println("[EditorPanelManager] Deleted Entity: " + name);
        }
    }

    public void spawnDefaultCube() {
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = "Cube_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, "Assets/Models/cube.obj");
        sceneEntities.add(entity);
        selectEntity(entity);
        System.out.println("[EditorPanelManager] Spawned 3D Cube Entity: " + name);
    }

    public void spawnLightSource() {
        String entityId = "light_" + (sceneEntities.size() + 1);
        String name = "DirectionalLight_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, null);
        entity.addComponent(new LightComponent());
        sceneEntities.add(entity);
        selectEntity(entity);
        System.out.println("[EditorPanelManager] Spawned Light Source Entity: " + name);
    }

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX;
        this.camPanY = panY;
        this.camZoom = zoom;
        this.camPitch = pitch;
        this.camYaw = yaw;
    }

    public float[] projectWorldToNdc(float wx, float wy, float wz) {
        float aspect = 712.0f / 488.0f;
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));

        float p00 = f / aspect;
        float p11 = f;

        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        float vx = cy * wx - sy * wz + camPanX;
        float vy = sy * sp * wx + cp * wy + cy * sp * wz + camPanY;
        float vz = sy * cp * wx - sp * wy + cy * cp * wz + camZoom;

        float clipW = -vz;
        if (clipW <= 0.001f) clipW = 0.001f;

        float ndcX = (p00 * vx) / clipW;
        float ndcY = (p11 * vy) / clipW;

        float uiX = 0.0f + ndcX * 0.55f;
        float uiY = 0.19f + ndcY * 0.69f;

        return new float[] { uiX, uiY };
    }

    public void update(float deltaTime) {}

    public float[] get3DGridVertices() {
        if (!show3DGrid) return new float[0];
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) {
            cached3DGridVertices = generate3DGridVertices();
        }
        return cached3DGridVertices;
    }

    public int get3DGridVertexCount() {
        if (!show3DGrid) return 0;
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) get3DGridVertices();
        return cached3DGridVertices.length / 9;
    }

    public float[] generate3DGridVertices() {
        List<Float> verts = new ArrayList<>();
        int extent = 15;
        float y = 0.0f;

        // Baseline grid generation:
        // 1. Z-constant lines running along X axis ((-extent, y, z) to (extent, y, z))
        for (int z_i = -extent; z_i <= extent; z_i++) {
            float z = (float) z_i;
            float r = 0.22f, g = 0.22f, b = 0.28f, a = 0.8f;
            if (z_i == 0) { r = 1.0f; g = 0.25f; b = 0.25f; a = 1.0f; } // RED for X Axis (Z=0 line)

            addGridLine(verts, -extent, y, z, extent, y, z, r, g, b, a);
        }

        // 2. X-constant lines running along Z axis ((x, y, -extent) to (x, y, extent))
        for (int x_i = -extent; x_i <= extent; x_i++) {
            float x = (float) x_i;
            float r = 0.22f, g = 0.22f, b = 0.28f, a = 0.8f;
            if (x_i == 0) { r = 0.25f; g = 0.55f; b = 1.0f; a = 1.0f; } // BLUE for Z Axis (X=0 line)

            addGridLine(verts, x, y, -extent, x, y, extent, r, g, b, a);
        }

        // 3. Y Axis Vertical Line (GREEN) from Y=0.0f to Y=3.0f!
        addGridLine(verts, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.25f, 1.0f, 0.25f, 1.0f);

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void addGridLine(List<Float> verts, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        verts.add(x1); verts.add(y1); verts.add(z1); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
        verts.add(x2); verts.add(y2); verts.add(z2); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
    }

    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();

            AssetType type = AssetType.UNKNOWN;
            if (ext.equals("obj") || ext.equals("fbx") || ext.equals("gltf") || ext.equals("glb")) {
                type = AssetType.MESH_3D;
            } else if (ext.equals("png") || ext.equals("jpg") || ext.equals("tga")) {
                type = AssetType.TEXTURE;
            } else if (ext.equals("scene")) {
                type = AssetType.SCENE;
            }

            AssetEntry entry = new AssetEntry(name, path, type);
            assetDirectory.add(entry);
            System.out.println("[Asset Directory] Registered File: " + name + " (" + type + ")");
        }
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot == -1) ? "" : fileName.substring(dot + 1);
    }

    public void spawnEntityFromAsset(AssetEntry meshAsset) {
        if (meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = meshAsset.name.replaceAll("\\.[^.]+$", "");
        SceneEntity entity = new SceneEntity(entityId, name, meshAsset.absolutePath);

        sceneEntities.add(entity);
        selectEntity(entity);
        System.out.println("[Scene Hierarchy] Spawned ECS Entity: '" + name + "' with Transform, MeshRenderer & Material.");
    }

    public void assignTextureToSelectedEntity(AssetEntry textureAsset) {
        if (selectedEntity == null || textureAsset == null || textureAsset.type != AssetType.TEXTURE) return;
        selectedEntity.setTextureAsset(textureAsset.absolutePath, textureAsset.name);
        System.out.println("[Inspector] Linked Texture '" + textureAsset.name + "' to ECS Entity '" + selectedEntity.name + "'");
    }

    public void assignMeshToSelectedEntity(AssetEntry meshAsset) {
        if (selectedEntity == null || meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        selectedEntity.setMeshAssetPath(meshAsset.absolutePath);
        System.out.println("[Inspector] Linked 3D Mesh '" + meshAsset.name + "' to ECS Entity '" + selectedEntity.name + "'");
    }

    public void selectEntity(SceneEntity entity) {
        for (SceneEntity e : sceneEntities) {
            e.selected = (e == entity);
        }
        this.selectedEntity = entity;
    }

    public List<SceneEntity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }
    public List<AssetEntry> getAssetDirectory() { return Collections.unmodifiableList(assetDirectory); }
    public SceneEntity getSelectedEntity() { return selectedEntity; }
    public String getActiveScenePath() { return activeScenePath; }

    public GizmoMode getCurrentGizmoMode() { return currentGizmoMode; }
    public void setCurrentGizmoMode(GizmoMode mode) {
        this.currentGizmoMode = mode;
        System.out.println("[Gizmo] Switched Gizmo Mode -> " + mode);
    }

    public GizmoAxis getActiveGizmoAxis() { return activeGizmoAxis; }
    public void setActiveGizmoAxis(GizmoAxis axis) { this.activeGizmoAxis = axis; }

    public DropdownMenu getActiveDropdownMenu() { return activeDropdownMenu; }
    public void setActiveDropdownMenu(DropdownMenu menu) {
        this.activeDropdownMenu = menu;
        if (menu != DropdownMenu.NONE) {
            System.out.println("[Top Menu Bar] Opened Dropdown -> " + menu);
        }
    }

    // Asset Picker Modal Management
    public ModalState getActiveModal() { return activeModal; }
    public AssetType getPickerFilterType() { return pickerFilterType; }

    public void openAssetPicker(AssetType filterType) {
        this.pickerFilterType = filterType;
        this.activeModal = ModalState.ASSET_PICKER;
        System.out.println("[EditorUI] Opened In-Engine Asset Picker Modal for type: " + filterType);
    }

    public void closeModal() {
        this.activeModal = ModalState.NONE;
        System.out.println("[EditorUI] Closed Modal Overlay.");
    }

    public float[] getUiVertices() {
        this.cachedUiVertices = generateUiVertices();
        return this.cachedUiVertices;
    }

    public int getUiVertexCount() {
        if (cachedUiVertices == null || cachedUiVertices.length == 0) getUiVertices();
        return cachedUiVertices.length / 6;
    }

    public float[] generateUiVertices() {
        List<Float> verts = new ArrayList<>();

        SceneHierarchyPanel.render(verts, sceneEntities);
        InspectorPanel.render(verts, selectedEntity, assetDirectory);
        AssetDirectoryPanel.render(verts, assetDirectory);

        ViewportPanel.renderFraming(verts);
        ViewportPanel.renderToolbar(verts, currentGizmoMode);

        renderTopMenuBar(verts);

        if (selectedEntity != null) {
            render3DGizmos(verts, selectedEntity, currentGizmoMode, activeGizmoAxis);
        }

        if (activeDropdownMenu != DropdownMenu.NONE) {
            renderDropdownMenuOverlay(verts, activeDropdownMenu);
        }

        if (activeModal == ModalState.ASSET_PICKER) {
            renderAssetPickerModal(verts);
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void renderTopMenuBar(List<Float> verts) {
        UIBatchBuilder.addQuad(verts, -1.0f, 0.92f, 1.0f, 1.0f, 0.10f, 0.10f, 0.14f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, -1.0f, 0.92f, 1.0f, 1.0f, 0.002f, 0.20f, 0.20f, 0.28f, 1.0f);

        UIBatchBuilder.addBoundedText(verts, "RETRO ENGINE", -0.98f, 0.94f, -0.75f, 1.2f, 0.25f, 0.80f, 1.0f, 1.0f);

        renderHeaderMenuButton(verts, "FILE", -0.72f, -0.62f, activeDropdownMenu == DropdownMenu.FILE);
        renderHeaderMenuButton(verts, "EDIT", -0.61f, -0.51f, activeDropdownMenu == DropdownMenu.EDIT);
        renderHeaderMenuButton(verts, "ENTITY", -0.50f, -0.38f, activeDropdownMenu == DropdownMenu.ENTITY);
        renderHeaderMenuButton(verts, "VIEW", -0.37f, -0.27f, activeDropdownMenu == DropdownMenu.VIEW);

        String sceneName = new File(activeScenePath).getName();
        UIBatchBuilder.addBoundedText(verts, "SCENE: " + sceneName, 0.35f, 0.94f, 0.98f, 1.0f, 0.7f, 0.7f, 0.8f, 1.0f);
    }

    private void renderHeaderMenuButton(List<Float> verts, String label, float minX, float maxX, boolean isActive) {
        float r = isActive ? 0.22f : 0.14f;
        float g = isActive ? 0.28f : 0.14f;
        float b = isActive ? 0.38f : 0.18f;

        UIBatchBuilder.addQuad(verts, minX, 0.93f, maxX, 0.99f, r, g, b, 1.0f);
        if (isActive) {
            UIBatchBuilder.addFrameBorder(verts, minX, 0.93f, maxX, 0.99f, 0.002f, 0.0f, 0.6f, 1.0f, 1.0f);
        }
        UIBatchBuilder.addBoundedText(verts, label, minX + 0.015f, 0.945f, maxX - 0.005f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void renderDropdownMenuOverlay(List<Float> verts, DropdownMenu menu) {
        float minX = switch (menu) {
            case FILE -> -0.72f;
            case EDIT -> -0.61f;
            case ENTITY -> -0.50f;
            case VIEW -> -0.37f;
            default -> -0.72f;
        };
        float maxX = minX + 0.32f;
        float minY = 0.58f;
        float maxY = 0.92f;

        UIBatchBuilder.addQuad(verts, minX, minY, maxX, maxY, 0.12f, 0.12f, 0.16f, 0.98f);
        UIBatchBuilder.addFrameBorder(verts, minX, minY, maxX, maxY, 0.003f, 0.0f, 0.55f, 0.90f, 1.0f);

        switch (menu) {
            case FILE -> {
                renderDropdownItem(verts, "New Scene", minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, "Open Scene... (Cmd+O)", minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(verts, "Save Scene... (Cmd+S)", minX + 0.01f, 0.67f, maxX - 0.01f);
            }
            case EDIT -> {
                String undoLabel = "Undo " + (commandHistory.canUndo() ? "(Cmd+Z)" : "[Disabled]");
                String redoLabel = "Redo " + (commandHistory.canRedo() ? "(Cmd+Shift+Z)" : "[Disabled]");
                renderDropdownItem(verts, undoLabel, minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, redoLabel, minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(verts, "Delete Selected (Del)", minX + 0.01f, 0.67f, maxX - 0.01f);
            }
            case ENTITY -> {
                renderDropdownItem(verts, "+ Spawn 3D Cube", minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, "+ Spawn Light Source", minX + 0.01f, 0.76f, maxX - 0.01f);
            }
            case VIEW -> {
                renderDropdownItem(verts, "Reset Viewport Camera (F)", minX + 0.01f, 0.85f, maxX - 0.01f);
                String gridLabel = show3DGrid ? "Hide 3D Grid" : "Show 3D Grid";
                renderDropdownItem(verts, gridLabel, minX + 0.01f, 0.76f, maxX - 0.01f);
            }
            default -> {}
        }
    }

    private void renderDropdownItem(List<Float> verts, String text, float minX, float y, float maxX) {
        UIBatchBuilder.addQuad(verts, minX, y - 0.02f, maxX, y + 0.05f, 0.16f, 0.18f, 0.24f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, minX, y - 0.02f, maxX, y + 0.05f, 0.001f, 0.25f, 0.28f, 0.35f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, text, minX + 0.01f, y + 0.01f, maxX - 0.01f, 1.0f, 0.95f, 0.95f, 0.95f, 1.0f);
    }

    public void renderAssetPickerModal(List<Float> verts) {
        UIBatchBuilder.addQuad(verts, -1.0f, -1.0f, 1.0f, 1.0f, 0.02f, 0.02f, 0.04f, 0.75f);

        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        UIBatchBuilder.addQuad(verts, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.10f, 0.11f, 0.15f, 0.98f);
        UIBatchBuilder.addFrameBorder(verts, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.004f, 0.0f, 0.55f, 0.90f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMinX, cardMaxY - 0.10f, cardMaxX, cardMaxY, 0.16f, 0.18f, 0.24f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "SELECT " + pickerFilterType.name() + " ASSET", cardMinX + 0.03f, cardMaxY - 0.04f, cardMaxX - 0.10f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMaxX - 0.08f, cardMaxY - 0.08f, cardMaxX - 0.02f, cardMaxY - 0.02f, 0.65f, 0.15f, 0.15f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "X", cardMaxX - 0.065f, cardMaxY - 0.035f, cardMaxX - 0.03f, 1.2f, 1.0f, 1.0f, 1.0f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMinX + 0.03f, cardMaxY - 0.18f, cardMinX + 0.38f, cardMaxY - 0.12f, 0.25f, 0.15f, 0.15f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, cardMinX + 0.03f, cardMaxY - 0.18f, cardMinX + 0.38f, cardMaxY - 0.12f, 0.002f, 0.8f, 0.2f, 0.2f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "[NONE / UNBIND]", cardMinX + 0.05f, cardMaxY - 0.14f, cardMinX + 0.36f, 1.0f, 1.0f, 0.6f, 0.6f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMaxX - 0.38f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.12f, 0.15f, 0.45f, 0.30f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, cardMaxX - 0.38f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.12f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "[IMPORT DISK...]", cardMaxX - 0.36f, cardMaxY - 0.14f, cardMaxX - 0.05f, 1.0f, 0.4f, 1.0f, 0.6f, 1.0f);

        List<AssetEntry> filtered = assetDirectory.stream()
                .filter(a -> a.type == pickerFilterType)
                .toList();

        float listY = cardMaxY - 0.24f;

        if (filtered.isEmpty()) {
            UIBatchBuilder.addBoundedText(verts, "NO " + pickerFilterType.name() + " ASSETS IN PROJECT", cardMinX + 0.05f, listY, cardMaxX - 0.05f, 1.1f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "CLICK [IMPORT DISK...] TO BROWSE NATIVE FILES", cardMinX + 0.05f, listY - 0.08f, cardMaxX - 0.05f, 1.0f, 0.3f, 0.8f, 1.0f, 1.0f);
        } else {
            for (AssetEntry asset : filtered) {
                if (listY < cardMinY + 0.05f) break;

                UIBatchBuilder.addQuad(verts, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.15f, 0.18f, 0.24f, 1.0f);
                UIBatchBuilder.addFrameBorder(verts, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.001f, 0.25f, 0.30f, 0.40f, 1.0f);

                UIBatchBuilder.addBoundedText(verts, "• " + asset.name, cardMinX + 0.05f, listY - 0.01f, cardMaxX - 0.22f, 1.1f, 0.95f, 0.95f, 0.95f, 1.0f);

                UIBatchBuilder.addQuad(verts, cardMaxX - 0.20f, listY - 0.04f, cardMaxX - 0.05f, listY + 0.02f, 0.0f, 0.45f, 0.70f, 1.0f);
                UIBatchBuilder.addBoundedText(verts, "SELECT", cardMaxX - 0.18f, listY - 0.01f, cardMaxX - 0.06f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                listY -= 0.10f;
            }
        }
    }

    private void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis) {
        TransformComponent tc = entity.getTransform();
        float px = tc.posX;
        float py = tc.posY;
        float pz = tc.posZ;

        float[] p0 = projectWorldToNdc(px, py, pz);
        float cx = p0[0];
        float cy = p0[1];

        float axisWorldLen = 1.2f;

        float[] pX = projectWorldToNdc(px + axisWorldLen, py, pz);
        float[] pY = projectWorldToNdc(px, py + axisWorldLen, pz);
        float[] pZ = projectWorldToNdc(px, py, pz + axisWorldLen);

        float dirXX = pX[0] - cx, dirXY = pX[1] - cy;
        float dirYX = pY[0] - cx, dirYY = pY[1] - cy;
        float dirZX = pZ[0] - cx, dirZY = pZ[1] - cy;

        float lenX = (float) Math.hypot(dirXX, dirXY);
        float lenY = (float) Math.hypot(dirYX, dirYY);
        float lenZ = (float) Math.hypot(dirZX, dirZY);

        float screenLen = 0.16f;

        float uXX = lenX > 0.0001f ? (dirXX / lenX) : 1.0f;
        float uXY = lenX > 0.0001f ? (dirXY / lenX) : 0.0f;

        float uYX = lenY > 0.0001f ? (dirYX / lenY) : 0.0f;
        float uYY = lenY > 0.0001f ? (dirYY / lenY) : 1.0f;

        float uZX = lenZ > 0.0001f ? (dirZX / lenZ) : -0.707f;
        float uZY = lenZ > 0.0001f ? (dirZY / lenZ) : -0.707f;

        float xTipX = cx + uXX * screenLen, xTipY = cy + uXY * screenLen;
        float yTipX = cx + uYX * screenLen, yTipY = cy + uYY * screenLen;
        float zTipX = cx + uZX * screenLen, zTipY = cy + uZY * screenLen;

        float lineThick = 0.006f;

        UIBatchBuilder.addSquareEnd(verts, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        float rx = 1.0f, rg = 0.25f, rb = 0.25f;
        float gx = 0.25f, gg = 1.0f, gb = 0.25f;
        float bx = 0.25f, bg = 0.55f, bb = 1.0f;

        if (activeAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        if (mode == GizmoMode.TRANSLATE) {
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, xTipX + uXX * 0.02f, xTipY + uXY * 0.02f, uXX, uXY, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, yTipX + uYX * 0.02f, yTipY + uYY * 0.02f, uYX, uYY, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, zTipX + uZX * 0.02f, zTipY + uZY * 0.02f, uZX, uZY, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.SCALE) {
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, xTipX, xTipY, 0.024f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, yTipX, yTipY, 0.024f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, zTipX, zTipY, 0.024f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.ROTATE) {
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }
    }
}
