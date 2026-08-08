package nhcm.jvmrtdp.handles.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A snapshot of loaded classes and immediate child packages. */
public class RemotePackage {
    private final String name;
    private final List<String> packages;
    private final List<String> classes;

    public RemotePackage(String name, List<String> packages, List<String> classes) {
        this.name = Objects.requireNonNull(name, "name");
        this.packages = immutableCopy(packages);
        this.classes = immutableCopy(classes);
    }

    public String name() {
        return name;
    }

    public List<String> packages() {
        return packages;
    }

    public List<String> classes() {
        return classes;
    }

    private static List<String> immutableCopy(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<String>(source));
    }
}
