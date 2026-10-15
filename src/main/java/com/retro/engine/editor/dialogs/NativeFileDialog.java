package com.retro.engine.editor.dialogs;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for cross-platform native OS file dialogs (macOS NSOpenPanel/NSSavePanel,
 * Windows File Explorer, Linux GTK/Zenity).
 * Supports both single and multi-file selections.
 */
public class NativeFileDialog {

    public static String openFileDialog(String title, String defaultPath, String filterDesc, String... extensions) {
        List<String> files = openMultipleFileDialogs(title, defaultPath, false, filterDesc, extensions);
        return (files != null && !files.isEmpty()) ? files.get(0) : null;
    }

    public static List<String> openMultipleFileDialogs(String title, String defaultPath, String filterDesc, String... extensions) {
        return openMultipleFileDialogs(title, defaultPath, true, filterDesc, extensions);
    }

    public static List<String> openMultipleFileDialogs(String title, String defaultPath, boolean allowMultiple, String filterDesc, String... extensions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = null;
            if (extensions != null && extensions.length > 0) {
                filters = stack.mallocPointer(extensions.length);
                for (String ext : extensions) {
                    filters.put(stack.UTF8("*." + ext));
                }
                filters.flip();
            }
            String result = TinyFileDialogs.tinyfd_openFileDialog(
                title,
                defaultPath != null ? defaultPath : "Assets/",
                filters,
                filterDesc,
                allowMultiple
            );
            if (result == null || result.trim().isEmpty()) {
                return new ArrayList<>();
            }
            String[] split = result.split("\\|");
            List<String> list = new ArrayList<>();
            for (String s : split) {
                if (!s.trim().isEmpty()) {
                    list.add(s.trim());
                }
            }
            return list;
        } catch (Exception e) {
            System.err.println("[NativeFileDialog] Error opening file dialog: " + e.getMessage());
            return new ArrayList<>();
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
