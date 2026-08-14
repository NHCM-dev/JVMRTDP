package nhcm.jvmrtdp.api.hook;

import nhcm.jvmrtdp.api.jvmti.JvmDebuggerState;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointInfo;
import nhcm.jvmrtdp.api.jvmti.JvmEventBreakpointSpec;
import nhcm.jvmrtdp.api.jvmti.JvmFieldWatchInfo;
import nhcm.jvmrtdp.api.reference.JvmReferenceInfo;
import nhcm.jvmrtdp.api.reference.JvmReferenceManager;
import nhcm.jvmrtdp.api.reference.JvmReferenceStrength;
import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;
import nhcm.jvmrtdp.handles.jvm.RemoteJVMTIEnv;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Precise, session-scoped String hook registry shared by the library, CLI and TUI.
 * Field hooks are JVMTI access/modification watchpoints. Method hooks are JVMTI
 * entry/exit event breakpoints. Fast allocation hooks use lightweight completed
 * {@code String.<init>} probes; complete hooks add VM object-allocation events. Both filter
 * content and optional creator frames in the target and expose the match through the debugger.
 */
public final class JvmStringHookManager implements AutoCloseable {
    private static final String STRING_DESCRIPTOR = "Ljava/lang/String;";

    private final RemoteJNIEnv jni;
    private final RemoteJVMTIEnv jvmti;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private long revision;

    public JvmStringHookManager(RemoteJNIEnv jni, RemoteJVMTIEnv jvmti) {
        if (jni == null || jvmti == null) throw new IllegalArgumentException("jni and jvmti must not be null");
        this.jni = jni;
        this.jvmti = jvmti;
    }

