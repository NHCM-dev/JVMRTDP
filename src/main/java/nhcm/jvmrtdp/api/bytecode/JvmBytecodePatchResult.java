package nhcm.jvmrtdp.api.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result and BCI relocation table produced by a bytecode patch transaction. */
public final class JvmBytecodePatchResult {
    private final String className;
    private final byte[] originalBytes;
    private final byte[] patchedBytes;
    private final int operationCount;
    private final List<String> changedMethods;
    private final Map<String, Map<Long, Long>> relocations;
    private final boolean installed;

    JvmBytecodePatchResult(String className, byte[] originalBytes, byte[] patchedBytes,
            int operationCount, List<String> changedMethods,
            Map<String, Map<Long, Long>> relocations, boolean installed) {
        this.className = className;
        this.originalBytes = originalBytes.clone();
        this.patchedBytes = patchedBytes.clone();
        this.operationCount = operationCount;
        this.changedMethods = Collections.unmodifiableList(new ArrayList<String>(changedMethods));
        Map<String, Map<Long, Long>> copy = new LinkedHashMap<String, Map<Long, Long>>();
        for (Map.Entry<String, Map<Long, Long>> entry : relocations.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<Long, Long>(entry.getValue())));
        }
        this.relocations = Collections.unmodifiableMap(copy);
        this.installed = installed;
    }

    public String className() { return className; }
    public byte[] originalBytes() { return originalBytes.clone(); }
    public byte[] patchedBytes() { return patchedBytes.clone(); }
    public int operationCount() { return operationCount; }
    public List<String> changedMethods() { return changedMethods; }
    public Map<String, Map<Long, Long>> relocations() { return relocations; }
    public boolean installed() { return installed; }

    public Long relocatedBci(String methodName, String descriptor, long oldBci) {
        Map<Long, Long> method = relocations.get(methodKey(methodName, descriptor));
        return method == null ? null : method.get(oldBci);
    }

    public static String methodKey(String methodName, String descriptor) {
        return methodName + '\u0000' + descriptor;
    }

    @Override public String toString() {
        return (installed ? "installed" : "preview") + " bytecode patch for " + className
                + ": " + operationCount + " operation(s), " + changedMethods;
    }
}
