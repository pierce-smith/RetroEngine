package com.retro.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.Main;
import com.retro.engine.tools.BenchmarkScenes;
import com.retro.engine.tools.RenderCheckScene;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** The committed benchmark scenes must load and simulate, so a format or script change can't quietly break them. */
class BenchmarkScenesTest {

    private static final int FRAMES = 120;

    @TempDir Path dir;

    static Set<String> scenes() {
        return BenchmarkScenes.scenes().keySet();
    }

    @ParameterizedTest
    @MethodSource("scenes")
    void sceneRunsHeadless(String scene) throws Exception {
        Path project = Path.of(RenderCheckScene.PROJECT).toAbsolutePath();
        assertTrue(Files.isRegularFile(project.resolve(BenchmarkScenes.SCENE_DIR + scene)), "rerun BenchmarkScenes to write " + scene);

        Path log = dir.resolve("run.log");
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + dir.resolve("home"),
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--headless", "--no-mods", "--frames", Integer.toString(FRAMES),
                "--project", project.toString(), "--scene", BenchmarkScenes.SCENE_DIR + scene);
        Path workingDir = Files.createDirectories(dir.resolve("cwd"));
        Process process = new ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly().waitFor();
        String output = Files.readString(log);
        assertTrue(exited, "process did not exit:\n" + output);
        assertEquals(Main.EXIT_OK, process.exitValue(), output);
        assertTrue(output.contains("after " + FRAMES + " frames"), output);
    }
}
