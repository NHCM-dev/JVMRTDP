package nhcm.jvmrtdp.controllerside.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Controller-side catalog of class files that are discoverable on a target JVM's
 * application class path. Cataloging reads files without defining or initializing
 * the classes in the target VM.
 */
public final class JvmClassPathCatalog {
    private final Map<String, ClassEntry> classes;
    private final Set<String> loadedClassNames;

    private JvmClassPathCatalog(Map<String, ClassEntry> classes, Collection<String> loaded) {
        this.classes = Collections.unmodifiableMap(
                new LinkedHashMap<String, ClassEntry>(classes));
        this.loadedClassNames = Collections.unmodifiableSet(
                new LinkedHashSet<String>(loaded));
    }

    public static JvmClassPathCatalog discover(
            String classPath, String userDirectory, Collection<String> loaded) throws IOException {
        return discover(classPath, userDirectory, "", loaded);
    }

    /** Also scans Java 8 {@code rt.jar} or Java 9+ {@code jmods} when available. */
    public static JvmClassPathCatalog discover(String classPath, String userDirectory,
            String javaHome, Collection<String> loaded) throws IOException {
        Map<String, ClassEntry> result = new LinkedHashMap<String, ClassEntry>();
        String base = userDirectory == null || userDirectory.trim().isEmpty()
                ? "." : userDirectory.trim();
        String value = classPath == null ? "" : classPath;
        for (String raw : value.split(java.io.File.pathSeparator, -1)) {
            if (raw.trim().isEmpty()) continue;
            Path path = Paths.get(raw.trim());
            if (!path.isAbsolute()) path = Paths.get(base).resolve(path);
            path = path.normalize().toAbsolutePath();
            if (Files.isDirectory(path)) scanDirectory(path, result);
            else if (Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                scanArchive(path, "", result);
            }
        }
        scanRuntime(javaHome, result);
        return new JvmClassPathCatalog(result,
                loaded == null ? Collections.<String>emptyList() : loaded);
    }

    public int size() { return classes.size(); }

    public int unloadedSize() {
        int count = 0;
        for (String name : classes.keySet()) if (!loadedClassNames.contains(name)) count++;
        return count;
    }

    public ClassEntry find(String className) {
        return classes.get(normalize(className));
    }

    public boolean isLoaded(String className) {
        return loadedClassNames.contains(normalize(className));
    }

    public List<ClassEntry> searchUnloaded(String pattern, int limit) {
        String glob = pattern == null || pattern.trim().isEmpty() ? "*" : pattern.trim();
        List<ClassEntry> result = new ArrayList<ClassEntry>();
        for (ClassEntry entry : classes.values()) {
            if (loadedClassNames.contains(entry.name()) || !globMatches(glob, entry.name())) continue;
            result.add(entry);
            if (result.size() >= limit) break;
        }
        sortClasses(result);
        return Collections.unmodifiableList(result);
    }

