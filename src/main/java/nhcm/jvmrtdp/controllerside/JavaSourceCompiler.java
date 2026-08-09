package nhcm.jvmrtdp.controllerside;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Compiles source files or source fragments on the controller before deployment. */
public class JavaSourceCompiler {
    public Map<String, byte[]> compile(Path sourceFileOrDirectory, List<Path> classpath,
            List<String> compilerOptions) throws IOException {
        if (sourceFileOrDirectory == null) throw new IllegalArgumentException("source path must not be null");
        Path source = sourceFileOrDirectory.toAbsolutePath().normalize();
        List<Path> files = new ArrayList<Path>();
        if (Files.isDirectory(source)) {
            try (Stream<Path> stream = Files.walk(source)) {
                stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                        .sorted().forEach(files::add);
            }
        } else if (Files.isRegularFile(source) && source.toString().endsWith(".java")) {
            files.add(source);
        } else {
            throw new IllegalArgumentException("Source path must be a .java file or directory: " + source);
        }
        if (files.isEmpty()) throw new IllegalArgumentException("No .java files found under " + source);
        JavaCompiler compiler = requireCompiler();
        StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            List<JavaFileObject> units = new ArrayList<JavaFileObject>();
            for (JavaFileObject unit : manager.getJavaFileObjectsFromFiles(toFiles(files))) units.add(unit);
            return compile(compiler, manager, units, classpath, compilerOptions);
        } finally {
            manager.close();
        }
    }

    public Map<String, byte[]> compileSource(String binaryClassName, String source,
            List<Path> classpath, List<String> compilerOptions) throws IOException {
        if (binaryClassName == null || binaryClassName.trim().isEmpty()) {
            throw new IllegalArgumentException("binaryClassName must not be empty");
        }
        if (source == null || source.trim().isEmpty()) throw new IllegalArgumentException("source must not be empty");
        JavaCompiler compiler = requireCompiler();
        StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            return compile(compiler, manager,
                    Collections.<JavaFileObject>singletonList(new StringSource(binaryClassName, source)),
                    classpath, compilerOptions);
        } finally {
            manager.close();
        }
    }

    public Map<String, byte[]> compileMethods(String binaryClassName, String methodsSource,
            List<Path> classpath, List<String> compilerOptions) throws IOException {
        if (binaryClassName == null || binaryClassName.trim().isEmpty()) {
            throw new IllegalArgumentException("binaryClassName must not be empty");
        }
        int separator = binaryClassName.lastIndexOf('.');
        String packageName = separator < 0 ? "" : binaryClassName.substring(0, separator);
        String simpleName = separator < 0 ? binaryClassName : binaryClassName.substring(separator + 1);
        if (!simpleName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("Invalid generated class name: " + binaryClassName);
        }
        String source = (packageName.isEmpty() ? "" : "package " + packageName + ";\n")
                + "public class " + simpleName + " {\n" + methodsSource + "\n}\n";
        return compileSource(binaryClassName, source, classpath, compilerOptions);
    }

    private static Map<String, byte[]> compile(JavaCompiler compiler, StandardJavaFileManager manager,
            List<JavaFileObject> units, List<Path> classpath, List<String> compilerOptions) throws IOException {
        Path output = Files.createTempDirectory("jvmrtdp-compile-").toAbsolutePath().normalize();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        try {
            List<String> options = options(output, classpath, compilerOptions);
            Boolean successful = compiler.getTask(null, manager, diagnostics, options, null, units).call();
            if (!Boolean.TRUE.equals(successful)) throw new IllegalArgumentException(renderDiagnostics(diagnostics));
            Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
            try (Stream<Path> stream = Files.walk(output)) {
                stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                        .sorted().forEach(path -> {
                            String relative = output.relativize(path).toString();
                            String binaryName = relative.substring(0, relative.length() - ".class".length())
                                    .replace('/', '.').replace('\\', '.');
                            try {
                                classes.put(binaryName, Files.readAllBytes(path));
                            } catch (IOException exception) {
                                throw new CompileIoException(exception);
                            }
                        });
            } catch (CompileIoException exception) {
                throw exception.failure;
            }
            if (classes.isEmpty()) throw new IllegalStateException("Compilation produced no class files");
            return Collections.unmodifiableMap(classes);
        } finally {
            deleteTree(output);
        }
    }

    private static JavaCompiler requireCompiler() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK is required to compile Java source (no system compiler found)");
        }
        return compiler;
    }

    private static List<String> options(Path output, List<Path> classpath, List<String> requested) {
        List<String> result = new ArrayList<String>();
        List<String> supplied = requested == null ? Collections.<String>emptyList() : requested;
        if (!supplied.contains("--release") && !supplied.contains("-source")) {
            result.add("-source");
            result.add("8");
        }
        if (!supplied.contains("--release") && !supplied.contains("-target")) {
            result.add("-target");
            result.add("8");
        }
        result.add("-encoding");
        result.add("UTF-8");
        result.add("-d");
        result.add(output.toString());
        StringBuilder paths = new StringBuilder(System.getProperty("java.class.path", ""));
        if (classpath != null) {
            for (Path path : classpath) {
                if (paths.length() != 0) paths.append(java.io.File.pathSeparatorChar);
                paths.append(path.toAbsolutePath().normalize());
            }
        }
        if (paths.length() != 0) {
            result.add("-classpath");
            result.add(paths.toString());
        }
        result.addAll(supplied);
        return result;
    }

    private static String renderDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder result = new StringBuilder("Java compilation failed");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            result.append(System.lineSeparator());
            if (diagnostic.getSource() != null) result.append(diagnostic.getSource().getName());
            if (diagnostic.getLineNumber() >= 0) result.append(':').append(diagnostic.getLineNumber());
            result.append(": ").append(diagnostic.getMessage(Locale.ROOT));
        }
        return result.toString();
    }

    private static List<java.io.File> toFiles(List<Path> paths) {
        List<java.io.File> result = new ArrayList<java.io.File>();
        for (Path path : paths) result.add(path.toFile());
        return result;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    private static class StringSource extends SimpleJavaFileObject {
        private final String source;

        private StringSource(String binaryName, String source) {
            super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    }

    private static class CompileIoException extends RuntimeException {
        private final IOException failure;
        private CompileIoException(IOException failure) { this.failure = failure; }
    }
}
