package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.handles.java.RemoteClassInfo;
import nhcm.jvmrtdp.handles.search.RemoteClassQuery;
import nhcm.jvmrtdp.utils.GlobMatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Controller-side class-file dumping, including bounded package batches. */
public class RemoteDumpService {
    private final TargetSession session;

    public RemoteDumpService(TargetSession session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        this.session = session;
    }

    public Report dumpPackage(
            String packageName, Path outputDirectory, boolean recursive, String classGlob, int limit) {
        String normalizedPackage = normalizePackage(packageName);
        Path root = outputDirectory.toAbsolutePath().normalize();
        RemoteClassQuery query = new RemoteClassQuery()
                .name(classGlob == null || classGlob.isEmpty() ? "*" : classGlob)
                .inPackage(recursive
                        ? normalizedPackage.isEmpty() ? "**" : normalizedPackage + ".**"
                        : normalizedPackage)
                .limit(limit);
        GlobMatcher matcher = GlobMatcher.of(classGlob == null || classGlob.isEmpty() ? "*" : classGlob);
        List<Path> written = new ArrayList<Path>();
        List<String> failures = new ArrayList<String>();
        for (RemoteClassInfo info : session.jni().searchClasses(query)) {
            if (info.isArray() || !inPackage(info.name(), normalizedPackage, recursive)
                    || (!matcher.matches(info.name()) && !matcher.matches(simpleName(info.name())))) continue;
            Path destination = root.resolve(info.name().replace('.', '/') + ".class").normalize();
            if (!destination.startsWith(root)) {
                failures.add(info.name() + ": unsafe output path");
                continue;
            }
            try {
                written.add(session.jvmti().dumpClass(info.name(), destination));
            } catch (RuntimeException exception) {
                failures.add(info.name() + ": " + exception.getMessage());
            } catch (IOException exception) {
                failures.add(info.name() + ": " + exception.getMessage());
            }
        }
        return new Report(written, failures);
    }

    private static boolean inPackage(String className, String packageName, boolean recursive) {
        int separator = className.lastIndexOf('.');
        String actual = separator < 0 ? "" : className.substring(0, separator);
        return recursive
                ? packageName.isEmpty() || actual.equals(packageName) || actual.startsWith(packageName + ".")
                : actual.equals(packageName);
    }

    private static String normalizePackage(String value) {
        if (value == null || value.isEmpty() || ".".equals(value) || "<default>".equalsIgnoreCase(value)) return "";
        String normalized = value.replace('/', '.');
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String simpleName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    public static class Report {
        private final List<Path> written;
        private final List<String> failures;

        private Report(List<Path> written, List<String> failures) {
            this.written = Collections.unmodifiableList(new ArrayList<Path>(written));
            this.failures = Collections.unmodifiableList(new ArrayList<String>(failures));
        }

        public List<Path> written() { return written; }
        public List<String> failures() { return failures; }
    }
}
