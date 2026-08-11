package nhcm.jvmrtdp.controllerside.debug;

import nhcm.jvmrtdp.api.jvmti.JvmBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerLocal;
import nhcm.jvmrtdp.api.jvmti.JvmDebuggerState;
import nhcm.jvmrtdp.api.jvmti.JvmFieldWatchInfo;
import nhcm.jvmrtdp.api.jvmti.JvmStackFrame;
import nhcm.jvmrtdp.controllerside.TargetSession;
import nhcm.jvmrtdp.handles.jvm.RemoteJvmtiThread;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Versioned, machine-readable debugger analysis snapshots for scripts and external tools. */
public final class DebuggerAnalysisExporter {
    public enum Format {
        JSON, JSONL;

        public static Format parse(String value) {
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (RuntimeException failure) {
                throw new IllegalArgumentException("Analysis format must be json or jsonl");
            }
        }
    }

    private DebuggerAnalysisExporter() {}

    public static String capture(TargetSession session, Format format,
            int maxFrames, int localsDepth) {
        if (maxFrames < 0) throw new IllegalArgumentException("maxFrames must not be negative");
        if (localsDepth < 0) throw new IllegalArgumentException("localsDepth must not be negative");
        Snapshot snapshot = snapshot(session, maxFrames, localsDepth);
        return format == Format.JSONL ? jsonLines(snapshot) : json(snapshot);
    }

