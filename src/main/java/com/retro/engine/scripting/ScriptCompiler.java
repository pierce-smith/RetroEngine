package com.retro.engine.scripting;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles script and mod sources with javac (the {@code jdk.compiler} module) into memory. Scripts get the full Java
 * language of the running JDK: lambdas, {@code var}, records, generics and switch patterns.
 *
 * <p>Each compile returns a {@link ScriptClassLoader} that defines the new classes on top of a parent loader. Classes
 * from earlier compiles in that parent chain, such as a mod's own classes, live only in memory, so javac can't find them
 * on the class path. The file manager serves their bytecode to javac directly.
 */
public final class ScriptCompiler {

    /** A compile that failed. The message lists each error as {@code File.java:line: message}. */
    public static final class CompileException extends Exception {
        public CompileException(String message) { super(message); }
    }

    /** Holds the classes from one compile; defines them on demand and falls back to its parent for everything else. */
    public static final class ScriptClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        ScriptClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = Collections.unmodifiableMap(classes);
        }

        /** Binary names of the classes this compile defined, nested classes included. */
        public Set<String> classNames() { return classes.keySet(); }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.endsWith(".class")) {
                byte[] bytes = classes.get(name.substring(0, name.length() - 6).replace('/', '.'));
                if (bytes != null) return new ByteArrayInputStream(bytes);
            }
            return super.getResourceAsStream(name);
        }
    }

    private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

    private ScriptCompiler() {}

    /** False when the running Java has no {@code jdk.compiler} module, such as a game runtime built without it. */
    public static boolean isAvailable() { return JAVAC != null; }

    public static ScriptClassLoader compile(File source, ClassLoader parent) throws CompileException {
        return compile(List.of(source), parent);
    }

    /** Compiles {@code sources} together, so they may refer to each other, and defines them over {@code parent}. */
    public static ScriptClassLoader compile(List<File> sources, ClassLoader parent) throws CompileException {
        if (JAVAC == null) {
            throw new CompileException("This Java runtime has no compiler (jdk.compiler module), so scripts can't be compiled. "
                    + "Run with a full JDK, or re-export the game with this engine version.");
        }
        if (parent == null) parent = ScriptCompiler.class.getClassLoader();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = JAVAC.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        Map<String, byte[]> output = new LinkedHashMap<>();
        try (MemoryFileManager files = new MemoryFileManager(standard, parent, output)) {
            standard.setLocation(StandardLocation.CLASS_PATH, classPath(parent));
            Iterable<? extends JavaFileObject> units = standard.getJavaFileObjectsFromFiles(sources);
            List<String> options = List.of("-g", "-proc:none", "-parameters", "-nowarn", "-implicit:none");
            Boolean ok = JAVAC.getTask(null, files, diagnostics, options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) throw new CompileException(format(diagnostics));
        } catch (IOException e) {
            throw new CompileException("Compiler I/O failed: " + e.getMessage());
        }
        return new ScriptClassLoader(parent, output);
    }

    private static String format(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder text = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            if (!text.isEmpty()) text.append('\n');
            String file = d.getSource() != null ? new File(d.getSource().toUri()).getName() : "<script>";
            text.append(file).append(':').append(d.getLineNumber()).append(": ").append(d.getMessage(Locale.ROOT));
        }
        return text.isEmpty() ? "compilation failed" : text.toString();
    }

    /**
     * The JVM class path, plus every jar or folder that a URLClassLoader in the parent chain reads, plus the engine's
     * own location. That covers the IDE, Gradle test workers, the packaged editor and exported games.
     */
    static List<File> classPath(ClassLoader parent) {
        Set<File> path = new LinkedHashSet<>();
        String jvm = System.getProperty("java.class.path", "");
        for (String entry : jvm.split(File.pathSeparator)) {
            if (!entry.isBlank()) path.add(new File(entry));
        }
        for (ClassLoader l = parent; l != null; l = l.getParent()) {
            if (l instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) addUrl(path, url);
            }
        }
        CodeSource engine = ScriptCompiler.class.getProtectionDomain().getCodeSource();
        if (engine != null && engine.getLocation() != null) addUrl(path, engine.getLocation());
        List<File> existing = new ArrayList<>();
        for (File f : path) if (f.exists()) existing.add(f);
        return existing;
    }

    private static void addUrl(Set<File> path, URL url) {
        if (!"file".equals(url.getProtocol())) return;
        try {
            path.add(new File(url.toURI()));
        } catch (URISyntaxException | IllegalArgumentException ignored) {}
    }

    /** Sends javac's output to memory and lets it read classes compiled earlier in the parent loader chain. */
    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, byte[]> output;
        private final List<Map.Entry<String, byte[]>> inherited = new ArrayList<>();

        MemoryFileManager(StandardJavaFileManager standard, ClassLoader parent, Map<String, byte[]> output) {
            super(standard);
            this.output = output;
            for (ClassLoader l = parent; l != null; l = l.getParent()) {
                if (l instanceof ScriptClassLoader scripts) {
                    for (Map.Entry<String, byte[]> e : scripts.classes.entrySet()) inherited.add(e);
                }
            }
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            return new SimpleJavaFileObject(memoryUri(className, kind), kind) {
                @Override
                public OutputStream openOutputStream() {
                    return new ByteArrayOutputStream() {
                        @Override
                        public void close() { output.put(className, toByteArray()); }
                    };
                }
            };
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
                throws IOException {
            Iterable<JavaFileObject> base = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.CLASS_PATH || inherited.isEmpty() || !kinds.contains(JavaFileObject.Kind.CLASS)) return base;
            List<JavaFileObject> all = new ArrayList<>();
            base.forEach(all::add);
            for (Map.Entry<String, byte[]> e : inherited) {
                String name = e.getKey();
                int dot = name.lastIndexOf('.');
                String pkg = dot < 0 ? "" : name.substring(0, dot);
                if (pkg.equals(packageName) || (recurse && pkg.startsWith(packageName + "."))) {
                    all.add(new InheritedClass(name, e.getValue()));
                }
            }
            return all;
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof InheritedClass c) return c.binaryName;
            return super.inferBinaryName(location, file);
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            if (a instanceof InheritedClass || b instanceof InheritedClass) return a == b;
            return super.isSameFile(a, b);
        }
    }

    private static final class InheritedClass extends SimpleJavaFileObject {
        final String binaryName;
        private final byte[] bytes;

        InheritedClass(String binaryName, byte[] bytes) {
            super(memoryUri(binaryName, Kind.CLASS), Kind.CLASS);
            this.binaryName = binaryName;
            this.bytes = bytes;
        }

        @Override
        public InputStream openInputStream() { return new ByteArrayInputStream(bytes); }
    }

    private static URI memoryUri(String className, JavaFileObject.Kind kind) {
        return URI.create("mem:///" + className.replace('.', '/') + kind.extension);
    }
}
