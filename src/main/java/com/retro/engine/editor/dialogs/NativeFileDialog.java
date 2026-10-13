package com.retro.engine.editor.dialogs;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Utility for cross-platform native OS file dialogs (macOS NSOpenPanel/NSSavePanel,
 * Windows File Explorer, Linux GTK/Zenity).
 */
public class NativeFileDialog {

    public static String openFileDialog(String title, String defaultPath, String filterDesc, String... extensions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = null;
            if (extensions != null && extensions.length > 0) {
                filters = stack.mallocPointer(extensions.length);
                for (String ext : extensions) {
                    filters.put(stack.UTF8("*." + ext));
                }
                filters.flip();
            }
            return TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    defaultPath != null ? defaultPath : "Assets/Scenes/MainLevel.scene",
                    filters,
                    filterDesc,
                    false
            );
        } catch (Exception e) {
            System.err.println("[NativeFileDialog] Error opening file dialog: " + e.getMessage());
            return null;
        }
    }

    public static String saveFileDialog(String title, String defaultPath, String filterDesc, String... extensions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = null;
            if (extensions != null && extensions.length > 0) {
                filters = stack.mallocPointer(extensions.length);
                for (String ext : extensions) {
                    filters.put(stack.UTF8("*." + ext));
                }
                filters.flip();
            }
            return TinyFileDialogs.tinyfd_saveFileDialog(
                    title,
                    defaultPath != null ? defaultPath : "Assets/Scenes/MainLevel.scene",
                    filters,
                    filterDesc
            );
        } catch (Exception e) {
            System.err.println("[NativeFileDialog] Error opening save dialog: " + e.getMessage());
            return null;
        }
    }
}