    public static Path write(TargetSession session, Path output, Format format,
            int maxFrames, int localsDepth) throws IOException {
        if (output == null) throw new IllegalArgumentException("output must not be null");
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(absolute, capture(session, format, maxFrames, localsDepth)
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return absolute;
    }

    private static Snapshot snapshot(TargetSession session, int maxFrames, int localsDepth) {
        Snapshot result = new Snapshot();
        result.capturedAt = Instant.now().toString();
        result.pid = session.server().process().pid();
        result.freeze = session.debugger().status();
        result.breakpoints.addAll(session.jvmti().managedBreakpoints());
        result.watches.addAll(session.jvmti().managedFieldWatches());

        List<RemoteJvmtiThread> threads = session.jvmti().threads();
        try {
            for (int index = 0; index < threads.size(); index++) {
                RemoteJvmtiThread thread = threads.get(index);
                result.threads.add(new ThreadRow(index, thread.name(), thread.capturedState(),
                        thread.stateSummary(), thread.priority(), thread.daemon(),
                        thread.debuggerPaused(), DebuggerControlService.sensitiveReason(thread.name())));
            }
        } finally {
            for (RemoteJvmtiThread thread : threads) thread.close();
        }

        List<JvmDebuggerState> states = session.jvmti().debuggerStates();
        try {
            int index = 0;
            for (JvmDebuggerState state : states) {
                if (!state.paused() || state.thread() == null) continue;
                StopRow stop = new StopRow(index++, state);
                try {
                    List<JvmStackFrame> frames = session.jvmti().stackFrames(state.thread(), maxFrames);
                    for (JvmStackFrame frame : frames) {
                        stop.stack.add(frame.raw());
                        stop.frames.add(new FrameRow(frame));
                    }
                    stop.preferredFrameDepth = preferredFrameDepth(frames);
                } catch (RuntimeException failure) {
                    stop.stackError = rootMessage(failure);
                }
                List<JvmDebuggerLocal> locals = new ArrayList<JvmDebuggerLocal>();
                try {
                    locals.addAll(session.jvmti().debuggerLocals(state.thread(), localsDepth));
                    for (JvmDebuggerLocal local : locals) {
                        stop.locals.add(new LocalRow(local.name(), local.descriptor(),
                                local.genericSignature(), local.slot(), local.scopeStart(),
                                local.scopeLength(), local.inferred(), local.available(),
                                local.available() && local.value() != null
                                        ? local.value().displayValue() : null,
                                local.error()));
                    }
                } catch (RuntimeException failure) {
                    stop.localsError = rootMessage(failure);
                } finally {
                    for (JvmDebuggerLocal local : locals) local.close();
                }
                result.stops.add(stop);
            }
        } finally {
            for (JvmDebuggerState state : states) state.close();
        }
        return result;
    }

    private static String json(Snapshot value) {
        StringBuilder out = new StringBuilder(8192);
        out.append("{\n  \"schema\": \"jvmrtdp.debug-analysis\",\n  \"version\": 2,");
        field(out, "capturedAt", value.capturedAt, true, 2);
        out.append(",\n  \"target\": {\"pid\": ").append(value.pid).append("},");
        out.append("\n  \"freeze\": ");
        appendFreeze(out, value.freeze);
        out.append(",\n  \"threads\": [");
        for (int index = 0; index < value.threads.size(); index++) {
            if (index > 0) out.append(',');
            out.append("\n    ");
            appendThread(out, value.threads.get(index));
        }
        if (!value.threads.isEmpty()) out.append('\n').append("  ");
        out.append("],\n  \"stops\": [");
        for (int index = 0; index < value.stops.size(); index++) {
            if (index > 0) out.append(',');
            out.append("\n    ");
            appendStop(out, value.stops.get(index));
        }
        if (!value.stops.isEmpty()) out.append('\n').append("  ");
        out.append("],\n  \"breakpoints\": [");
        for (int index = 0; index < value.breakpoints.size(); index++) {
            if (index > 0) out.append(',');
            appendBreakpoint(out, value.breakpoints.get(index));
        }
        out.append("],\n  \"fieldWatches\": [");
        for (int index = 0; index < value.watches.size(); index++) {
            if (index > 0) out.append(',');
            appendWatch(out, value.watches.get(index));
        }
        out.append("]\n}\n");
        return out.toString();
    }

    private static String jsonLines(Snapshot value) {
        StringBuilder out = new StringBuilder(8192);
        out.append("{\"type\":\"meta\",\"schema\":\"jvmrtdp.debug-analysis\","
                + "\"version\":2,\"capturedAt\":");
        quote(out, value.capturedAt);
        out.append(",\"pid\":").append(value.pid).append(",\"freeze\":");
        appendFreeze(out, value.freeze);
        out.append("}\n");
        for (ThreadRow thread : value.threads) {
            out.append("{\"type\":\"thread\",\"data\":");
            appendThread(out, thread);
            out.append("}\n");
        }
        for (StopRow stop : value.stops) {
            out.append("{\"type\":\"stop\",\"data\":");
            appendStop(out, stop);
            out.append("}\n");
        }
        for (JvmBreakpointInfo breakpoint : value.breakpoints) {
            out.append("{\"type\":\"breakpoint\",\"data\":");
            appendBreakpoint(out, breakpoint);
            out.append("}\n");
        }
        for (JvmFieldWatchInfo watch : value.watches) {
            out.append("{\"type\":\"fieldWatch\",\"data\":");
            appendWatch(out, watch);
            out.append("}\n");
        }
        return out.toString();
    }

    private static void appendFreeze(StringBuilder out, DebuggerFreezeReport value) {
        out.append("{\"active\":").append(value.active())
                .append(",\"generation\":").append(value.generation())
                .append(",\"ownedThreadCount\":").append(value.ownedThreadCount())
                .append(",\"entries\":[");
        for (int index = 0; index < value.entries().size(); index++) {
            if (index > 0) out.append(',');
            DebuggerFreezeReport.Entry entry = value.entries().get(index);
            out.append('{');
            field(out, "thread", entry.threadName(), true, 0);
            field(out, "originalState", entry.originalStateSummary(), false, 0);
            out.append(",\"stateBits\":").append(entry.originalState())
                    .append(",\"daemon\":").append(entry.daemon());
            field(out, "action", entry.action().name().toLowerCase(Locale.ROOT), false, 0);
            field(out, "detail", entry.detail(), false, 0);
            out.append('}');
        }
        out.append("]}");
    }

    private static void appendThread(StringBuilder out, ThreadRow value) {
        out.append('{').append("\"index\":").append(value.index);
        field(out, "name", value.name, false, 0);
        out.append(",\"stateBits\":").append(value.stateBits);
        field(out, "state", value.state, false, 0);
        out.append(",\"priority\":").append(value.priority)
                .append(",\"daemon\":").append(value.daemon)
                .append(",\"debuggerPaused\":").append(value.debuggerPaused);
        nullableField(out, "sensitiveReason", value.sensitiveReason);
        out.append('}');
    }

    private static void appendStop(StringBuilder out, StopRow value) {
        out.append('{').append("\"index\":").append(value.index);
        field(out, "thread", value.thread, false, 0);
        field(out, "reason", value.reason, false, 0);
        field(out, "class", value.className, false, 0);
        field(out, "method", value.methodName, false, 0);
        field(out, "descriptor", value.descriptor, false, 0);
        out.append(",\"bci\":").append(value.location)
                .append(",\"sourceLine\":").append(value.sourceLine)
                .append(",\"sequence\":").append(value.sequence)
                .append(",\"preferredFrameDepth\":").append(value.preferredFrameDepth)
                .append(",\"stack\":[");
        strings(out, value.stack);
        out.append(']');
        nullableField(out, "stackError", value.stackError);
        out.append(",\"frames\":[");
        for (int index = 0; index < value.frames.size(); index++) {
            if (index > 0) out.append(',');
            appendFrame(out, value.frames.get(index));
        }
        out.append(']');
        out.append(",\"locals\":[");
        for (int index = 0; index < value.locals.size(); index++) {
            if (index > 0) out.append(',');
            appendLocal(out, value.locals.get(index));
        }
        out.append(']');
        nullableField(out, "localsError", value.localsError);
        out.append('}');
    }

    private static void appendLocal(StringBuilder out, LocalRow value) {
        out.append('{');
        field(out, "name", value.name, true, 0);
        field(out, "descriptor", value.descriptor, false, 0);
        field(out, "genericSignature", value.genericSignature, false, 0);
        out.append(",\"slot\":").append(value.slot)
                .append(",\"scopeStart\":").append(value.scopeStart)
                .append(",\"scopeLength\":").append(value.scopeLength)
                .append(",\"inferred\":").append(value.inferred)
                .append(",\"available\":").append(value.available);
        nullableField(out, "value", value.value);
        nullableField(out, "error", emptyToNull(value.error));
        out.append('}');
    }

    private static void appendFrame(StringBuilder out, FrameRow value) {
        out.append('{').append("\"depth\":").append(value.depth);
        field(out, "class", value.className, false, 0);
        field(out, "method", value.methodName, false, 0);
        field(out, "descriptor", value.descriptor, false, 0);
        out.append(",\"bci\":").append(value.location)
                .append(",\"native\":").append(value.nativeFrame)
                .append(",\"platform\":").append(value.platformFrame)
                .append('}');
    }

    private static int preferredFrameDepth(List<JvmStackFrame> frames) {
        if (frames.isEmpty() || frames.get(0).hasJavaLocation()) return 0;
        for (JvmStackFrame frame : frames) {
            if (frame.hasJavaLocation() && !frame.isPlatformFrame()) return frame.depth();
        }
        for (JvmStackFrame frame : frames) if (frame.hasJavaLocation()) return frame.depth();
        return 0;
    }

    private static void appendBreakpoint(StringBuilder out, JvmBreakpointInfo value) {
        out.append('{');
        field(out, "class", value.className(), true, 0);
        field(out, "method", value.methodName(), false, 0);
        field(out, "descriptor", value.descriptor(), false, 0);
        out.append(",\"bci\":").append(value.location()).append('}');
    }

    private static void appendWatch(StringBuilder out, JvmFieldWatchInfo value) {
        out.append('{');
        field(out, "class", value.className(), true, 0);
        field(out, "field", value.fieldName(), false, 0);
        field(out, "descriptor", value.descriptor(), false, 0);
        field(out, "kind", value.kind(), false, 0);
        out.append('}');
    }

    private static void strings(StringBuilder out, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(',');
            quote(out, values.get(index));
        }
    }