    /** Searches symbolic members without loading their owner classes. */
    public List<MemberMatch> searchUnloadedMembers(String ownerPattern, String memberPattern,
            MemberKind kind, int limit) throws IOException {
        String ownerGlob = ownerPattern == null || ownerPattern.isEmpty() ? "*" : ownerPattern;
        String memberGlob = memberPattern == null || memberPattern.isEmpty() ? "*" : memberPattern;
        List<MemberMatch> result = new ArrayList<MemberMatch>();
        for (ClassEntry owner : classes.values()) {
            if (loadedClassNames.contains(owner.name()) || !globMatches(ownerGlob, owner.name())) continue;
            List<Member> members = kind == MemberKind.FIELD
                    ? owner.metadata().fields() : owner.metadata().methods();
            for (Member member : members) {
                if (!globMatches(memberGlob, member.name())) continue;
                result.add(new MemberMatch(owner, member));
                if (result.size() >= limit) return Collections.unmodifiableList(result);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Immediate child packages and unloaded classes for a package-browser page. */
    public PackageView packageView(String packageName) {
        String requested = normalizePackage(packageName);
        String prefix = requested.isEmpty() ? "" : requested + ".";
        Set<String> packages = new LinkedHashSet<String>();
        List<ClassEntry> entries = new ArrayList<ClassEntry>();
        for (ClassEntry entry : classes.values()) {
            if (loadedClassNames.contains(entry.name()) || !entry.name().startsWith(prefix)) continue;
            String remaining = entry.name().substring(prefix.length());
            int separator = remaining.indexOf('.');
            if (separator >= 0) packages.add(prefix + remaining.substring(0, separator));
            else entries.add(entry);
        }
        List<String> packageList = new ArrayList<String>(packages);
        Collections.sort(packageList, String.CASE_INSENSITIVE_ORDER);
        sortClasses(entries);
        return new PackageView(requested, packageList, entries);
    }

    private static void scanDirectory(final Path root, Map<String, ClassEntry> result)
            throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!eligibleResource(relative)) return;
                String name = resourceClassName(relative);
                if (!result.containsKey(name)) {
                    result.put(name, new ClassEntry(name, root.toString(), path, null));
                }
            });
        }
    }

    private static void scanRuntime(String javaHome, Map<String, ClassEntry> result)
            throws IOException {
        if (javaHome == null || javaHome.trim().isEmpty()) return;
        Path home = Paths.get(javaHome.trim()).toAbsolutePath().normalize();
        Path[] runtimeJars = { home.resolve("lib/rt.jar"), home.resolve("jre/lib/rt.jar") };
        for (Path jar : runtimeJars) {
            if (Files.isRegularFile(jar)) scanArchive(jar, "", result);
        }
        Path jmods = home.resolve("jmods");
        if (Files.isDirectory(jmods)) {
            try (java.util.stream.Stream<Path> paths = Files.list(jmods)) {
                java.util.Iterator<Path> iterator = paths
                        .filter(path -> path.getFileName().toString().endsWith(".jmod"))
                        .sorted().iterator();
                while (iterator.hasNext()) scanArchive(iterator.next(), "classes/", result);
            }
        }
    }

    private static void scanArchive(Path jarPath, String resourcePrefix,
            Map<String, ClassEntry> result) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String resource = entry.getName();
                if (entry.isDirectory() || !resource.startsWith(resourcePrefix)) continue;
                String classResource = resource.substring(resourcePrefix.length());
                if (!eligibleResource(classResource)) continue;
                String name = resourceClassName(classResource);
                if (!result.containsKey(name)) {
                    result.put(name, new ClassEntry(name, jarPath.toString(), jarPath, resource));
                }
            }
        }
    }

    private static boolean eligibleResource(String resource) {
        return resource.endsWith(".class") && !resource.startsWith("META-INF/")
                && !resource.equals("module-info.class")
                && !resource.endsWith("/module-info.class");
    }

    private static String resourceClassName(String resource) {
        return resource.substring(0, resource.length() - 6).replace('/', '.').replace('\\', '.');
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String result = value.trim().replace('/', '.');
        return result.endsWith(".class") ? result.substring(0, result.length() - 6) : result;
    }

    private static String normalizePackage(String value) {
        String result = normalize(value);
        while (result.endsWith(".")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static boolean globMatches(String pattern, String value) {
        int p = 0, v = 0, star = -1, retry = 0;
        while (v < value.length()) {
            if (p < pattern.length()
                    && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++; v++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++; retry = v;
            } else if (star >= 0) {
                p = star + 1; v = ++retry;
            } else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    private static void sortClasses(List<ClassEntry> entries) {
        Collections.sort(entries, new Comparator<ClassEntry>() {
            @Override public int compare(ClassEntry left, ClassEntry right) {
                return left.name().compareToIgnoreCase(right.name());
            }
        });
    }

    public static final class PackageView {
        private final String name;
        private final List<String> packages;
        private final List<ClassEntry> classes;

        private PackageView(String name, List<String> packages, List<ClassEntry> classes) {
            this.name = name;
            this.packages = Collections.unmodifiableList(new ArrayList<String>(packages));
            this.classes = Collections.unmodifiableList(new ArrayList<ClassEntry>(classes));
        }

        public String name() { return name; }
        public List<String> packages() { return packages; }
        public List<ClassEntry> classes() { return classes; }
    }

    public static final class ClassEntry {
        private final String name;
        private final String origin;
        private final Path source;
        private final String jarResource;
        private volatile ClassMetadata metadata;

        private ClassEntry(String name, String origin, Path source, String jarResource) {
            this.name = name;
            this.origin = origin;
            this.source = source;
            this.jarResource = jarResource;
        }

        public String name() { return name; }
        public String origin() { return origin; }

        public byte[] bytes() throws IOException {
            if (jarResource == null) return Files.readAllBytes(source);
            try (JarFile jar = new JarFile(source.toFile())) {
                JarEntry entry = jar.getJarEntry(jarResource);
                if (entry == null) throw new IOException("Missing JAR entry: " + jarResource);
                try (java.io.InputStream input = jar.getInputStream(entry)) {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
                    return output.toByteArray();
                }
            }
        }

        public ClassMetadata metadata() throws IOException {
            ClassMetadata current = metadata;
            if (current != null) return current;
            synchronized (this) {
                if (metadata == null) metadata = parseMetadata(bytes());
                return metadata;
            }
        }

        private static ClassMetadata parseMetadata(byte[] bytes) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            List<Member> fields = new ArrayList<Member>();
            for (FieldNode field : node.fields) {
                fields.add(new Member(MemberKind.FIELD, field.name, field.desc, field.access));
            }
            List<Member> methods = new ArrayList<Member>();
            for (MethodNode method : node.methods) {
                methods.add(new Member(MemberKind.METHOD, method.name, method.desc, method.access));
            }
            return new ClassMetadata(node.access,
                    node.superName == null ? "" : node.superName.replace('/', '.'),
                    replaceNames(node.interfaces), fields, methods);
        }

        private static List<String> replaceNames(List<String> names) {
            List<String> result = new ArrayList<String>();
            for (String name : names) result.add(name.replace('/', '.'));
            return result;
        }
    }

    public enum MemberKind { FIELD, METHOD }

    public static final class MemberMatch {
        private final ClassEntry owner;
        private final Member member;

        private MemberMatch(ClassEntry owner, Member member) {
            this.owner = owner;
            this.member = member;
        }

        public ClassEntry owner() { return owner; }
        public Member member() { return member; }
    }

    public static final class Member {
        private final MemberKind kind;
        private final String name;
        private final String descriptor;
        private final int access;

        private Member(MemberKind kind, String name, String descriptor, int access) {
            this.kind = kind;
            this.name = name;
            this.descriptor = descriptor;
            this.access = access;
        }

        public MemberKind kind() { return kind; }
        public String name() { return name; }
        public String descriptor() { return descriptor; }
        public int access() { return access; }
        public boolean isStatic() { return (access & 0x0008) != 0; }
        public boolean isNative() { return (access & 0x0100) != 0; }
        public boolean isAbstract() { return (access & 0x0400) != 0; }
        public String typeSummary() {
            try {
                if (kind == MemberKind.FIELD) return Type.getType(descriptor).getClassName();
                Type method = Type.getMethodType(descriptor);
                StringBuilder result = new StringBuilder(method.getReturnType().getClassName()).append('(');
                Type[] arguments = method.getArgumentTypes();
                for (int index = 0; index < arguments.length; index++) {
                    if (index > 0) result.append(", ");
                    result.append(arguments[index].getClassName());
                }
                return result.append(')').toString();
            } catch (RuntimeException invalid) { return descriptor; }
        }
    }

    public static final class ClassMetadata {
        private final int access;
        private final String superName;
        private final List<String> interfaces;
        private final List<Member> fields;
        private final List<Member> methods;

        private ClassMetadata(int access, String superName, List<String> interfaces,
                List<Member> fields, List<Member> methods) {
            this.access = access;
            this.superName = superName;
            this.interfaces = Collections.unmodifiableList(new ArrayList<String>(interfaces));
            this.fields = Collections.unmodifiableList(new ArrayList<Member>(fields));
            this.methods = Collections.unmodifiableList(new ArrayList<Member>(methods));
        }

        public int access() { return access; }
        public String superName() { return superName; }
        public List<String> interfaces() { return interfaces; }
        public List<Member> fields() { return fields; }
        public List<Member> methods() { return methods; }
    }
}
