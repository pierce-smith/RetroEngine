package com.retro.engine.scripting;

import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.components.gameplay.ScriptComponent;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardened ScriptEngine managing script instantiation, lifecycle ticks,
 * reflection lookups, ClassLoader caching, dynamic runtime compilation,
 * and hot-loop exception suppression.
 */
public class ScriptEngine {

    // Caches successfully loaded classes by clean simple name / key
    private static final Map<String, Class<?>> compiledClassCache = new ConcurrentHashMap<>();
    
    // Negative failure cache preventing hot-loop re-compilation/re-resolution on every frame tick
    private static final Set<String> failedScriptCache = ConcurrentHashMap.newKeySet();

    // Reusable URLClassLoader to prevent Metaspace and classloader leaks across re-compilations
    private static URLClassLoader scriptClassLoader;

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");

    /**
     * Resets caches and closes open ClassLoaders. Call this when re-compiling or reloading scripts.
     */
    public static void clearCache() {
        compiledClassCache.clear();
        failedScriptCache.clear();
        if (scriptClassLoader != null) {
            try {
                scriptClassLoader.close();
            } catch (IOException ignored) {}
            scriptClassLoader = null;
        }
    }

    public static void initializeScripts(List<Entity> entities, SceneWorld sceneWorld) {
        if (entities == null) return;
        for (Entity entity : entities) {
            if (entity == null) continue;
            ScriptComponent sc = entity.getComponent(ScriptComponent.class);
            if (sc != null && sc.enabled && sc.activeScriptInstance == null) {
                instantiateAndStartScript(entity, sc, sceneWorld);
            }
        }
    }

    public static void updateScripts(List<Entity> entities, float deltaTime, SceneWorld sceneWorld) {
        if (entities == null) return;
        for (Entity entity : entities) {
            if (entity == null) continue;
            ScriptComponent sc = entity.getComponent(ScriptComponent.class);
            if (sc != null && sc.enabled) {
                if (sc.activeScriptInstance == null) {
                    instantiateAndStartScript(entity, sc, sceneWorld);
                }
                if (sc.activeScriptInstance != null) {
                    try {
                        sc.activeScriptInstance.onUpdate(deltaTime);
                    } catch (Exception e) {
                        System.err.println("[ScriptEngine] Exception during onUpdate() on Entity '" + entity.name + "': " + e.getMessage());
                        // Disable script instance after runtime failure to prevent thrashing stderr on every frame
                        sc.activeScriptInstance = null;
                        sc.enabled = false;
                    }
                }
            }
        }
    }

    public static void destroyScripts(List<Entity> entities) {
        if (entities == null) return;
        for (Entity entity : entities) {
            if (entity == null) continue;
            ScriptComponent sc = entity.getComponent(ScriptComponent.class);
            if (sc != null && sc.activeScriptInstance != null) {
                try {
                    sc.activeScriptInstance.onDestroy();
                } catch (Exception e) {
                    System.err.println("[ScriptEngine] Exception during onDestroy() on Entity '" + entity.name + "': " + e.getMessage());
                }
                sc.activeScriptInstance = null;
            }
        }
    }

