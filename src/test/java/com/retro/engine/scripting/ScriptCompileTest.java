package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.modding.ModScript;

import org.codehaus.janino.SimpleCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every script we hand to users has to compile under Janino, the compiler that runs them. javac is not a substitute:
 * it accepts code Janino rejects, so these go through the same steps as ScriptEngine.
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

    private static SimpleCompiler cook(Path source, ClassLoader parent) throws Exception {
        SimpleCompiler compiler = new SimpleCompiler();
        compiler.setParentClassLoader(parent);
        compiler.cook(source.toString(), new StringReader(Files.readString(source)));
        return compiler;
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
        SimpleCompiler compiler = cook(source, getClass().getClassLoader());
        Class<?> loaded = compiler.getClassLoader().loadClass(className(source));
        assertTrue(EntityScript.class.isAssignableFrom(loaded), source + " is not an EntityScript");
    }

    /** Mod scripts compile against the mod's own classes, through the same class-loader chain the game uses. */
    @Test
    void exampleModCompilesThroughItsClassLoaderChain() throws Exception {
        Path scripts = Path.of("ModSDK/StarterPack/example_mod/Scripts");
        SimpleCompiler entry = cook(scripts.resolve("ModScript.java"), getClass().getClassLoader());
        Class<?> entryClass = entry.getClassLoader().loadClass("com.example.examplemod.ModScript");
        assertTrue(ModScript.class.isAssignableFrom(entryClass));

        SimpleCompiler wobble = cook(scripts.resolve("WobbleScript.java"), entry.getClassLoader());
        Class<?> wobbleClass = wobble.getClassLoader().loadClass("com.example.examplemod.WobbleScript");
        assertTrue(EntityScript.class.isAssignableFrom(wobbleClass));
    }
}
