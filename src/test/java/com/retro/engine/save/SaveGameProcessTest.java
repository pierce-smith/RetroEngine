package com.retro.engine.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.Main;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.templates.TemplateManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Saves in one run of the game and loads in the next, each its own JVM running the standalone runtime (no editor),
 * so the save has to survive a real quit: shutdown, files on disk, a cold start.
 */
class SaveGameProcessTest {

    @TempDir Path dir;

    /** Replaces the starter cube's script: first run plays a bit and saves, second run loads and reports. */
    private static final String SCRIPT = """
            import com.retro.engine.save.Save;
            import com.retro.engine.save.SaveGame;
            import com.retro.engine.save.SaveRecord;
            public class StarterRotateScript extends com.retro.engine.scripting.framework.SimpleEntityScript {
                @Save public int coins = 0;
                private int frame = 0;
                private String carried = "";
                public void onStart() {
                    if (SaveGame.exists("slot1") && SaveGame.values().getString("run", "").isEmpty()) {
                        System.out.println("RUN2 LOADING " + SaveGame.load("slot1"));
                    }
                }
                public void onSave(SaveRecord d) { d.set("carried", "torch"); }
                public void onLoad(SaveRecord d) {
                    carried = d.getString("carried", "");
                    System.out.println("RUN2 LOADED coins=" + coins + " x=" + getPositionX() + " carried=" + carried
                            + " run=" + SaveGame.values().getString("run", "") + " play>0=" + (SaveGame.getPlayTime() > 0));
                }
                public void onUpdate(float dt) {
                    if (++frame == 10 && !SaveGame.exists("slot1")) {
                        setPosition(4.25f, getPositionY(), getPositionZ());
                        coins = 9;
                        SaveGame.values().set("run", "one");
                        System.out.println("RUN1 SAVED " + SaveGame.save("slot1", "First run"));
                    }
                }
            }
            """;

    private String run(File project) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("retro.test.runtime", System.getProperty("java.home")), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + dir.resolve("home"),
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--headless", "--project", project.getPath(), "--frames", "30"));
        Path log = Files.createTempFile(dir, "run", ".log");
        Path cwd = Files.createDirectories(dir.resolve("cwd"));
        Process p = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean exited = p.waitFor(90, TimeUnit.SECONDS);
        if (!exited) p.destroyForcibly().waitFor();
        String out = Files.readString(log);
        assertTrue(exited, "game did not exit:\n" + out);
        assertEquals(Main.EXIT_OK, p.exitValue(), out);
        return out;
    }

    @Test
    void saveInOneRunLoadInTheNext() throws Exception {
        File project = ProjectWorkspace.createProject(new EditorWorkspace(), dir.toFile(), "Save Run", TemplateManager.STARTER_CONTENT);
        Files.writeString(project.toPath().resolve("Assets/Scripts/StarterRotateScript.java"), SCRIPT);

        String first = run(project);
        assertTrue(first.contains("RUN1 SAVED true"), first);

        String second = run(project);
        assertTrue(second.contains("RUN2 LOADING true"), second);
        assertTrue(second.contains("RUN2 LOADED coins=9 x=4.25 carried=torch run=one play>0=true"), second);
        // Loaded exactly once: after the load the playthrough's values are set, so onStart does not load again
        assertEquals(1, second.split("RUN2 LOADED", -1).length - 1, second);
    }
}
