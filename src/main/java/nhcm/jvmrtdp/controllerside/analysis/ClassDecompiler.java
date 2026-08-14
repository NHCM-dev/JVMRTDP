package nhcm.jvmrtdp.controllerside.analysis;

import com.strobel.assembler.metadata.Buffer;
import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Decompiles in-memory target class bytes using source-built CFR or Procyon. */
public final class ClassDecompiler {
    public DecompilationResult decompile(
            String className, byte[] classBytes, DecompilerEngine engine) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("className must not be empty");
        }
        if (classBytes == null || classBytes.length < 4) {
            throw new IllegalArgumentException("classBytes are invalid");
        }
        DecompilerEngine selected = engine == null ? DecompilerEngine.CFR : engine;
        return selected == DecompilerEngine.PROCYON
                ? decompileProcyon(className, classBytes)
                : decompileCfr(className, classBytes);
    }

    /** Extracts a single decompiled method body from the full class source. */
    public String decompileMethod(String className, byte[] classBytes, String methodName,
            String descriptor, DecompilerEngine engine) {
        return decompileMethodResult(className, classBytes, methodName, descriptor, engine).source();
    }

    public DecompilationResult decompileMethodResult(String className, byte[] classBytes,
            String methodName, String descriptor, DecompilerEngine engine) {
        if (methodName == null || methodName.isEmpty()) throw new IllegalArgumentException("methodName is required");
        if (descriptor == null || descriptor.isEmpty()) throw new IllegalArgumentException("descriptor is required");
        DecompilationResult result = decompile(className, classBytes, engine);
        JavaMethodExtractor.Extraction extracted = JavaMethodExtractor.extractDetails(
                result.source(), simpleName(className), methodName, descriptor);
        if (extracted == null) {
            List<String> diagnostics = new ArrayList<String>(result.diagnostics());
            diagnostics.add("The decompiler did not expose an isolatable declaration for "
                    + methodName + descriptor + "; the class result is shown.");
            return new DecompilationResult(result.engine(), className,
                    result.source(), diagnostics,
                    Collections.singletonMap(DecompilationResult.mappingKey(methodName, descriptor),
                            result.lineMappings(methodName, descriptor)));
        }
        NavigableMap<Integer, Integer> adjusted = new TreeMap<Integer, Integer>();
        int lineCount = extracted.source().split("\\r?\\n", -1).length;
        for (Map.Entry<Integer, Integer> mapping
                : result.lineMappings(methodName, descriptor).entrySet()) {
            int line = mapping.getValue() - extracted.startLine() + 1;
            if (line >= 1 && line <= lineCount) adjusted.put(mapping.getKey(), line);
        }
        Map<String, NavigableMap<Integer, Integer>> mappings =
                new HashMap<String, NavigableMap<Integer, Integer>>();
        mappings.put(DecompilationResult.mappingKey(methodName, descriptor), adjusted);
        return new DecompilationResult(result.engine(), className, extracted.source(),
                result.diagnostics(), mappings);
    }

    /**
     * Decompiles only the source rows correlated by CFR with a selected BCI range. CFR's
     * bytecode-location map is produced by its control-flow analysis and does not require
     * a LineNumberTable in the target class.
     */
    public DecompilationResult decompileRangeResult(String className, byte[] classBytes,
            String methodName, String descriptor, int fromBci, int toBci,
            DecompilerEngine engine) {
        if (fromBci < 0 || toBci < fromBci) throw new IllegalArgumentException(
                "Invalid BCI range " + fromBci + ".." + toBci);
        DecompilationResult method = decompileMethodResult(
                className, classBytes, methodName, descriptor, engine);
        NavigableMap<Integer, Integer> map = method.lineMappings(methodName, descriptor);
        if (map.isEmpty()) throw new IllegalStateException(
                "No decompiler BCI mapping is available for " + methodName + descriptor
                        + "; CFR is required for range decompilation");
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            if (entry.getKey().intValue() < fromBci || entry.getKey().intValue() > toBci) continue;
            first = Math.min(first, entry.getValue().intValue());
            last = Math.max(last, entry.getValue().intValue());
        }
        if (first == Integer.MAX_VALUE) {
            Map.Entry<Integer, Integer> near = map.floorEntry(Integer.valueOf(fromBci));
            if (near == null) near = map.ceilingEntry(Integer.valueOf(fromBci));
            if (near == null) throw new IllegalStateException("No decompiled row is near BCI " + fromBci);
            first = last = near.getValue().intValue();
        }
        String[] lines = method.source().split("\\r?\\n", -1);
        first = Math.max(1, Math.min(first, lines.length));
        last = Math.max(first, Math.min(last, lines.length));
        StringBuilder source = new StringBuilder();
        for (int line = first; line <= last; line++) {
            if (source.length() > 0) source.append(System.lineSeparator());
            source.append(lines[line - 1]);
        }
        NavigableMap<Integer, Integer> adjusted = new TreeMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            if (entry.getKey().intValue() >= fromBci && entry.getKey().intValue() <= toBci
                    && entry.getValue().intValue() >= first && entry.getValue().intValue() <= last) {
                adjusted.put(entry.getKey(), Integer.valueOf(entry.getValue().intValue() - first + 1));
            }
        }
        Map<String, NavigableMap<Integer, Integer>> mappings =
                new HashMap<String, NavigableMap<Integer, Integer>>();
        mappings.put(DecompilationResult.mappingKey(methodName, descriptor), adjusted);
        return new DecompilationResult(method.engine(), className, source.toString(),
                method.diagnostics(), mappings);
    }

    private static DecompilationResult decompileCfr(final String className, final byte[] classBytes) {
        final String internalPath = className.replace('.', '/') + ".class";
        final StringBuilder source = new StringBuilder();
        final List<String> diagnostics = new ArrayList<String>();
        final Map<String, NavigableMap<Integer, Integer>> lineMappings =
                new HashMap<String, NavigableMap<Integer, Integer>>();
        ClassFileSource classSource = new ClassFileSource() {
            @Override public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {}
            @Override public Collection<String> addJar(String jarPath) { return Collections.emptyList(); }
            @Override public String getPossiblyRenamedPath(String path) { return path; }
            @Override public Pair<byte[], String> getClassFileContent(String path) throws IOException {
                String normalized = path.replace('\\', '/');
                if (normalized.equals(internalPath) || normalized.endsWith('/' + internalPath)
                        || normalized.equals(className) || normalized.equals(className + ".class")) {
                    return Pair.make(classBytes, internalPath);
                }
                throw new IOException("Class bytes are not available in this snapshot: " + path);
            }
        };
        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                if (sinkType == SinkType.JAVA && available.contains(SinkClass.DECOMPILED)) {
                    return Collections.singletonList(SinkClass.DECOMPILED);
                }
                if (sinkType == SinkType.LINENUMBER
                        && available.contains(SinkClass.LINE_NUMBER_MAPPING)) {
                    return Collections.singletonList(SinkClass.LINE_NUMBER_MAPPING);
                }
                return Collections.singletonList(SinkClass.STRING);
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> Sink<T> getSink(final SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                    return value -> source.append(((SinkReturns.Decompiled) value).getJava());
                }
                if (sinkType == SinkType.LINENUMBER && sinkClass == SinkClass.LINE_NUMBER_MAPPING) {
                    return value -> {
                        SinkReturns.LineNumberMapping mapping = (SinkReturns.LineNumberMapping) value;
                        lineMappings.put(DecompilationResult.mappingKey(
                                mapping.methodName(), mapping.methodDescriptor()),
                                new TreeMap<Integer, Integer>(mapping.getMappings()));
                    };
                }
                return value -> {
                    if (sinkType == SinkType.EXCEPTION || sinkType == SinkType.SUMMARY) {
                        diagnostics.add(String.valueOf(value));
                    }
                };
            }
        };
        Map<String, String> options = new HashMap<String, String>();
        options.put("showversion", "false");
        options.put("comments", "false");
        options.put("hideutf", "false");
        options.put("trackbytecodeloc", "true");
        new CfrDriver.Builder()
                .withClassFileSource(classSource)
                .withOutputSink(sinkFactory)
                .withOptions(options)
                .build()
                .analyse(Collections.singletonList(internalPath));
        if (source.length() == 0) {
            throw new IllegalStateException("CFR produced no source: " + diagnostics);
        }
        return new DecompilationResult(DecompilerEngine.CFR, className, source.toString(),
                diagnostics, lineMappings);
    }

    private static DecompilationResult decompileProcyon(final String className, final byte[] classBytes) {
        DecompilerSettings settings = DecompilerSettings.javaDefaults();
        settings.setShowSyntheticMembers(true);
        settings.setForceExplicitImports(false);
        settings.setTypeLoader(new ITypeLoader() {
            @Override
            public boolean tryLoadType(String internalName, Buffer buffer) {
                String requested = stripClassSuffix(internalName).replace('.', '/');
                String expected = className.replace('.', '/');
                if (!requested.equals(expected)) return false;
                buffer.position(0);
                buffer.reset(classBytes.length);
                buffer.putByteArray(classBytes, 0, classBytes.length);
                buffer.position(0);
                return true;
            }
        });
        StringWriter writer = new StringWriter();
        PlainTextOutput output = new PlainTextOutput(writer);
        output.setUnicodeOutputEnabled(true);
        Decompiler.decompile(className.replace('.', '/'), output, settings);
        String source = writer.toString();
        if (source.isEmpty() || source.startsWith("!!! ERROR:")) {
            throw new IllegalStateException("Procyon failed to decompile " + className + ": " + source);
        }
        return new DecompilationResult(
                DecompilerEngine.PROCYON, className, source, Collections.<String>emptyList());
    }

    private static String stripClassSuffix(String value) {
        return value.endsWith(".class") ? value.substring(0, value.length() - 6) : value;
    }

    private static String simpleName(String className) {
        int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return separator < 0 ? className : className.substring(separator + 1);
    }

}
