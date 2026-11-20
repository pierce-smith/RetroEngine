package com.retro.engine.save;

import com.retro.engine.api.PublicApi;

import java.util.List;

/**
 * Save slots for gameplay scripts: save where the player is and what happened in the game, and load it back.
 *
 * <p>A save holds the level the player is in, {@link #values()} (quest flags, keys found, chapter reached), and for each
 * level what changed since it was loaded from its scene file: entities destroyed, entities spawned during play (by
 * prefab or {@code spawnEntity}), changed component properties such as positions, and script fields marked
 * {@link Save} plus whatever scripts write in {@code onSave}. Loading loads the level fresh from its scene file and
 * applies those changes, so a level you patch after release still loads under old saves.</p>
 *
 * <p>Level memory: with <b>Remember Levels</b> on (Project Settings, Runtime tab; {@link #setRememberLevels} changes it
 * while the game runs) a level the player leaves keeps its changes, and returning to it brings them back; every
 * remembered level goes into the save. With it off, a level resets whenever it is left and a save holds only the
 * level the player is in. Loading the level that is already running ({@code SceneManager.reloadCurrentScene()}) always
 * restarts it as the scene file has it.</p>
 *
 * <p>Not saved: physics velocities, animation playback, sounds that are playing, particles. Save what your game needs of
 * those in {@code onSave}. Saving works while the game runs (in the editor: in Play mode); files go to the game's
 * folder in the operating system's save location ({@code ScriptSaveData.getSaveFolder()}) as {@code <slot>.rsave}.
 * In the editor, Stop forgets the session but keeps the files. {@link com.retro.engine.scripting.framework.ScriptSaveData}
 * remains the place for settings that belong to the player rather than to one save (volume, display choices).</p>
 *
 * <pre>{@code
 * SaveGame.save("slot1", "Chapter 2 - Docks");     // now
 * if (SaveGame.exists("slot1")) SaveGame.load("slot1");  // switches level at the end of the frame
 * SaveGame.values().set("met_ferryman", true);
 * }</pre>
 */
@PublicApi
public final class SaveGame {

    /** One slot on disk, for a load menu. */
    public record SlotInfo(String slot, String displayName, String scenePath, long savedAtMillis, double playTimeSeconds) {}

    private SaveGame() {}

    /** Saves the game to {@code slot} right away. Returns false when nothing is running or writing failed (logged). */
    public static boolean save(String slot) {
        return save(slot, "");
    }

    /** {@code displayName} is shown back by {@link #listSlots()}, for example the chapter or checkpoint name. */
    public static boolean save(String slot, String displayName) {
        SaveSystem s = system("save");
        return s != null && s.save(slot, displayName);
    }

    /**
     * Saves at the end of this frame, after physics and every script ran, so everything has settled. The
     * {@code Checkpoint} component uses this.
     */
    public static void saveAtEndOfFrame(String slot, String displayName) {
        SaveSystem s = system("saveAtEndOfFrame");
        if (s != null) s.requestSave(slot, displayName);
    }

    /**
     * Loads {@code slot}: at the end of this frame the saved level loads fresh and the saved state is applied before
     * its scripts start. Returns false, leaving the current level running, when the slot is missing or damaged.
     */
    public static boolean load(String slot) {
        SaveSystem s = system("load");
        return s != null && s.load(slot);
    }

    /** Starts over from {@code scenePath}: forgets every remembered level, the values and the play time. Files on disk stay. */
    public static boolean newGame(String scenePath) {
        SaveSystem s = system("newGame");
        return s != null && s.newGame(scenePath);
    }

    public static boolean exists(String slot) {
        return SaveSlots.exists(slot);
    }

    /** Deletes the slot's file. True when it is gone (or never existed). */
    public static boolean delete(String slot) {
        return SaveSlots.delete(slot);
    }

    /** Every slot on disk, newest first. Reads each file, so call it when a menu opens, not every frame. */
    public static List<SlotInfo> listSlots() {
        return SaveSlots.list();
    }

    /** The slot's details, or null when it is missing or damaged. */
    public static SlotInfo getSlotInfo(String slot) {
        try {
            return SaveSlots.info(slot, SaveSlots.read(slot));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Values that belong to this playthrough: saved with every slot and replaced on load. Empty outside play (a
     * throwaway record is returned, so reads are safe).
     */
    public static SaveRecord values() {
        SaveSystem s = SaveSystem.active();
        return s != null ? s.values() : new SaveRecord();
    }

    /** Seconds played in this playthrough, carried in saves. */
    public static double getPlayTime() {
        SaveSystem s = SaveSystem.active();
        return s != null ? s.playTime() : 0;
    }

    public static boolean isRememberingLevels() {
        SaveSystem s = SaveSystem.active();
        return s == null || s.rememberLevels();
    }

    /** Overrides the project's Remember Levels setting until the game quits (or the editor stops Play). Off forgets every remembered level. */
    public static void setRememberLevels(boolean remember) {
        SaveSystem s = system("setRememberLevels");
        if (s != null) s.setRememberLevels(remember);
    }

    /** The level comes back as its scene file has it the next time it loads. */
    public static void forgetLevel(String scenePath) {
        SaveSystem s = system("forgetLevel");
        if (s != null) s.forgetLevel(scenePath);
    }

    public static void forgetAllLevels() {
        SaveSystem s = system("forgetAllLevels");
        if (s != null) s.forgetAllLevels();
    }

    private static SaveSystem system(String call) {
        SaveSystem s = SaveSystem.active();
        if (s == null) System.err.println("[SaveGame] " + call + " ignored: the game is not running.");
        return s;
    }
}
