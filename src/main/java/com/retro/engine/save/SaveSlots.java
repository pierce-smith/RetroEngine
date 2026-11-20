package com.retro.engine.save;

import com.retro.engine.core.AtomicFile;
import com.retro.engine.core.UserFolders;
import com.retro.engine.scripting.framework.ScriptSaveData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Where save slots live on disk ({@code <OS save folder>/<slot>.rsave}) and reading / writing / listing them. */
final class SaveSlots {

    static final String EXTENSION = ".rsave";

    private SaveSlots() {}

    static File folder() {
        return new File(ScriptSaveData.getSaveFolder());
    }

    /** Slot names become file names, so they get the same cleanup as game folder names. */
    static File fileFor(String slot) {
        return new File(folder(), UserFolders.folderName(slot == null || slot.isBlank() ? "save" : slot.trim()) + EXTENSION);
    }

    static void write(String slot, SaveData data) throws IOException {
        File file = fileFor(slot);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create the save folder " + dir);
        // A crash mid-write must never lose the slot's previous save
        AtomicFile.write(file, SaveFileFormat.write(data));
    }

    static SaveData read(String slot) throws IOException {
        File file = fileFor(slot);
        if (!file.isFile()) throw new IOException("There is no save in slot '" + slot + "'.");
        return SaveFileFormat.read(Files.readAllBytes(file.toPath()));
    }

    static boolean exists(String slot) { return fileFor(slot).isFile(); }

    static boolean delete(String slot) {
        File file = fileFor(slot);
        return !file.exists() || file.delete();
    }

    /** Every readable slot, newest first. Damaged files are logged and left out. */
    static List<SaveGame.SlotInfo> list() {
        List<SaveGame.SlotInfo> out = new ArrayList<>();
        File[] files = folder().listFiles((d, name) -> name.endsWith(EXTENSION));
        if (files == null) return out;
        for (File f : files) {
            String slot = f.getName().substring(0, f.getName().length() - EXTENSION.length());
            try {
                out.add(info(slot, SaveFileFormat.read(Files.readAllBytes(f.toPath()))));
            } catch (IOException e) {
                System.err.println("[SaveGame] Skipping " + f.getName() + ": " + e.getMessage());
            }
        }
        out.sort(Comparator.comparingLong(SaveGame.SlotInfo::savedAtMillis).reversed());
        return out;
    }

    static SaveGame.SlotInfo info(String slot, SaveData d) {
        return new SaveGame.SlotInfo(slot, d.displayName, d.scenePath, d.savedAtMillis, d.playTimeSeconds);
    }
}
