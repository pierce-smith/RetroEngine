package com.retro.engine.api;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads the {@link PublicApi} surface out of the compiled engine classes: the text form written to the baseline
 * ({@code api/retroengine.api}) and the list of signatures that mention non-API types.
 *
 * <p>Run as a program ({@code ./gradlew apiDump}) to rewrite the baseline.
 */
public final class ApiSurface {

    public static final String BASELINE = "api/retroengine.api";
    private static final String ENGINE_PREFIX = "com.retro.engine.";
    private static final String EDITOR_PREFIX = "com.retro.engine.editor.";

    /** Types marked {@link PublicApi}, including public nested types of marked types. */
    public final Set<Class<?>> fullTypes = new LinkedHashSet<>();
    /** Unmarked types with at least one {@link PublicApi} member. */
    public final Set<Class<?>> partialTypes = new LinkedHashSet<>();
    /** Signatures that mention non-API types, and other rule breaks, one line each. */
    public final Set<String> leaks = new TreeSet<>();

    private ApiSurface() {}

    public static ApiSurface scan() throws IOException {
        ApiSurface s = new ApiSurface();
        List<Class<?>> all = engineClasses();
        for (Class<?> c : all) {
            if (isFullApi(c)) s.fullTypes.add(c);
        }
        for (Class<?> c : all) {
            if (s.fullTypes.contains(c) || !Modifier.isPublic(c.getModifiers())) continue;
            if (members(c).stream().anyMatch(m -> ((AnnotatedElement) m).isAnnotationPresent(PublicApi.class))) s.partialTypes.add(c);
        }
        s.checkLeaks();
        return s;
    }

    public boolean isApiType(Class<?> c) {
        return fullTypes.contains(c) || partialTypes.contains(c);
    }

    /** Public, marked (or nested in a marked type, unless marked {@link Internal}), and not editor code. */
    private static boolean isFullApi(Class<?> c) {
        if (c.isAnonymousClass() || c.isLocalClass() || c.isSynthetic()) return false;
        if (!Modifier.isPublic(c.getModifiers()) || c.isAnnotationPresent(Internal.class)) return false;
        if (c.isAnnotationPresent(PublicApi.class)) return true;
        Class<?> outer = c.getDeclaringClass();
        return outer != null && isFullApi(outer);
    }

