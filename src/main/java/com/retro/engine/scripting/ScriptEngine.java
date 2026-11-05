package com.retro.engine.scripting;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.physics.PhysicsSystem;

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
 * hot-loop exception suppression, and live hot reload of edited script sources.
 */
public class ScriptEngine {

    // Caches successfully loaded classes by clean simple name / key
    private static final Map<String, Class<?>> compiledClassCache = new ConcurrentHashMap<>();
    
    // Negative failure cache preventing hot-loop re-compilation/re-resolution on every frame tick
    private static final Set<String> failedScriptCache = ConcurrentHashMap.newKeySet();

    // Every script error since the process started, so headless test runs can fail on them
    private static final java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger();

    /** Scripts that failed to compile or load, and script callbacks that threw, since the process started. */
    public static int getFailureCount() {
        return failureCount.get();
    }

    // Reusable URLClassLoader to prevent Metaspace and classloader leaks across re-compilations
    private static URLClassLoader scriptClassLoader;

    // Scripts whose source changed on disk; running instances are swapped at the start of the next updateScripts()
    private static final Set<String> pendingHotSwaps = ConcurrentHashMap.newKeySet();

    // Active project's Assets/ folder; script sources are searched here before the working directory
    private static volatile String projectAssetsRoot = null;

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

    /**
     * Hot reload entry point for a script source modified on disk: drops its compiled class and any cached compile
     * failure so the next resolve recompiles it, and schedules running instances for a swap on the next script tick.
     */
    public static void invalidateScript(String scriptPath) {
        String className = cleanClassName(scriptPath);
        if (className == null) return;
        compiledClassCache.remove(className);
        failedScriptCache.remove(className);
        pendingHotSwaps.add(className);
        System.out.println("[ScriptEngine] Script source changed on disk: " + className + " (queued for hot reload).");
    }

    /** Binds script source lookup to a project's Assets/ folder (null to unbind) and drops all cached classes. */
    /** Assets/ folder of the open project (set by the editor or standalone runtime), or null when none is known. */
    public static String getProjectAssetsRoot() {
        return projectAssetsRoot;
    }

    public static void setProjectAssetsRoot(String assetsRoot) {
        projectAssetsRoot = (assetsRoot != null && !assetsRoot.trim().isEmpty()) ? assetsRoot.trim() : null;
        clearCache();
    }

    /** Resolves a component's script: class name from scriptName, source file from scriptAssetPath. */
    public static Class<?> resolveScriptClass(ScriptComponent sc) {
        if (sc == null) return null;
        String rawName = (sc.scriptName != null && !sc.scriptName.trim().isEmpty()) ? sc.scriptName : sc.scriptAssetPath;
        return resolveScriptClass(rawName, sc.scriptAssetPath);
    }

    /*
     * Hot loops below index into the live lists instead of using iterators or Entity.getComponents (which builds a new
     * list): no per-frame allocation, and a script that spawns entities or adds components mid-tick cannot trigger a
     * ConcurrentModificationException. Newly spawned entities are picked up in the same pass.
     */

