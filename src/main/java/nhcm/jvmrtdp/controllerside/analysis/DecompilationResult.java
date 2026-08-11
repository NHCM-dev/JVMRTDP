package nhcm.jvmrtdp.controllerside.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class DecompilationResult {
    private final DecompilerEngine engine;
    private final String className;
    private final String source;
    private final List<String> diagnostics;
    private final Map<String, NavigableMap<Integer, Integer>> lineMappings;

    public DecompilationResult(
            DecompilerEngine engine, String className, String source, List<String> diagnostics) {
        this(engine, className, source, diagnostics,
                Collections.<String, NavigableMap<Integer, Integer>>emptyMap());
    }

    public DecompilationResult(DecompilerEngine engine, String className, String source,
            List<String> diagnostics, Map<String, NavigableMap<Integer, Integer>> lineMappings) {
        this.engine = engine;
        this.className = className;
        this.source = source;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<String>(diagnostics));
        Map<String, NavigableMap<Integer, Integer>> copy =
                new HashMap<String, NavigableMap<Integer, Integer>>();
        for (Map.Entry<String, NavigableMap<Integer, Integer>> entry : lineMappings.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableNavigableMap(
                    new TreeMap<Integer, Integer>(entry.getValue())));
        }
        this.lineMappings = Collections.unmodifiableMap(copy);
    }

    public DecompilerEngine engine() { return engine; }
    public String className() { return className; }
    public String source() { return source; }
    public List<String> diagnostics() { return diagnostics; }

    /** Maps bytecode index to a 1-based line in this result's source. */
    public NavigableMap<Integer, Integer> lineMappings(String methodName, String descriptor) {
        NavigableMap<Integer, Integer> mappings = lineMappings.get(mappingKey(methodName, descriptor));
        return mappings == null ? Collections.<Integer, Integer>emptyNavigableMap() : mappings;
    }

    static String mappingKey(String methodName, String descriptor) {
        return methodName + '\0' + descriptor;
    }
}