    private static void field(StringBuilder out, String name, String value,
            boolean first, int ignoredIndent) {
        if (!first) out.append(',');
        quote(out, name);
        out.append(':');
        quote(out, value == null ? "" : value);
    }

    private static void nullableField(StringBuilder out, String name, String value) {
        out.append(',');
        quote(out, name);
        out.append(':');
        if (value == null) out.append("null");
        else quote(out, value);
    }

    static void quote(StringBuilder out, String value) {
        out.append('"');
        String text = value == null ? "" : value;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (character < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else out.append(character);
            }
        }
        out.append('"');
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static final class Snapshot {
        private String capturedAt;
        private long pid;
        private DebuggerFreezeReport freeze;
        private final List<ThreadRow> threads = new ArrayList<ThreadRow>();
        private final List<StopRow> stops = new ArrayList<StopRow>();
        private final List<JvmBreakpointInfo> breakpoints = new ArrayList<JvmBreakpointInfo>();
        private final List<JvmFieldWatchInfo> watches = new ArrayList<JvmFieldWatchInfo>();
    }

    private static final class ThreadRow {
        private final int index;
        private final String name;
        private final int stateBits;
        private final String state;
        private final int priority;
        private final boolean daemon;
        private final boolean debuggerPaused;
        private final String sensitiveReason;