    public static void initializeScripts(List<Entity> entities, SceneWorld sceneWorld) {
        if (entities == null) return;
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (entity == null) continue;
            List<Component> comps = entity.getAllComponents();
            for (int c = 0; c < comps.size(); c++) {
                if (comps.get(c) instanceof ScriptComponent sc && sc.enabled && sc.activeScriptInstance == null) {
                    instantiateAndStartScript(entity, sc, sceneWorld);
                }
            }
        }
    }

    public static void updateScripts(List<Entity> entities, float deltaTime, SceneWorld sceneWorld) {
        if (entities == null) return;
        if (!pendingHotSwaps.isEmpty()) hotSwapScripts(entities, sceneWorld);
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (entity == null) continue;
            List<Component> comps = entity.getAllComponents();
            for (int c = 0; c < comps.size(); c++) {
                if (!(comps.get(c) instanceof ScriptComponent sc) || !sc.enabled) continue;
                if (sc.activeScriptInstance == null) {
                    instantiateAndStartScript(entity, sc, sceneWorld);
                }
                EntityScript script = sc.activeScriptInstance;
                if (script == null) continue;
                try {
                    script.onFrameworkUpdate(deltaTime);
                    script.onUpdate(deltaTime);
                } catch (Throwable t) {
                    disableFailedScript(entity, sc, "onUpdate", t);
                }
            }
        }
    }

    /**
     * Turns off one script instance that threw, leaving every other script and the engine loop running. The error and
     * a trimmed stack trace go to System.err, which the editor console shows. The component's enabled flag is part
     * of the Play snapshot, so the script runs again after Stop.
     */
    private static void disableFailedScript(Entity entity, ScriptComponent sc, String callback, Throwable t) {
        if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
        EntityScript script = sc.activeScriptInstance;
        String scriptName = script != null ? script.getClass().getSimpleName() : String.valueOf(sc.scriptName);
        sc.activeScriptInstance = null;
        sc.enabled = false;
        logScriptError("[ScriptEngine] " + scriptName + "." + callback + "() threw on Entity '" + entity.name
                + "'; the script is disabled until Play is stopped.", t);
    }

    private static final int MAX_LOGGED_FRAMES = 8;

    /** Header, the exception, and its first frames (the console shows few rows, so full traces would bury the cause). */
    static void logScriptError(String header, Throwable t) {
        failureCount.incrementAndGet();
        StringBuilder sb = new StringBuilder(512).append(header);
        for (Throwable cause = t; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            sb.append(cause == t ? "\n  " : "\n  Caused by: ").append(cause);
            StackTraceElement[] frames = cause.getStackTrace();
            int shown = Math.min(frames.length, MAX_LOGGED_FRAMES);
            for (int i = 0; i < shown; i++) sb.append("\n    at ").append(frames[i]);
            if (frames.length > shown) sb.append("\n    ... ").append(frames.length - shown).append(" more");
        }
        System.err.println(sb);
    }

    /**
     * Frame boundary swap for invalidated scripts: recompiles each one once, then replaces every running instance with
     * a fresh one (old onDestroy(), new init(), property overrides re-bound, onStart()). If the source no longer compiles
     * or the new class cannot be constructed, the old instance keeps running. Components without a running instance need
     * nothing: they resolve the recompiled class whenever they next instantiate.
     */
    private static void hotSwapScripts(List<Entity> entities, SceneWorld sceneWorld) {
        Set<String> classNames = new java.util.HashSet<>();
        for (java.util.Iterator<String> it = pendingHotSwaps.iterator(); it.hasNext(); ) {
            classNames.add(it.next());
            it.remove();
        }

        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (entity == null) continue;
            for (ScriptComponent sc : entity.getComponents(ScriptComponent.class)) {
                EntityScript previous = sc.activeScriptInstance;
                if (previous == null) continue;
                String className = cleanClassName((sc.scriptName != null && !sc.scriptName.trim().isEmpty()) ? sc.scriptName : sc.scriptAssetPath);
                if (className == null || !classNames.contains(className)) continue;

                Class<?> clazz = resolveScriptClass(sc);
                // Unchanged class: e.g. Play started after the edit, so this instance already runs the recompiled code
                if (clazz == previous.getClass()) continue;
                if (clazz == null || !EntityScript.class.isAssignableFrom(clazz)) {
                    System.err.println("[ScriptEngine] Hot reload of '" + className + "' failed; Entity '" + entity.name + "' keeps the previous version.");
                    continue;
                }

                EntityScript replacement;
                try {
                    replacement = (EntityScript) clazz.getDeclaredConstructor().newInstance();
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
                    logScriptError("[ScriptEngine] Hot reload could not construct '" + className + "' on Entity '" + entity.name + "'; keeping the previous version.", t);
                    continue;
                }

                try {
                    previous.onDestroy();
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
                    logScriptError("[ScriptEngine] " + className + ".onDestroy() threw during hot reload on Entity '" + entity.name + "'.", t);
                }
                runFrameworkDestroy(previous, entity);

                sc.activeScriptInstance = replacement;
                try {
                    replacement.init(entity, sceneWorld);
                    injectPropertyOverrides(replacement, sc.propertyOverrides);
                    replacement.onStart();
                    System.out.println("[ScriptEngine] Hot reloaded script '" + className + "' on Entity '" + entity.name + "'");
                } catch (Throwable t) {
                    disableFailedScript(entity, sc, "onStart (hot reload)", t);
                }
            }
        }
    }

    public static void physicsUpdateScripts(List<PhysicsSystem.PhysicsBody> activeBodies, float fixedDeltaTime) {
        if (activeBodies == null) return;
        for (int i = 0; i < activeBodies.size(); i++) {
            PhysicsSystem.PhysicsBody body = activeBodies.get(i);
            if (body == null || body.entity == null) continue;
            List<Component> comps = body.entity.getAllComponents();
            for (int c = 0; c < comps.size(); c++) {
                if (!(comps.get(c) instanceof ScriptComponent sc) || !sc.enabled) continue;
                EntityScript script = sc.activeScriptInstance;
                if (script == null) continue;
                try {
                    script.onPhysicsUpdate(fixedDeltaTime);
                } catch (Throwable t) {
                    disableFailedScript(body.entity, sc, "onPhysicsUpdate", t);
                }
            }
        }
    }

    private enum Contact { COLLISION_ENTER, COLLISION_STAY, COLLISION_EXIT, TRIGGER_ENTER, TRIGGER_STAY, TRIGGER_EXIT }

    public static void dispatchCollisionEnter(Entity entity, Entity other) { dispatchContact(entity, other, Contact.COLLISION_ENTER); }
    public static void dispatchCollisionStay(Entity entity, Entity other) { dispatchContact(entity, other, Contact.COLLISION_STAY); }
    public static void dispatchCollisionExit(Entity entity, Entity other) { dispatchContact(entity, other, Contact.COLLISION_EXIT); }
    public static void dispatchTriggerEnter(Entity entity, Entity other) { dispatchContact(entity, other, Contact.TRIGGER_ENTER); }
    public static void dispatchTriggerStay(Entity entity, Entity other) { dispatchContact(entity, other, Contact.TRIGGER_STAY); }
    public static void dispatchTriggerExit(Entity entity, Entity other) { dispatchContact(entity, other, Contact.TRIGGER_EXIT); }

    /** Delivers a collision / trigger event to every enabled, running script on {@code entity}. A script that throws is disabled. */
    private static void dispatchContact(Entity entity, Entity other, Contact contact) {
        if (entity == null || other == null) return;
        List<Component> comps = entity.getAllComponents();
        for (int c = 0; c < comps.size(); c++) {
            if (!(comps.get(c) instanceof ScriptComponent sc) || !sc.enabled) continue;
            EntityScript script = sc.activeScriptInstance;
            if (script == null) continue;
            try {
                switch (contact) {
                    case COLLISION_ENTER -> script.onCollisionEnter(other);
                    case COLLISION_STAY -> script.onCollisionStay(other);
                    case COLLISION_EXIT -> script.onCollisionExit(other);
                    case TRIGGER_ENTER -> script.onTriggerEnter(other);
                    case TRIGGER_STAY -> script.onTriggerStay(other);
                    case TRIGGER_EXIT -> script.onTriggerExit(other);
                }
            } catch (Throwable t) {
                disableFailedScript(entity, sc, callbackName(contact), t);
            }
        }
    }

    private static String callbackName(Contact contact) {
        return switch (contact) {
            case COLLISION_ENTER -> "onCollisionEnter";
            case COLLISION_STAY -> "onCollisionStay";
            case COLLISION_EXIT -> "onCollisionExit";
            case TRIGGER_ENTER -> "onTriggerEnter";
            case TRIGGER_STAY -> "onTriggerStay";
            case TRIGGER_EXIT -> "onTriggerExit";
        };
    }

    public static void destroyScripts(List<Entity> entities) {
        if (entities == null) return;
        for (int i = 0; i < entities.size(); i++) {
            destroyEntityScripts(entities.get(i));
        }
    }

    /** Runs onDestroy on every live script of one entity (e.g. one destroyed mid-play) and detaches the instances. */
    public static void destroyEntityScripts(Entity entity) {
        if (entity == null) return;
        List<Component> comps = entity.getAllComponents();
        for (int c = 0; c < comps.size(); c++) {
            if (!(comps.get(c) instanceof ScriptComponent sc) || sc.activeScriptInstance == null) continue;
            EntityScript script = sc.activeScriptInstance;
            sc.activeScriptInstance = null;
            try {
                script.onDestroy();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
                logScriptError("[ScriptEngine] " + script.getClass().getSimpleName() + ".onDestroy() threw on Entity '" + entity.name + "'.", t);
            }
            runFrameworkDestroy(script, entity);
        }
    }

    private static void runFrameworkDestroy(EntityScript script, Entity entity) {
        try {
            script.onFrameworkDestroy();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
            logScriptError("[ScriptEngine] " + script.getClass().getSimpleName() + " framework cleanup threw on Entity '" + (entity != null ? entity.name : "?") + "'.", t);
        }
    }

    public static Class<?> resolveScriptClass(String rawName) {
        return resolveScriptClass(rawName, null);
    }

    public static Class<?> resolveScriptClass(String rawName, String sourcePathHint) {
        String cleanClassName = cleanClassName(rawName);
        if (cleanClassName == null) return null;

        if (failedScriptCache.contains(cleanClassName)) {
            return null;
        }

        Class<?> clazz = compiledClassCache.get(cleanClassName);
        if (clazz != null) return clazz;

        File diskScriptFile = locateScriptSource(rawName, sourcePathHint, cleanClassName + ".java");

        String declaredPackage = parsePackageDeclaration(diskScriptFile);

        String[] candidateClassNames = candidateNames(declaredPackage, cleanClassName);

        for (String candidate : candidateClassNames) {
            try {
                clazz = Class.forName(candidate, true, scriptParentLoader());
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

    /** Fully qualified names a script class may have: its declared package, the default script packages, then none. */
    private static String[] candidateNames(String declaredPackage, String className) {
        String[] defaults = { "com.retro.engine.scripting.user." + className, "com.retro.engine.scripting." + className, className };
        if (declaredPackage == null || declaredPackage.isEmpty()) return defaults;
        return new String[] { declaredPackage + "." + className, defaults[0], defaults[1], defaults[2] };
    }

    /** Simple class name (the class cache key) for a script name or path: file name without .java / .class. */
    private static String cleanClassName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return null;
        String name = new File(rawName.trim()).getName().trim();
        if (name.toLowerCase().endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        } else if (name.toLowerCase().endsWith(".class")) {
            name = name.substring(0, name.length() - 6);
        }
        return name.isEmpty() ? null : name;
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

        Class<?> clazz = resolveScriptClass(sc);

        // Unresolvable or wrong-type scripts are switched off for this Play session (restored by Stop), so the
        // per-frame tick does not retry them and print the same error every frame
        if (clazz == null) {
            sc.enabled = false;
            failureCount.incrementAndGet();
            System.err.println("[ScriptEngine] Could not resolve or compile script class for '" + rawName + "'; disabled until Play is stopped.");
            return;
        }
        if (!EntityScript.class.isAssignableFrom(clazz)) {
            sc.enabled = false;
            failureCount.incrementAndGet();
            System.err.println("[ScriptEngine] Class '" + clazz.getName() + "' does not extend EntityScript; disabled until Play is stopped.");
            return;
        }

        EntityScript script;
        try {
            script = (EntityScript) clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            disableFailedScript(entity, sc, "<init>", t);
            return;
        }
        sc.activeScriptInstance = script;
        try {
            script.init(entity, sceneWorld);
            injectPropertyOverrides(script, sc.propertyOverrides);
            script.onStart();
            System.out.println("[ScriptEngine] Started script '" + clazz.getSimpleName() + "' on Entity '" + entity.name + "'");
        } catch (Throwable t) {
            disableFailedScript(entity, sc, "onAwake/onStart", t);
        }
    }

    /** Source lookup order: explicit asset path, raw name, project Assets/Scripts, project Assets/ tree, working-dir Assets/Scripts. */
    private static File locateScriptSource(String rawName, String sourcePathHint, String javaFileName) {
        if (sourcePathHint != null && !sourcePathHint.trim().isEmpty()) {
            File hint = new File(sourcePathHint.trim());
            if (hint.isFile()) return hint;
        }
        File raw = new File(rawName);
        if (raw.isFile()) return raw;

        String root = projectAssetsRoot;
        if (root != null) {
            File direct = new File(root, "Scripts/" + javaFileName);
            if (direct.isFile()) return direct;
            try (var stream = java.nio.file.Files.walk(new File(root).toPath())) {
                File found = stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(javaFileName))
                    .map(java.nio.file.Path::toFile)
                    .findFirst().orElse(null);
                if (found != null) return found;
            } catch (IOException ignored) {}
        }

        File alt = new File("Assets/Scripts/" + javaFileName);
        return alt.isFile() ? alt : null;
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

    /** Active mod class loader chain, falling back to the engine's own class loader. */
    private static ClassLoader scriptParentLoader() {
        ClassLoader l = com.retro.engine.modding.ModLoader.scriptParentClassLoader();
        return (l != null) ? l : com.retro.engine.core.EngineContext.class.getClassLoader();
    }

    private static Class<?> compileAndLoadJavaFile(File scriptFile, String className, String declaredPackage) {
        System.out.println("[ScriptEngine] Dynamic in-memory Janino compilation triggered for script: " + scriptFile.getAbsolutePath());

        try {
            SimpleCompiler compiler = new SimpleCompiler();
            // Explicit parent: the mod class loader chain while mods are loaded (so scripts can use mod components),
            // else the engine's own loader. Never Janino's default (the thread context loader), which in a packaged
            // app may not see engine classes.
            ClassLoader parent = scriptParentLoader();
            compiler.setParentClassLoader(parent);

            Thread thread = Thread.currentThread();
            ClassLoader previousContext = thread.getContextClassLoader();
            thread.setContextClassLoader(parent);
            try (FileReader reader = new FileReader(scriptFile)) {
                compiler.cook(scriptFile.getAbsolutePath(), reader);
            } finally {
                thread.setContextClassLoader(previousContext);
            }

            ClassLoader cl = compiler.getClassLoader();
            String[] candidates = candidateNames(declaredPackage, className);

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
