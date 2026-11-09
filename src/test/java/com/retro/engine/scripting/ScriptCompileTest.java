package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.modding.ModScript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every script we hand to users has to compile through {@link ScriptCompiler}, the same path ScriptEngine and the mod
 * loader use at runtime.
 */
class ScriptCompileTest {

    public static Stream<Path> shippedScripts() throws IOException {
        Stream<Path> all = Stream.empty();
        for (String root : List.of("src/main/resources/templates", "src/main/resources/starter", "src/test/resources/TestProject/Assets/Scripts",
                "src/main/java/com/retro/engine/scripting/framework/examples")) {
            all = Stream.concat(all, javaFiles(Path.of(root)).stream());
        }
        return all;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static ClassLoader cook(Path source, ClassLoader parent) throws Exception {
        return ScriptCompiler.compile(source.toFile(), parent);
    }

    /** ScriptEngine reads the package line to build this name; the test only needs the same result. */
    private static String className(Path source) throws IOException {
        String simple = source.getFileName().toString().replace(".java", "");
        for (String line : Files.readAllLines(source)) {
            if (line.startsWith("package ")) {
                return line.substring("package ".length(), line.indexOf(';')).trim() + "." + simple;
            }
        }
        return simple;
    }

    @Test
    void thereAreScriptsToCheck() throws IOException {
        assertFalse(shippedScripts().toList().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("shippedScripts")
    void scriptCompilesAsAnEntityScript(Path source) throws Exception {
        Class<?> loaded = cook(source, getClass().getClassLoader()).loadClass(className(source));
        assertTrue(EntityScript.class.isAssignableFrom(loaded), source + " is not an EntityScript");
    }

    /** Mod scripts compile against the mod's own classes, through the same class-loader chain the game uses. */
    @Test
    void exampleModCompilesThroughItsClassLoaderChain() throws Exception {
        Path scripts = Path.of("ModSDK/StarterPack/example_mod/Scripts");
        ClassLoader entry = cook(scripts.resolve("ModScript.java"), getClass().getClassLoader());
        Class<?> entryClass = entry.loadClass("com.example.examplemod.ModScript");
        assertTrue(ModScript.class.isAssignableFrom(entryClass));

        ClassLoader wobble = cook(scripts.resolve("WobbleScript.java"), entry);
        Class<?> wobbleClass = wobble.loadClass("com.example.examplemod.WobbleScript");
        assertTrue(EntityScript.class.isAssignableFrom(wobbleClass));
    }

    /** The reason for moving to javac: Janino rejected all of these. */
    @Test
    void modernJavaCompiles(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path source = dir.resolve("ModernScript.java");
        Files.writeString(source, """
                import com.retro.engine.scripting.EntityScript;
                import java.util.ArrayList;
                import java.util.List;

                public class ModernScript extends EntityScript {
                    record Hit(String name, float damage) {}

                    public static float total() {
                        var hits = new ArrayList<Hit>();
                        hits.add(new Hit("a", 2f));
                        hits.add(new Hit("b", 3f));
                        float sum = 0;
                        for (Hit h : hits) sum += h.damage();
                        Runnable r = () -> {};
                        r.run();
                        return sum + (float) hits.stream().mapToDouble(Hit::damage).sum();
                    }
                }
                """);
        Class<?> c = cook(source, getClass().getClassLoader()).loadClass("ModernScript");
        assertEquals(10f, (float) c.getMethod("total").invoke(null));
    }

    /** Mod code compiled in memory must be visible to the next compile in the chain, which reads no class files. */
    @Test
    void laterCompileSeesClassesFromEarlierOne(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path a = dir.resolve("Shared.java");
        Files.writeString(a, "package mod.a; public class Shared { public static int value() { return 7; } }");
        Path b = dir.resolve("UsesShared.java");
        Files.writeString(b, "package mod.b; public class UsesShared { public static int get() { return mod.a.Shared.value() * 2; } }");
        ClassLoader first = cook(a, getClass().getClassLoader());
        ClassLoader second = cook(b, first);
        assertEquals(14, second.loadClass("mod.b.UsesShared").getMethod("get").invoke(null));
    }

    @Test
    void compileErrorNamesFileAndLine(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path source = dir.resolve("Broken.java");
        Files.writeString(source, "public class Broken {\n    int x = \"nope\";\n}\n");
        ScriptCompiler.CompileException e = assertThrows(ScriptCompiler.CompileException.class,
                () -> ScriptCompiler.compile(source.toFile(), getClass().getClassLoader()));
        assertTrue(e.getMessage().startsWith("Broken.java:2: "), e.getMessage());
    }
}