    public synchronized JvmStringHookInfo watchField(String name, RemoteField field,
            boolean modification, RemoteObject receiver) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        if (!STRING_DESCRIPTOR.equals(field.descriptor())) {
            throw new IllegalArgumentException("String field hook requires descriptor "
                    + STRING_DESCRIPTOR + ": " + field.descriptor());
        }
        if (!field.isStatic() && receiver == null) {
            // null means every instance, which is a valid JVMTI watch.
        } else if (field.isStatic() && receiver != null) {
            throw new IllegalArgumentException("A static field hook cannot have a receiver");
        }
        String key = normalize(name);
        rejectDuplicate(field.declaringClass(), field.name(), field.descriptor(),
                modification ? JvmStringHookKind.FIELD_WRITE : JvmStringHookKind.FIELD_READ,
                receiver == null ? 0L : receiver.remoteId(), key);
        RemoteObject retained = receiver == null ? null : jni.retain(receiver, false);
        Entry entry = Entry.field(key,
                modification ? JvmStringHookKind.FIELD_WRITE : JvmStringHookKind.FIELD_READ,
                field, retained);
        replaceEntry(key, entry);
        enableOrRemove(entry);
        return entry.info();
    }

    public synchronized JvmStringHookInfo breakMethod(String name, JvmStringHookKind kind,
            String className, String methodName, String descriptor) {
        if (kind != JvmStringHookKind.METHOD_ENTRY && kind != JvmStringHookKind.METHOD_EXIT) {
            throw new IllegalArgumentException("Method String hook kind must be METHOD_ENTRY or METHOD_EXIT");
        }
        String owner = normalizeClass(className);
        if (!"java.lang.String".equals(owner) && descriptor.indexOf(STRING_DESCRIPTOR) < 0) {
            throw new IllegalArgumentException("Method hook must target java.lang.String or a signature containing "
                    + STRING_DESCRIPTOR);
        }
        String key = normalize(name);
        rejectDuplicate(owner, methodName, descriptor, kind, 0L, key);
        Entry entry = Entry.method(key, kind, owner, methodName, descriptor);
        replaceEntry(key, entry);
        enableOrRemove(entry);
        return entry.info();
    }

    /** Stops the allocating thread when a newly observable String matches the supplied filter. */
    public synchronized JvmStringHookInfo breakAllocation(String name,
            JvmStringAllocationSpec spec) {
        String key = normalize(name);
        if (spec == null) throw new IllegalArgumentException("allocation spec must not be null");
        Entry entry = Entry.allocation(key, spec);
        replaceEntry(key, entry);
        enableOrRemove(entry);
        return entry.info();
    }

    public synchronized List<JvmStringHookInfo> snapshot() {
        List<JvmStringHookInfo> result = new ArrayList<JvmStringHookInfo>();
        for (Entry entry : entries.values()) result.add(entry.info());
        return Collections.unmodifiableList(result);
    }

    public synchronized JvmStringHookInfo info(String name) { return require(name).info(); }
    public synchronized boolean contains(String name) { return entries.containsKey(normalize(name)); }
    public synchronized long revision() { return revision; }

    public synchronized JvmStringHookInfo setEnabled(String name, boolean enabled) {
        Entry entry = require(name);
        if (enabled && entry.enabled && exhausted(entry)) return rearm(name);
        setEnabled(entry, enabled);
        return entry.info();
    }

    /** Resets native sampling/hit limits and arms an allocation hook again. */
    public synchronized JvmStringHookInfo rearm(String name) {
        Entry entry = require(name);
        if (entry.kind != JvmStringHookKind.ALLOCATION) {
            throw new IllegalStateException("Only allocation hooks can be rearmed");
        }
        if (!entry.enabled) setEnabled(entry, true);
        else jvmti.setStringAllocationHook(entry.registrationId, entry.allocationSpec, true);
        entry.hitCount = 0L;
        entry.lastHit = "";
        revision++;
        return entry.info();
    }

    /** Records hits already collected by the shared debugger without taking ownership of states. */
    public synchronized void observe(List<JvmDebuggerState> states) {
        if (states == null) return;
        for (JvmDebuggerState state : states) {
            if (state == null || !state.paused()) continue;
            for (Entry entry : entries.values()) {
                if (!entry.enabled || !matches(entry, state)) continue;
                if (state.sequence() <= entry.lastHitSequence) continue;
                entry.lastHitSequence = state.sequence();
                entry.lastHit = state.reason() + " at " + state.className() + "."
                        + state.methodName() + state.descriptor() + "@" + state.location();
                if (entry.kind == JvmStringHookKind.ALLOCATION) {
                    entry.hitCount++;
                    close(entry.lastValue);
                    entry.lastValue = state.eventValue() == null
                            ? null : jni.retain(state.eventValue(), false);
                    entry.lastValueText = readString(entry.lastValue);
                }
                revision++;
            }
        }
    }

    /** Reads the String field behind a field hook; the caller owns the returned handle. */
    public synchronized RemoteObject acquireValue(String name) {
        Entry entry = require(name);
        if (entry.kind == JvmStringHookKind.ALLOCATION) {
            if (entry.lastValue == null) {
                throw new IllegalStateException("String allocation hook " + entry.name
                        + " has not matched a live value yet");
            }
            return jni.retain(entry.lastValue, false);
        }
        entry = requireField(name);
        return entry.field.isStatic() ? entry.field.readStatic() : entry.field.read(entry.receiver);
    }

    /** Replaces the String reference stored in the field behind a field hook. */
    public synchronized void replaceValue(String name, RemoteObject replacement) {
        if (replacement == null) throw new IllegalArgumentException("replacement must not be null");
        Entry entry = requireField(name);
        if (!replacement.isNull() && !"java.lang.String".equals(replacement.className())) {
            throw new IllegalArgumentException("Replacement must be java.lang.String or null");
        }
        if (entry.field.isStatic()) entry.field.writeStatic(replacement);
        else entry.field.write(entry.receiver, replacement);
        revision++;
    }

    /** Adds a field value or the most recent allocation match to the shared reference manager. */
    public synchronized JvmReferenceInfo trackValue(String hookName,
            JvmReferenceManager references, String referenceName,
            JvmReferenceStrength receiverStrength) {
        if (references == null) throw new IllegalArgumentException("references must not be null");
        Entry entry = require(hookName);
        if (entry.kind == JvmStringHookKind.ALLOCATION) {
            try (RemoteObject value = acquireValue(hookName)) {
                return references.trackObject(referenceName, value,
                        receiverStrength == null ? JvmReferenceStrength.STRONG : receiverStrength);
            }
        }
        entry = requireField(hookName);
        return entry.field.isStatic()
                ? references.trackStaticField(referenceName, entry.field)
                : references.trackField(referenceName, entry.field, entry.receiver,
                        receiverStrength == null ? JvmReferenceStrength.STRONG : receiverStrength);
    }

    public synchronized void remove(String name) {
        Entry entry = entries.remove(normalize(name));
        if (entry == null) throw new IllegalArgumentException("Unknown String hook: " + name);
        disableQuietly(entry);
        close(entry.receiver);
        close(entry.lastValue);
        revision++;
    }

    public synchronized void clear() {
        for (Entry entry : entries.values()) {
            disableQuietly(entry);
            close(entry.receiver);
            close(entry.lastValue);
        }
        entries.clear();
        revision++;
    }

    @Override public void close() { clear(); }

    private void setEnabled(Entry entry, boolean enabled) {
        if (entry.enabled == enabled) return;
        if (enabled) {
            jvmti.configureDebugger(true);
            if (entry.kind == JvmStringHookKind.ALLOCATION) {
                jvmti.setStringAllocationHook(entry.registrationId, entry.allocationSpec, true);
            } else if (entry.field != null) {
                jvmti.setFieldWatch(entry.className, entry.memberName, entry.descriptor,
                        entry.kind == JvmStringHookKind.FIELD_WRITE, entry.receiver, true);
                entry.fieldWatch = findFieldWatch(entry);
            } else {
                entry.eventBreakpoint = jvmti.setEventBreakpoint(entry.kind == JvmStringHookKind.METHOD_ENTRY
                        ? JvmEventBreakpointSpec.methodEntry(entry.className, entry.memberName, entry.descriptor)
                        : JvmEventBreakpointSpec.methodExit(entry.className, entry.memberName, entry.descriptor));
            }
            entry.enabled = true;
        } else {
            if (entry.kind == JvmStringHookKind.ALLOCATION) {
                jvmti.setStringAllocationHook(entry.registrationId, entry.allocationSpec, false);
            }
            if (entry.fieldWatch != null) jvmti.clearFieldWatch(entry.fieldWatch);
            if (entry.eventBreakpoint != null) jvmti.clearEventBreakpoint(entry.eventBreakpoint);
            entry.fieldWatch = null;
            entry.eventBreakpoint = null;
            entry.enabled = false;
        }
        revision++;
    }

    private void enableOrRemove(Entry entry) {
        try {
            setEnabled(entry, true);
        } catch (RuntimeException failure) {
            if (entries.get(entry.name) == entry) entries.remove(entry.name);
            disableQuietly(entry);
            close(entry.receiver);
            close(entry.lastValue);
            revision++;
            throw failure;
        }
    }

    private JvmFieldWatchInfo findFieldWatch(Entry entry) {
        for (JvmFieldWatchInfo info : jvmti.managedFieldWatches()) {
            if (info.className().equals(entry.className)
                    && info.fieldName().equals(entry.memberName)
                    && info.descriptor().equals(entry.descriptor)
                    && info.modification() == (entry.kind == JvmStringHookKind.FIELD_WRITE)
                    && info.receiverId() == (entry.receiver == null ? 0L : entry.receiver.remoteId())) return info;
        }
        throw new IllegalStateException("JVMTI installed the field hook but did not return its registration");
    }

    private static boolean matches(Entry entry, JvmDebuggerState state) {
        String reason = state.reason().toLowerCase(Locale.ROOT);
        if (entry.kind == JvmStringHookKind.ALLOCATION) {
            return "allocation".equals(state.returnState())
                    && reasonContainsRegistration(reason, entry.registrationId);
        }
        if (entry.kind == JvmStringHookKind.METHOD_ENTRY) {
            return reason.contains("method_entry") && exactMethod(entry, state);
        }
        if (entry.kind == JvmStringHookKind.METHOD_EXIT) {
            return reason.contains("method_exit") && exactMethod(entry, state);
        }
        String expected = entry.kind == JvmStringHookKind.FIELD_WRITE ? "modification" : "access";
        String prefix = entry.kind == JvmStringHookKind.FIELD_WRITE ? "field_write:" : "field_read:";
        String precise = prefix
                + entry.className.toLowerCase(Locale.ROOT) + "."
                + entry.memberName.toLowerCase(Locale.ROOT)
                + entry.descriptor.toLowerCase(Locale.ROOT);
        String legacy = prefix + entry.memberName.toLowerCase(Locale.ROOT)
                + entry.descriptor.toLowerCase(Locale.ROOT);
        return reason.contains(precise) || reason.contains(legacy)
                || reason.contains("field") && reason.contains(expected)
                && reason.contains(entry.memberName.toLowerCase(Locale.ROOT));
    }

    private static boolean exhausted(Entry entry) {
        return entry.allocationSpec != null && entry.allocationSpec.maximumHits() > 0L
                && entry.hitCount >= entry.allocationSpec.maximumHits();
    }

    private static boolean reasonContainsRegistration(String reason, String registrationId) {
        String marker = registrationId.toLowerCase(Locale.ROOT);
        int start = reason.indexOf("string_alloc:");
        if (start < 0) return false;
        String[] ids = reason.substring(start + "string_alloc:".length()).split(",");
        for (String id : ids) if (marker.equals(id)) return true;
        return false;
    }

    private static boolean exactMethod(Entry entry, JvmDebuggerState state) {
        return entry.className.equals(state.className()) && entry.memberName.equals(state.methodName())
                && entry.descriptor.equals(state.descriptor());
    }

    private Entry requireField(String name) {
        Entry entry = require(name);
        if (entry.field == null) throw new IllegalStateException("String hook " + name + " is not field-backed");
        if (entry.receiver != null) {
            String state = jni.referenceStatus(entry.receiver);
            if (!"live".equals(state)) throw new IllegalStateException("Hook receiver is " + state);
        }
        return entry;
    }

    private Entry require(String name) {
        Entry entry = entries.get(normalize(name));
        if (entry == null) throw new IllegalArgumentException("Unknown String hook: " + name);
        return entry;
    }

    private Entry replaceEntry(String key, Entry entry) {
        Entry previous = entries.remove(key);
        if (previous != null) {
            disableQuietly(previous);
            close(previous.receiver);
            close(previous.lastValue);
        }
        entries.put(key, entry);
        revision++;
        return entry;
    }

    private void rejectDuplicate(String className, String member, String descriptor,
            JvmStringHookKind kind, long receiverId, String exceptName) {
        for (Entry entry : entries.values()) {
            if (!entry.name.equals(exceptName) && entry.kind == kind
                    && entry.className.equals(className) && entry.memberName.equals(member)
                    && entry.descriptor.equals(descriptor)
                    && (entry.receiver == null ? 0L : entry.receiver.remoteId()) == receiverId) {
                throw new IllegalArgumentException("An equivalent String hook already exists: " + entry.name);
            }
        }
    }

    private void disableQuietly(Entry entry) {
        try { if (entry.enabled) setEnabled(entry, false); }
        catch (RuntimeException ignored) { entry.enabled = false; }
    }

    private static void close(RemoteObject value) {
        if (value == null) return;
        try { value.close(); } catch (RuntimeException ignored) { }
    }

    public static String normalize(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Hook name must not be empty");
        String value = name.trim();
        if (value.charAt(0) == '$' || value.charAt(0) == '@') value = value.substring(1);
        if (value.isEmpty()) throw new IllegalArgumentException("Hook name must not be empty");
        return value;
    }

    private static String normalizeClass(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Class name must not be empty");
        return name.trim().replace('/', '.');
    }

    private static String allocationId(String name) {
        return "string-allocation-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                name.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(RemoteObject value) {
        if (value == null) return "";
        try { return preview(value.asObject(String.class)); }
        catch (RuntimeException failure) { return preview(value.displayValue()); }
    }

    private static String preview(String value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder(Math.min(value.length(), 160));
        for (int index = 0; index < value.length() && result.length() < 160; ++index) {
            char character = value.charAt(index);
            if (character == '\n') result.append("\\n");
            else if (character == '\r') result.append("\\r");
            else if (character == '\t') result.append("\\t");
            else if (Character.isISOControl(character)) result.append('?');
            else result.append(character);
        }
        if (result.length() >= 160 || value.length() > result.length()) result.append("...");
        return result.toString();
    }

    private static final class Entry {
        private final String name;
        private final JvmStringHookKind kind;
        private final String className;
        private final String memberName;
        private final String descriptor;
        private final RemoteField field;
        private final RemoteObject receiver;
        private final String registrationId;
        private final JvmStringAllocationSpec allocationSpec;
        private boolean enabled;
        private JvmFieldWatchInfo fieldWatch;
        private JvmEventBreakpointInfo eventBreakpoint;
        private long lastHitSequence = -1L;
        private String lastHit = "";
        private long hitCount;
        private RemoteObject lastValue;
        private String lastValueText = "";

        private Entry(String name, JvmStringHookKind kind, String className,
                String memberName, String descriptor, RemoteField field, RemoteObject receiver,
                String registrationId, JvmStringAllocationSpec allocationSpec) {
            this.name = name;
            this.kind = kind;
            this.className = className;
            this.memberName = memberName;
            this.descriptor = descriptor;
            this.field = field;
            this.receiver = receiver;
            this.registrationId = registrationId;
            this.allocationSpec = allocationSpec;
        }

        private static Entry field(String name, JvmStringHookKind kind,
                RemoteField field, RemoteObject receiver) {
            return new Entry(name, kind, field.declaringClass(), field.name(),
                    field.descriptor(), field, receiver, "", null);
        }

        private static Entry method(String name, JvmStringHookKind kind,
                String className, String methodName, String descriptor) {
            return new Entry(name, kind, className, methodName, descriptor,
                    null, null, "", null);
        }

        private static Entry allocation(String name, JvmStringAllocationSpec spec) {
            return new Entry(name, JvmStringHookKind.ALLOCATION, "java.lang.String",
                    "<allocation>", STRING_DESCRIPTOR, null, null,
                    allocationId(name), spec);
        }

        private JvmStringHookInfo info() {
            return new JvmStringHookInfo(name, kind, className, memberName, descriptor,
                    receiver != null, enabled, lastHitSequence, lastHit,
                    allocationSpec, hitCount, lastValueText);
        }
    }
}