    private static boolean isExperimental(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getDeclaringClass()) if (k.isAnnotationPresent(Experimental.class)) return true;
        return false;
    }

    /** Public/protected, non-synthetic declared members, in a stable order. */
    private static List<Member> members(Class<?> c) {
        List<Member> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) if (visible(f.getModifiers()) && !f.isSynthetic()) out.add(f);
        for (Constructor<?> k : c.getDeclaredConstructors()) if (visible(k.getModifiers()) && !k.isSynthetic()) out.add(k);
        for (Method m : c.getDeclaredMethods()) if (visible(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) out.add(m);
        return out;
    }

    private static boolean visible(int mod) {
        return Modifier.isPublic(mod) || Modifier.isProtected(mod);
    }

    /** API members of an API type: everything in a marked type except {@link Internal}; only marked ones in a partial type. */
    public List<Member> apiMembers(Class<?> c) {
        List<Member> out = new ArrayList<>();
        boolean full = fullTypes.contains(c);
        for (Member m : members(c)) {
            AnnotatedElement a = (AnnotatedElement) m;
            if (full ? !a.isAnnotationPresent(Internal.class) : a.isAnnotationPresent(PublicApi.class)) {
                // Enum plumbing the compiler writes for every enum
                if (c.isEnum() && m instanceof Method method && method.getName().equals("$values")) continue;
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Leak check
    // ------------------------------------------------------------------------------------------

    private void checkLeaks() {
        for (Class<?> c : allApiTypes()) {
            if (c.getName().startsWith(EDITOR_PREFIX)) leaks.add(name(c) + ": editor types can't be public API");
            if (fullTypes.contains(c)) {
                checkType(c.getGenericSuperclass(), name(c) + " extends");
                for (Type t : c.getGenericInterfaces()) checkType(t, name(c) + " implements");
            }
            for (Member m : apiMembers(c)) {
                String where = name(c) + "." + memberName(m);
                if (m instanceof Field f) {
                    checkType(f.getGenericType(), where);
                } else {
                    Executable e = (Executable) m;
                    if (e instanceof Method method) checkType(method.getGenericReturnType(), where);
                    for (Type t : e.getGenericParameterTypes()) checkType(t, where);
                    for (Type t : e.getGenericExceptionTypes()) checkType(t, where);
                    for (TypeVariable<?> v : e.getTypeParameters()) for (Type b : v.getBounds()) checkType(b, where);
                }
            }
        }
    }

    private void checkType(Type t, String where) {
        if (t == null) return;
        if (t instanceof Class<?> k) {
            while (k.isArray()) k = k.getComponentType();
            if (k.isPrimitive() || k.getName().startsWith("java.")) return;
            if (!isApiType(k)) leaks.add(where + " exposes " + k.getName() + ", which isn't public API");
        } else if (t instanceof ParameterizedType p) {
            checkType(p.getRawType(), where);
            for (Type a : p.getActualTypeArguments()) checkType(a, where);
        } else if (t instanceof GenericArrayType g) {
            checkType(g.getGenericComponentType(), where);
        } else if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) checkType(b, where);
            for (Type b : w.getLowerBounds()) checkType(b, where);
        } else if (t instanceof TypeVariable<?> v) {
            for (Type b : v.getBounds()) if (b != t) checkType(b, where);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Text form
    // ------------------------------------------------------------------------------------------

    public List<Class<?>> allApiTypes() {
        List<Class<?>> out = new ArrayList<>(fullTypes);
        out.addAll(partialTypes);
        out.sort(Comparator.comparing(Class::getName));
        return out;
    }

    /** One line per type header and per member. Sorted, so the file diffs cleanly. */
    public List<String> render() {
        List<String> out = new ArrayList<>();
        out.add("# RetroEngine public API. Generated by ./gradlew apiDump; checked by PublicApiTest. Don't edit by hand.");
        for (Class<?> c : allApiTypes()) {
            boolean typeExperimental = isExperimental(c);
            String header = typeHeader(c) + (partialTypes.contains(c) ? " [partial]" : "") + (typeExperimental ? " [experimental]" : "");
            out.add(header);
            TreeSet<String> lines = new TreeSet<>();
            for (Member m : apiMembers(c)) {
                boolean experimental = typeExperimental || ((AnnotatedElement) m).isAnnotationPresent(Experimental.class);
                lines.add("  " + memberSignature(c, m) + (experimental ? " [experimental]" : ""));
            }
            out.addAll(lines);
        }
        return out;
    }

    private static String typeHeader(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        int mod = c.getModifiers() & ~Modifier.INTERFACE;
        if (c.isInterface()) mod &= ~Modifier.ABSTRACT;
        if (c.isEnum() || c.isRecord()) mod &= ~Modifier.FINAL; // implied
        String mods = Modifier.toString(mod);
        if (!mods.isEmpty()) sb.append(mods).append(' ');
        sb.append(c.isAnnotation() ? "@interface " : c.isInterface() ? "interface " : c.isEnum() ? "enum " : c.isRecord() ? "record " : "class ");
        sb.append(c.getName());
        TypeVariable<?>[] vars = c.getTypeParameters();
        if (vars.length > 0) {
            sb.append('<');
            for (int i = 0; i < vars.length; i++) sb.append(i > 0 ? "," : "").append(vars[i].getTypeName());
            sb.append('>');
        }
        Type sup = c.getGenericSuperclass();
        if (sup != null && sup != Object.class && !c.isEnum() && !c.isRecord()) sb.append(" extends ").append(sup.getTypeName());
        Type[] ifaces = c.isAnnotation() ? new Type[0] : c.getGenericInterfaces();
        if (ifaces.length > 0) {
            sb.append(c.isInterface() ? " extends " : " implements ");
            for (int i = 0; i < ifaces.length; i++) sb.append(i > 0 ? ", " : "").append(ifaces[i].getTypeName());
        }
        return sb.toString();
    }

    private static String memberSignature(Class<?> c, Member m) {
        String s = m instanceof Field f ? f.toGenericString()
                : m instanceof Method method ? method.toGenericString()
                : ((Constructor<?>) m).toGenericString();
        // "public int com.x.Foo.bar(int)" -> "public int bar(int)"; constructors keep the simple class name
        String kind = m instanceof Field ? "field " : m instanceof Method ? "method " : "ctor ";
        return kind + s.replace(c.getName() + ".", "");
    }

    private static String memberName(Member m) {
        return m instanceof Constructor<?> ? "<init>" : m.getName();
    }

    private static String name(Class<?> c) {
        return c.getName().startsWith(ENGINE_PREFIX) ? c.getName().substring(ENGINE_PREFIX.length()) : c.getName();
    }

    // ------------------------------------------------------------------------------------------
    // Class discovery
    // ------------------------------------------------------------------------------------------

    /** Every class compiled into the engine's main output (the folder or jar holding {@link PublicApi}). */
    private static List<Class<?>> engineClasses() throws IOException {
        Path root;
        try {
            root = Path.of(PublicApi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        TreeMap<String, Class<?>> out = new TreeMap<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (rel.endsWith(".class") && !rel.endsWith("module-info.class") && !rel.endsWith("package-info.class")) add(out, rel);
                }
            }
        } else {
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(root.toFile())) {
                jar.stream().map(java.util.jar.JarEntry::getName)
                        .filter(n -> n.endsWith(".class") && !n.endsWith("module-info.class") && !n.endsWith("package-info.class"))
                        .forEach(n -> add(out, n));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(TreeMap<String, Class<?>> out, String path) {
        String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        if (!name.startsWith(ENGINE_PREFIX)) return;
        try {
            out.put(name, Class.forName(name, false, ApiSurface.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("Can't load " + name + " to read the API surface", e);
        }
    }

    /** {@code apiDump}: rewrites the baseline. Argument: the baseline path (default {@value #BASELINE}). */
    public static void main(String[] args) throws IOException {
        ApiSurface s = scan();
        Path out = Path.of(args.length > 0 ? args[0] : BASELINE);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, s.render(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + out + ": " + s.fullTypes.size() + " types, " + s.partialTypes.size() + " partial.");
        if (!s.leaks.isEmpty()) {
            System.out.println(s.leaks.size() + " leak(s); PublicApiTest will fail until they're fixed:");
            s.leaks.forEach(l -> System.out.println("  " + l));
        }
    }
}