    private static void instantiateAndStartScript(Entity entity, ScriptComponent sc, SceneWorld sceneWorld) {
        if (sc == null) return;
        String rawName = (sc.scriptName != null && !sc.scriptName.trim().isEmpty()) ? sc.scriptName : sc.scriptAssetPath;
        if (rawName == null || rawName.trim().isEmpty()) return;

        File sourceFile = new File(rawName);
        String fileName = sourceFile.getName().trim();

        String cleanClassName = fileName;
        if (cleanClassName.toLowerCase().endsWith(".java")) {
            cleanClassName = cleanClassName.substring(0, cleanClassName.length() - 5);
        } else if (cleanClassName.toLowerCase().endsWith(".class")) {
            cleanClassName = cleanClassName.substring(0, cleanClassName.length() - 6);
        }

        // 1. Check negative cache: if resolution failed previously, skip immediately
        if (failedScriptCache.contains(cleanClassName)) {
            return;
        }

        // 2. Check positive cache
        Class<?> clazz = compiledClassCache.get(cleanClassName);

        // Resolve source file location on disk
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

        // Parse package header from disk source file
        String declaredPackage = parsePackageDeclaration(diskScriptFile);

        if (clazz == null) {
            // Static classpath lookups using parsed package and expanded sub-package list
            String[] candidateClassNames;
            if (declaredPackage != null && !declaredPackage.isEmpty()) {
                candidateClassNames = new String[]{
                    declaredPackage + "." + cleanClassName,
                    "com.retro.engine.scripting.user." + cleanClassName,
                    "com.retro.engine.scripting." + cleanClassName,
                    "com.retro.engine.scene.components.gameplay." + cleanClassName,
                    cleanClassName
                };
            } else {
                candidateClassNames = new String[]{
                    "com.retro.engine.scripting.user." + cleanClassName,
                    "com.retro.engine.scripting." + cleanClassName,
                    "com.retro.engine.scene.components.gameplay." + cleanClassName,
                    cleanClassName
                };
            }

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

        // 3. Dynamic runtime compilation if class is not on static classpath
        if (clazz == null && diskScriptFile != null && diskScriptFile.exists()) {
            clazz = compileAndLoadJavaFile(diskScriptFile, cleanClassName, declaredPackage);
            if (clazz != null) {
                compiledClassCache.put(cleanClassName, clazz);
            }
        }

        if (clazz == null) {
            // Record failure in negative cache to prevent re-compilation on subsequent frames
            failedScriptCache.add(cleanClassName);
            System.err.println("[ScriptEngine] Could not resolve or compile script class for '" + fileName + "'. Skipping further attempts.");
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
                failedScriptCache.add(cleanClassName);
                System.err.println("[ScriptEngine] Class '" + clazz.getName() + "' does not extend EntityScript!");
            }
        } catch (Exception e) {
            failedScriptCache.add(cleanClassName);
            System.err.println("[ScriptEngine] Failed to instantiate script '" + clazz.getName() + "' on Entity '" + entity.name + "': " + e.getMessage());
        }
    }

    private static String parsePackageDeclaration(File file) {
        if (file == null || !file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = PACKAGE_PATTERN.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static Class<?> compileAndLoadJavaFile(File scriptFile, String className, String declaredPackage) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                System.err.println("[ScriptEngine] JDK JavaCompiler unavailable in current JRE execution environment.");
                return null;
            }

            File outputDir = new File(System.getProperty("java.io.tmpdir"), "retro_compiled_scripts");
            if (!outputDir.exists()) outputDir.mkdirs();

            String currentClasspath = System.getProperty("java.class.path");

            System.out.println("[ScriptEngine] Dynamic compilation triggered for script: " + scriptFile.getAbsolutePath());

            // Bind explicit system application classpath so engine types resolve cleanly
            int exitCode = compiler.run(null, null, null,
                "-classpath", currentClasspath,
                "-d", outputDir.getAbsolutePath(),
                scriptFile.getAbsolutePath()
            );

            if (exitCode == 0) {
                URL outputUrl = outputDir.toURI().toURL();
                if (scriptClassLoader == null) {
                    scriptClassLoader = new URLClassLoader(
                        new URL[]{ outputUrl },
                        EntityScript.class.getClassLoader()
                    );
                }

                String[] candidates;
                if (declaredPackage != null && !declaredPackage.isEmpty()) {
                    candidates = new String[]{
                        declaredPackage + "." + className,
                        "com.retro.engine.scripting.user." + className,
                        "com.retro.engine.scripting." + className,
                        className
                    };
                } else {
                    candidates = new String[]{
                        "com.retro.engine.scripting.user." + className,
                        "com.retro.engine.scripting." + className,
                        className
                    };
                }

                for (String c : candidates) {
                    try {
                        return scriptClassLoader.loadClass(c);
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
