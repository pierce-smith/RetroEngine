package com.retro.engine.scripting;

import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import org.codehaus.janino.SimpleCompiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardened ScriptEngine managing script instantiation, lifecycle ticks,
 * physics update dispatches, collision/trigger events, reflection lookups,
 * property overrides injection, ClassLoader caching, Janino in-memory compilation,
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
                        sc.activeScriptInstance = null;
                        sc.enabled = false;
                    }
                }
            }
        }
    }

    public static void physicsUpdateScripts(List<PhysicsSystem.PhysicsBody> activeBodies, float fixedDeltaTime) {
        if (activeBodies == null) return;
        for (int i = 0; i < activeBodies.size(); i++) {
            PhysicsSystem.PhysicsBody body = activeBodies.get(i);
            if (body == null || body.entity == null) continue;
            ScriptComponent sc = body.entity.getComponent(ScriptComponent.class);
            if (sc != null && sc.enabled && sc.activeScriptInstance != null) {
                try {
                    sc.activeScriptInstance.onPhysicsUpdate(fixedDeltaTime);
                } catch (Exception e) {
                    System.err.println("[ScriptEngine] Exception during onPhysicsUpdate() on Entity '" + body.entity.name + "': " + e.getMessage());
                    sc.activeScriptInstance = null;
                    sc.enabled = false;
                }
            }
        }
    }

    public static void dispatchCollisionEnter(Entity entity, Entity other) {
        if (entity == null || other == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc != null && sc.enabled && sc.activeScriptInstance != null) {
            try {
                sc.activeScriptInstance.onCollisionEnter(other);
            } catch (Exception e) {
                System.err.println("[ScriptEngine] Exception during onCollisionEnter() on Entity '" + entity.name + "': " + e.getMessage());
            }
        }
    }

    public static void dispatchCollisionStay(Entity entity, Entity other) {
        if (entity == null || other == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc != null && sc.enabled && sc.activeScriptInstance != null) {
            try {
                sc.activeScriptInstance.onCollisionStay(other);
            } catch (Exception e) {
                System.err.println("[ScriptEngine] Exception during onCollisionStay() on Entity '" + entity.name + "': " + e.getMessage());
            }
        }
    }

    public static void dispatchCollisionExit(Entity entity, Entity other) {
        if (entity == null || other == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc != null && sc.enabled && sc.activeScriptInstance != null) {
            try {
                sc.activeScriptInstance.onCollisionExit(other);
            } catch (Exception e) {
                System.err.println("[ScriptEngine] Exception during onCollisionExit() on Entity '" + entity.name + "': " + e.getMessage());
            }
        }
    }

    public static void dispatchTriggerEnter(Entity entity, Entity other) {
        if (entity == null || other == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc != null && sc.enabled && sc.activeScriptInstance != null) {
            try {
                sc.activeScriptInstance.onTriggerEnter(other);
            } catch (Exception e) {
                System.err.println("[ScriptEngine] Exception during onTriggerEnter() on Entity '" + entity.name + "': " + e.getMessage());
            }
        }
    }

    public static void dispatchTriggerStay(Entity entity, Entity other) {
        if (entity == null || other == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc != null && sc.enabled && sc.activeScriptInstance != null) {
            try {
                sc.activeScriptInstance.onTriggerStay(other);
            } catch (Exception e) {
                System.err.println("[ScriptEngine] Exception during onTriggerStay() on Entity '" + entity.name + "': " + e.getMessage());
            }
        }
    }

    public static void dispatchTriggerExit(Entity entity, Entity other) {
        if (entity == null || other == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc != null && sc.enabled && sc.activeScriptInstance != null) {
            try {
                sc.activeScriptInstance.onTriggerExit(other);
            } catch (Exception e) {
                System.err.println("[ScriptEngine] Exception during onTriggerExit() on Entity '" + entity.name + "': " + e.getMessage());
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

    public static Class<?> resolveScriptClass(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return null;

        File sourceFile = new File(rawName);
        String fileName = sourceFile.getName().trim();

        String cleanClassName = fileName;
        if (cleanClassName.toLowerCase().endsWith(".java")) {
            cleanClassName = cleanClassName.substring(0, cleanClassName.length() - 5);
        } else if (cleanClassName.toLowerCase().endsWith(".class")) {
            cleanClassName = cleanClassName.substring(0, cleanClassName.length() - 6);
        }

        if (failedScriptCache.contains(cleanClassName)) {
            return null;
        }

        Class<?> clazz = compiledClassCache.get(cleanClassName);
        if (clazz != null) return clazz;

        File diskScriptFile = null;
        File f = new File(rawName);
        if (f.exists()) diskScriptFile = f;
        if (diskScriptFile == null && sourceFile.exists()) diskScriptFile = sourceFile;
        if (diskScriptFile == null) {
            File alt = new File("Assets/Scripts/" + fileName);
            if (alt.exists()) diskScriptFile = alt;
        }

        String declaredPackage = parsePackageDeclaration(diskScriptFile);

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
                    return clazz;
                }
            } catch (ClassNotFoundException ignored) {}
        }

        if (diskScriptFile != null && diskScriptFile.exists()) {
            clazz = compileAndLoadJavaFile(diskScriptFile, cleanClassName, declaredPackage);
            if (clazz != null) {
                compiledClassCache.put(cleanClassName, clazz);
                return clazz;
            }
        }

        failedScriptCache.add(cleanClassName);
        return null;
    }

    public static void injectPropertyOverrides(EntityScript script, Map<String, String> overrides) {
        if (script == null || overrides == null || overrides.isEmpty()) return;
        Class<?> clazz = script.getClass();
        while (clazz != null && clazz != EntityScript.class && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || java.lang.reflect.Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                String fieldName = f.getName();
                if (overrides.containsKey(fieldName)) {
                    String strVal = overrides.get(fieldName);
                    try {
                        f.setAccessible(true);
                        setFieldValue(script, f, strVal);
                    } catch (Exception e) {
                        System.err.println("[ScriptEngine] Failed to inject property override '" + fieldName + "' = '" + strVal + "': " + e.getMessage());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    public static void applyPropertyOverride(ScriptComponent sc, String fieldName, String valueString) {
        if (sc == null || fieldName == null) return;
        if (sc.propertyOverrides == null) {
            sc.propertyOverrides = new java.util.HashMap<>();
        }
        if (valueString == null) {
            sc.propertyOverrides.remove(fieldName);
        } else {
            sc.propertyOverrides.put(fieldName, valueString);
        }

        if (sc.activeScriptInstance != null) {
            try {
                Class<?> clazz = sc.activeScriptInstance.getClass();
                while (clazz != null && clazz != EntityScript.class && clazz != Object.class) {
                    try {
                        Field f = clazz.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        setFieldValue(sc.activeScriptInstance, f, valueString);
                        break;
                    } catch (NoSuchFieldException e) {
                        clazz = clazz.getSuperclass();
                    }
                }
            } catch (Exception e) {
                System.err.println("[ScriptEngine] Error applying live script property '" + fieldName + "' = '" + valueString + "': " + e.getMessage());
            }
        }
    }

    private static void setFieldValue(Object target, Field f, String strVal) throws IllegalAccessException {
        if (target == null || f == null || strVal == null) return;
        Class<?> t = f.getType();
        if (t == float.class || t == Float.class) {
            f.setFloat(target, Float.parseFloat(strVal));
        } else if (t == int.class || t == Integer.class) {
            f.setInt(target, Integer.parseInt(strVal));
        } else if (t == boolean.class || t == Boolean.class) {
            f.setBoolean(target, Boolean.parseBoolean(strVal));
        } else if (t == double.class || t == Double.class) {
            f.setDouble(target, Double.parseDouble(strVal));
        } else if (t == long.class || t == Long.class) {
            f.setLong(target, Long.parseLong(strVal));
        } else if (t == String.class) {
            f.set(target, strVal);
        } else if (t.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumVal = Enum.valueOf((Class<Enum>) t, strVal);
            f.set(target, enumVal);
        }
    }

    private static void instantiateAndStartScript(Entity entity, ScriptComponent sc, SceneWorld sceneWorld) {
        if (sc == null) return;
        String rawName = (sc.scriptName != null && !sc.scriptName.trim().isEmpty()) ? sc.scriptName : sc.scriptAssetPath;
        if (rawName == null || rawName.trim().isEmpty()) return;

        Class<?> clazz = resolveScriptClass(rawName);

        if (clazz == null) {
            System.err.println("[ScriptEngine] Could not resolve or compile script class for '" + rawName + "'. Skipping further attempts.");
            return;
        }

        try {
            if (EntityScript.class.isAssignableFrom(clazz)) {
                EntityScript script = (EntityScript) clazz.getDeclaredConstructor().newInstance();
                script.init(entity, sceneWorld);
                sc.activeScriptInstance = script;

                // Pillar 3: Inject reflected property overrides before calling onStart()
                injectPropertyOverrides(script, sc.propertyOverrides);

                script.onStart();
                System.out.println("[ScriptEngine] Successfully initialized script '" + clazz.getSimpleName() + "' on Entity '" + entity.name + "'");
            } else {
                failedScriptCache.add(clazz.getSimpleName());
                System.err.println("[ScriptEngine] Class '" + clazz.getName() + "' does not extend EntityScript!");
            }
        } catch (Exception e) {
            failedScriptCache.add(clazz.getSimpleName());
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
        System.out.println("[ScriptEngine] Dynamic in-memory Janino compilation triggered for script: " + scriptFile.getAbsolutePath());

        try {
            SimpleCompiler compiler = new SimpleCompiler();
            compiler.setParentClassLoader(EntityScript.class.getClassLoader());

            try (FileReader reader = new FileReader(scriptFile)) {
                compiler.cook(scriptFile.getAbsolutePath(), reader);
            }

            ClassLoader cl = compiler.getClassLoader();
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
                    Class<?> compiledClass = cl.loadClass(c);
                    if (compiledClass != null) {
                        System.out.println("[ScriptEngine] Janino successfully compiled script in-memory: " + compiledClass.getName());
                        return compiledClass;
                    }
                } catch (ClassNotFoundException ignored) {}
            }
        } catch (Throwable janinoError) {
            System.err.println("[ScriptEngine] Janino in-memory compilation error in '" + scriptFile.getName() + "': " + janinoError.getMessage());
            janinoError.printStackTrace();
        }

        return null;
    }
}