        private ThreadRow(int index, String name, int stateBits, String state, int priority,
                boolean daemon, boolean debuggerPaused, String sensitiveReason) {
            this.index = index;
            this.name = name;
            this.stateBits = stateBits;
            this.state = state;
            this.priority = priority;
            this.daemon = daemon;
            this.debuggerPaused = debuggerPaused;
            this.sensitiveReason = sensitiveReason;
        }
    }

    private static final class StopRow {
        private final int index;
        private final String thread;
        private final String reason;
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final long location;
        private final int sourceLine;
        private final long sequence;
        private final List<String> stack = new ArrayList<String>();
        private final List<FrameRow> frames = new ArrayList<FrameRow>();
        private final List<LocalRow> locals = new ArrayList<LocalRow>();
        private int preferredFrameDepth;
        private String stackError;
        private String localsError;

        private StopRow(int index, JvmDebuggerState state) {
            this.index = index;
            this.thread = state.thread().displayValue();
            this.reason = state.reason();
            this.className = state.className();
            this.methodName = state.methodName();
            this.descriptor = state.descriptor();
            this.location = state.location();
            this.sourceLine = state.sourceLine();
            this.sequence = state.sequence();
        }
    }

    private static final class FrameRow {
        private final int depth;
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final long location;
        private final boolean nativeFrame;
        private final boolean platformFrame;

        private FrameRow(JvmStackFrame frame) {
            this.depth = frame.depth();
            this.className = frame.className();
            this.methodName = frame.methodName();
            this.descriptor = frame.descriptor();
            this.location = frame.location();
            this.nativeFrame = frame.isNative();
            this.platformFrame = frame.isPlatformFrame();
        }
    }

    private static final class LocalRow {
        private final String name;
        private final String descriptor;
        private final String genericSignature;
        private final int slot;
        private final long scopeStart;
        private final long scopeLength;
        private final boolean inferred;
        private final boolean available;
        private final String value;
        private final String error;

        private LocalRow(String name, String descriptor, String genericSignature, int slot,
                long scopeStart, long scopeLength, boolean inferred, boolean available,
                String value, String error) {
            this.name = name;
            this.descriptor = descriptor;
            this.genericSignature = genericSignature;
            this.slot = slot;
            this.scopeStart = scopeStart;
            this.scopeLength = scopeLength;
            this.inferred = inferred;
            this.available = available;
            this.value = value;
            this.error = error;
        }
    }
}
