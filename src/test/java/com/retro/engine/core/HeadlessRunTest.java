package com.retro.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.Main;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.templates.TemplateManager;
import com.retro.engine.editor.templates.TemplateManager.ProjectTemplate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the engine as a real separate process, the way CI and a player would: new project on disk, fresh JVM, real exit
 * code. In-process tests can't see startup order, class loading or whether the JVM actually exits.
 */
class HeadlessRunTest {

    private static final int FRAMES = 300;

    @TempDir Path dir;

    private record Run(int exitCode, String output) {}

    private Run runHeadless(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + dir.resolve("home"),
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--headless"));
        command.addAll(List.of(args));
        Path log = dir.resolve("run.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly().waitFor();
        String output = Files.readString(log);
        assertTrue(exited, "process did not exit:\n" + output);
        return new Run(process.exitValue(), output);
    }

    private File createProject(ProjectTemplate template) throws IOException {
        return ProjectWorkspace.createProject(new EditorWorkspace(), dir.toFile(), "Game_" + template.id(), template);
    }

    private static void assertExit(int expected, Run run) {
        assertEquals(expected, run.exitCode(), run.output());
    }

    public static Stream<ProjectTemplate> playableTemplates() {
        return TemplateManager.getTemplates().stream().filter(t -> t != TemplateManager.BLANK);
    }

    @ParameterizedTest
    @MethodSource("playableTemplates")
    void templateRunsCleanly(ProjectTemplate template) throws Exception {
        File project = createProject(template);
        Run run = runHeadless("--project", project.getPath(), "--frames", String.valueOf(FRAMES));
        assertExit(Main.EXIT_OK, run);
        assertTrue(run.output().contains("after " + FRAMES + " frames"), run.output());
    }

    @Test
    void equalsFormOfTheArgumentsWorks() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        assertExit(Main.EXIT_OK, runHeadless("--project=" + project.getPath(), "--frames=5"));
    }

    @Test
    void missingProjectFailsInsteadOfRunningNothing() throws Exception {
        assertExit(Main.EXIT_STARTUP_FAILED, runHeadless("--project", dir.resolve("nope").toString(), "--frames", "5"));
    }

    @Test
    void framesWithoutAProjectFails() throws Exception {
        assertExit(Main.EXIT_STARTUP_FAILED, runHeadless("--frames", "5"));
    }

    @Test
    void invalidFrameCountsFail() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        for (String frames : List.of("0", "-5", "lots")) {
            assertExit(Main.EXIT_STARTUP_FAILED, runHeadless("--project", project.getPath(), "--frames", frames));
        }
        assertExit(Main.EXIT_STARTUP_FAILED, runHeadless("--project", project.getPath(), "--frames"));
    }

    @Test
    void emptySceneFailsBecauseNothingWasTested() throws Exception {
        File project = createProject(TemplateManager.BLANK);
        assertExit(Main.EXIT_STARTUP_FAILED, runHeadless("--project", project.getPath(), "--frames", "5"));
    }

    @Test
    void corruptSceneFails() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        Files.write(project.toPath().resolve(ProjectWorkspace.STARTER_SCENE), new byte[] { 1, 2, 3, 4 });
        assertExit(Main.EXIT_STARTUP_FAILED, runHeadless("--project", project.getPath(), "--frames", "5"));
    }

    @Test
    void sceneDamagedByASingleByteFails() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        Path scene = project.toPath().resolve(ProjectWorkspace.STARTER_SCENE);
        byte[] bytes = Files.readAllBytes(scene);
        bytes[bytes.length / 2] ^= 0x40;
        Files.write(scene, bytes);
        Run run = runHeadless("--project", project.getPath(), "--frames", "5");
        assertExit(Main.EXIT_STARTUP_FAILED, run);
        assertTrue(run.output().contains("checksum"), run.output());
    }

    /** The starter scene's cube runs this script, so changing its source is enough to break a real project. */
    private void replaceStarterScript(File project, String source) throws IOException {
        Files.writeString(project.toPath().resolve("Assets/Scripts/StarterRotateScript.java"), source);
    }

    @Test
    void scriptThatDoesNotCompileFails() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        replaceStarterScript(project, "public class StarterRotateScript extends NotARealClass { }");
        assertExit(Main.EXIT_SCRIPT_FAILED, runHeadless("--project", project.getPath(), "--frames", "5"));
    }

    @Test
    void scriptThatThrowsDuringPlayFails() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        replaceStarterScript(project, """
                import com.retro.engine.scripting.EntityScript;
                public class StarterRotateScript extends EntityScript {
                    public void onUpdate(float deltaTime) { throw new IllegalStateException("boom"); }
                }
                """);
        Run run = runHeadless("--project", project.getPath(), "--frames", "5");
        assertExit(Main.EXIT_SCRIPT_FAILED, run);
        assertTrue(run.output().contains("boom"), run.output());
    }

    @Test
    void scriptThatThrowsWhenTheSceneStopsFails() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        replaceStarterScript(project, """
                import com.retro.engine.scripting.EntityScript;
                public class StarterRotateScript extends EntityScript {
                    public void onDestroy() { throw new IllegalStateException("boom"); }
                }
                """);
        assertExit(Main.EXIT_SCRIPT_FAILED, runHeadless("--project", project.getPath(), "--frames", "5"));
    }

    @Test
    void headlessRunDoesNotTouchTheProjectScene() throws Exception {
        File project = createProject(TemplateManager.STARTER_CONTENT);
        Path scene = project.toPath().resolve(ProjectWorkspace.STARTER_SCENE);
        byte[] before = Files.readAllBytes(scene);
        assertExit(Main.EXIT_OK, runHeadless("--project", project.getPath(), "--frames", "60"));
        assertFalse(before.length == 0);
        assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(Files.readAllBytes(scene)));
    }
}
