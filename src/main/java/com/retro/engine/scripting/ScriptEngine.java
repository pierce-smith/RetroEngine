package com.retro.engine.scripting;

import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.components.gameplay.ScriptComponent;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages instantiation, lifecycle ticks (onStart, onUpdate, onDestroy),
 * dynamic runtime compilation of .java source files on disk,
 * and cleanup of EntityScript instances during scene simulation.
 */
public class ScriptEngine {

    private static final Map<String, Class<?>> compiledClassCache = new ConcurrentHashMap<>();

    public static void initializeScripts(List<Entity> entities, SceneWorld sceneWorld) {
        if (entities == null) return;
        for (Entity entity : entities) {
            ScriptComponent sc = entity.getComponent(ScriptComponent.class);
            if (sc != null && sc.enabled) {
                instantiateAndStartScript(entity, sc, sceneWorld);
            }
        }
    }

    public static void updateScripts(List<Entity> entities, float deltaTime, SceneWorld sceneWorld) {
        if (entities == null) return;
        for (Entity entity : entities) {
            ScriptComponent sc = entity.getComponent(ScriptComponent.class);
            if (sc != null && sc.enabled) {
                if (sc.activeScriptInstance == null) {
                    instantiateAndStartScript(entity, sc, sceneWorld);
                }
                if (sc.activeScriptInstance != null) {
                    try {
                        sc.activeScriptInstance.onUpdate(deltaTime);
                    } catch (Exception e) {
                        System.err.println("[ScriptEngine] Error updating script on entity '" + entity.name + "': " + e.getMessage());
                    }
                }
            }
        }
    }

    public static void destroyScripts(List<Entity> entities) {
        if (entities == null) return;
        for (Entity entity : entities) {
            ScriptComponent sc = entity.getComponent(ScriptComponent.class);
            if (sc != null && sc.activeScriptInstance != null) {
                try {
                    sc.activeScriptInstance.onDestroy();
                } catch (Exception e) {
                    System.err.println("[ScriptEngine] Error destroying script on entity '" + entity.name + "': " + e.getMessage());
                }
                sc.activeScriptInstance = null;
            }
        }
    }

    private static void instantiateAndStartScript(Entity entity, ScriptComponent sc, SceneWorld sceneWorld) {
        if (sc == null) return;
        String rawName = (sc.scriptName != null && !sc.scriptName.trim().isEmpty()) ? sc.scriptName : sc.scriptAssetPath;
        if (rawName == null || rawName.trim().isEmpty()) return;

        // Clean path separators and extensions to get exact simple class name
        File sourceFile = new File(rawName);
        String fileName = sourceFile.getName().trim();

        String cleanClassName = fileName;
        if (cleanClassName.toLowerCase().endsWith(".java")) {
            cleanClassName = cleanClassName.substring(0, cleanClassName.length() - 5);
        } else if (cleanClassName.toLowerCase().endsWith(".class")) {
            cleanClassName = cleanClassName.substring(0, cleanClassName.length() - 6);
        }

        // Locate .java file on disk if available
        File diskScriptFile = null;
        if (sc.scriptAssetPath != null && !sc.scriptAssetPath.trim().isEmpty()) {
            File f = new File(sc.scriptAssetPath);
            if (f.exists()) diskScriptFile = f;
        }
        if (diskScriptFile == null && sourceFile.exists()) {
            diskScriptFile = sourceFile;
        }
        if (diskScriptFile == null) {
            File alt = new File("Assets/Scripts/" + fileName);
            if (alt.exists()) diskScriptFile = alt;
        }

        Class<?> clazz = compiledClassCache.get(cleanClassName);

        if (clazz == null) {
            // 1. Try static classpath resolution first
            String[] candidateClassNames = new String[]{
                "com.retro.engine.scripting.user." + cleanClassName,
                "com.retro.engine.scripting." + cleanClassName,
                cleanClassName
            };

            for (String candidate : candidateClassNames) {
                try {
                    clazz = Class.forName(candidate);
                    if (clazz != null) {
                        compiledClassCache.put(cleanClassName, clazz);
                        break;
                    }
                } catch (ClassNotFoundException ignored) {}
            }
        }

        // 2. If not found on static classpath, attempt dynamic runtime compilation of .java file from disk
        if (clazz == null && diskScriptFile != null && diskScriptFile.exists()) {
            clazz = compileAndLoadJavaFile(diskScriptFile, cleanClassName);
            if (clazz != null) {
                compiledClassCache.put(cleanClassName, clazz);
            }
        }

        if (clazz == null) {
            System.err.println("[ScriptEngine] Could not resolve or compile script class for '" + fileName + "'.");
            return;
        }

        try {
            if (EntityScript.class.isAssignableFrom(clazz)) {
                EntityScript script = (EntityScript) clazz.getDeclaredConstructor().newInstance();
                script.init(entity, sceneWorld);
                sc.activeScriptInstance = script;
                script.onStart();
                System.out.println("[ScriptEngine] Successfully initialized script '" + clazz.getSimpleName() + "' on Entity '" + entity.name + "'");
            } else {
                System.err.println("[ScriptEngine] Class '" + clazz.getName() + "' does not extend EntityScript!");
            }
        } catch (Exception e) {
            System.err.println("[ScriptEngine] Failed to instantiate script '" + clazz.getName() + "' on Entity '" + entity.name + "': " + e.getMessage());
        }
    }

    private static Class<?> compileAndLoadJavaFile(File scriptFile, String className) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                System.err.println("[ScriptEngine] JDK JavaCompiler unavailable in current JRE.");
                return null;
            }

            File outputDir = new File(System.getProperty("java.io.tmpdir"), "retro_compiled_scripts");
            if (!outputDir.exists()) outputDir.mkdirs();

            System.out.println("[ScriptEngine] Compiling runtime gameplay script: " + scriptFile.getAbsolutePath());

            int exitCode = compiler.run(null, null, null,
                "-d", outputDir.getAbsolutePath(),
                scriptFile.getAbsolutePath()
            );

            if (exitCode == 0) {
                URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{ outputDir.toURI().toURL() },
                    EntityScript.class.getClassLoader()
                );

                String[] candidates = new String[]{
                    "com.retro.engine.scripting.user." + className,
                    "com.retro.engine.scripting." + className,
                    className
                };

                for (String c : candidates) {
                    try {
                        return classLoader.loadClass(c);
                    } catch (ClassNotFoundException ignored) {}
                }
            } else {
                System.err.println("[ScriptEngine] Compilation failed for script: " + scriptFile.getName());
            }
        } catch (Exception e) {
            System.err.println("[ScriptEngine] Exception during dynamic script compilation: " + e.getMessage());
        }
        return null;
    }
}
