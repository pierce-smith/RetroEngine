package com.retro.engine.editor.dialogs;

import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.ArrayList;
import java.util.List;

public class NativeFileDialog {

    public static String openFileDialog(String title, String defaultPath, String filterDescription, String... extensions) {
        List<String> results = openMultipleFileDialogs(title, defaultPath, filterDescription, extensions);
        return (results != null && !results.isEmpty()) ? results.get(0) : null;
    }

    public static List<String> openMultipleFileDialogs(String title, String defaultPath, String filterDescription, String... extensions) {
        org.lwjgl.PointerBuffer filterBuffer = null;
        if (extensions != null && extensions.length > 0) {
            filterBuffer = org.lwjgl.system.MemoryUtil.memAllocPointer(extensions.length);
            for (String ext : extensions) {
                filterBuffer.put(org.lwjgl.system.MemoryUtil.memUTF8("*." + ext));
            }
            filterBuffer.flip();
        }

        try {
            String result = TinyFileDialogs.tinyfd_openFileDialog(
                title,
                defaultPath != null ? defaultPath : "",
                filterBuffer,
                filterDescription != null ? filterDescription : "Supported Files",
                true // allow multiple selection
            );

            if (result != null && !result.trim().isEmpty()) {
                String[] paths = result.split("\\|");
                List<String> list = new ArrayList<>();
                for (String p : paths) {
                    if (p != null && !p.trim().isEmpty()) {
                        list.add(p.trim());
                    }
                }
                return list;
            }
        } finally {
            if (filterBuffer != null) {
                org.lwjgl.system.MemoryUtil.memFree(filterBuffer);
            }
        }
        return null;
    }

    public static String saveFileDialog(String title, String defaultPath, String filterDescription, String... extensions) {
        org.lwjgl.PointerBuffer filterBuffer = null;
        if (extensions != null && extensions.length > 0) {
            filterBuffer = org.lwjgl.system.MemoryUtil.memAllocPointer(extensions.length);
            for (String ext : extensions) {
                filterBuffer.put(org.lwjgl.system.MemoryUtil.memUTF8("*." + ext));
            }
            filterBuffer.flip();
        }

        try {
            return TinyFileDialogs.tinyfd_saveFileDialog(
                title,
                defaultPath != null ? defaultPath : "",
                filterBuffer,
                filterDescription != null ? filterDescription : "Supported Files"
            );
        } finally {
            if (filterBuffer != null) {
                org.lwjgl.system.MemoryUtil.memFree(filterBuffer);
            }
        }
    }

    public static String promptInput(String title, String message, String defaultInput) {
        try {
            return TinyFileDialogs.tinyfd_inputBox(
                title != null ? title : "Input",
                message != null ? message : "Enter text:",
                defaultInput != null ? defaultInput : ""
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
