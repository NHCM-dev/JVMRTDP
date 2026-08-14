package nhcm.jvmrtdp.api.reference;

import nhcm.jvmrtdp.handles.java.RemoteField;
import nhcm.jvmrtdp.handles.java.RemoteObject;
import nhcm.jvmrtdp.handles.jvm.RemoteJNIEnv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session-scoped manager for independently owned object, instance-field and static-field
 * references. Strong entries keep an object alive; weak entries never consume or overwrite
 * a JVMTI object tag and therefore coexist with heap-tagging tools.
 */
public final class JvmReferenceManager implements AutoCloseable {
    private final RemoteJNIEnv jni;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private long revision;

    public JvmReferenceManager(RemoteJNIEnv jni) {
        if (jni == null) throw new IllegalArgumentException("jni must not be null");
        this.jni = jni;
    }

    public synchronized JvmReferenceInfo trackObject(String name, RemoteObject object,
            JvmReferenceStrength strength) {
        requireObject(object);
        String key = normalize(name);
        JvmReferenceStrength actual = strength(strength);
        RemoteObject retained = jni.retain(object, actual == JvmReferenceStrength.WEAK);
        return replaceEntry(key, new Entry(key, JvmReferenceKind.OBJECT, actual,
                retained, null, retained, "object snapshot", false)).info();
    }

    public synchronized JvmReferenceInfo trackField(String name, RemoteField field,
            RemoteObject receiver, JvmReferenceStrength receiverStrength) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        if (field.isStatic()) return trackStaticField(name, field);
        requireObject(receiver);
        JvmReferenceStrength actual = strength(receiverStrength);
        RemoteObject retainedReceiver = jni.retain(receiver, actual == JvmReferenceStrength.WEAK);
        String key = normalize(name);
        Entry entry = new Entry(key, JvmReferenceKind.INSTANCE_FIELD, actual,
                retainedReceiver, field, null,
                "field " + field.declaringClass() + "." + field.name(), true);
        replaceEntry(key, entry);
        refreshEntry(entry);
        return entry.info();
    }

    public synchronized JvmReferenceInfo trackStaticField(String name, RemoteField field) {
        if (field == null || !field.isStatic()) {
            throw new IllegalArgumentException("A static field is required");
        }
        String key = normalize(name);
        Entry entry = new Entry(key, JvmReferenceKind.STATIC_FIELD,
                JvmReferenceStrength.STRONG, null, field, null,
                "static field " + field.declaringClass() + "." + field.name(), true);
        replaceEntry(key, entry);
        refreshEntry(entry);
        return entry.info();
    }

    public synchronized boolean contains(String name) { return entries.containsKey(normalize(name)); }

    public synchronized long revision() { return revision; }

    /** Returns the latest cached state without target-JVM I/O. */
    public synchronized List<JvmReferenceInfo> snapshot() {
        List<JvmReferenceInfo> result = new ArrayList<JvmReferenceInfo>();
        for (Entry entry : entries.values()) result.add(entry.info());
        return Collections.unmodifiableList(result);
    }

    public synchronized JvmReferenceInfo info(String name) { return require(name).info(); }

    public synchronized JvmReferenceInfo refresh(String name) {
        Entry entry = require(name);
        refreshEntry(entry);
        return entry.info();
    }

    public synchronized List<JvmReferenceInfo> refreshAll() {
        for (Entry entry : entries.values()) refreshEntry(entry);
        return snapshot();
    }

    /** Returns a new strong handle owned by the caller. */
    public synchronized RemoteObject acquire(String name) {
        Entry entry = require(name);
        refreshEntry(entry);
        if (entry.state == JvmReferenceState.COLLECTED
                || entry.state == JvmReferenceState.RELEASED
                || entry.state == JvmReferenceState.ERROR) {
            throw new IllegalStateException("Tracked reference " + entry.name + " is "
                    + entry.state.name().toLowerCase(Locale.ROOT)
                    + (entry.error.isEmpty() ? "" : ": " + entry.error));
        }
        return jni.retain(entry.value, false);
    }

    /** Replaces either the tracked object slot or the tracked field value. */
    public synchronized JvmReferenceInfo replace(String name, RemoteObject replacement) {
        requireObject(replacement);
        Entry entry = require(name);
        if (entry.kind == JvmReferenceKind.OBJECT) {
            RemoteObject next = jni.retain(replacement,
                    entry.strength == JvmReferenceStrength.WEAK);
            close(entry.anchor);
            entry.anchor = next;
            entry.value = next;
            entry.error = "";
            updateState(entry, next);
        } else {
            if (entry.kind == JvmReferenceKind.INSTANCE_FIELD) ensureAnchor(entry);
            if (entry.kind == JvmReferenceKind.STATIC_FIELD) entry.field.writeStatic(replacement);
            else entry.field.write(entry.anchor, replacement);
            refreshEntry(entry);
        }
        revision++;
        return entry.info();
    }

    public synchronized JvmReferenceInfo setNull(String name) {
        try (RemoteObject value = jni.valueOf(null)) { return replace(name, value); }
    }

    public synchronized void release(String name) {
        Entry entry = entries.remove(normalize(name));
        if (entry == null) throw new IllegalArgumentException("Unknown tracked reference: " + name);
        entry.close();
        revision++;
    }

    public synchronized void releaseAll() {
        for (Entry entry : entries.values()) entry.close();
        entries.clear();
        revision++;
    }

    @Override public void close() { releaseAll(); }

    private Entry replaceEntry(String key, Entry entry) {
        Entry previous = entries.put(key, entry);
        if (previous != null) previous.close();
        revision++;
        return entry;
    }

    private void refreshEntry(Entry entry) {
        if (entry.state == JvmReferenceState.RELEASED) return;
        try {
            if (entry.kind == JvmReferenceKind.OBJECT) {
                ensureAnchor(entry);
                entry.anchor.refresh();
                entry.value = entry.anchor;
            } else {
                if (entry.kind == JvmReferenceKind.INSTANCE_FIELD) ensureAnchor(entry);
                RemoteObject next = entry.kind == JvmReferenceKind.STATIC_FIELD
                        ? entry.field.readStatic() : entry.field.read(entry.anchor);
                close(entry.value);
                entry.value = next;
            }
            entry.error = "";
            updateState(entry, entry.value);
        } catch (RuntimeException failure) {
            String status = entry.anchor == null ? "" : safeStatus(entry.anchor);
            if ("collected".equals(status)) entry.state = JvmReferenceState.COLLECTED;
            else if ("released".equals(status)) entry.state = JvmReferenceState.RELEASED;
            else entry.state = JvmReferenceState.ERROR;
            entry.error = rootMessage(failure);
        }
        revision++;
    }

    private void ensureAnchor(Entry entry) {
        String status = safeStatus(entry.anchor);
        if ("collected".equals(status)) throw new IllegalStateException("Tracked receiver was garbage collected");
        if ("released".equals(status)) throw new IllegalStateException("Tracked receiver was released");
    }

    private String safeStatus(RemoteObject object) {
        try { return jni.referenceStatus(object); }
        catch (RuntimeException failure) { return object != null && object.isReleased() ? "released" : ""; }
    }

    private static void updateState(Entry entry, RemoteObject value) {
        entry.state = value != null && value.isNull()
                ? JvmReferenceState.NULL : JvmReferenceState.LIVE;
    }

    private Entry require(String name) {
        Entry entry = entries.get(normalize(name));
        if (entry == null) throw new IllegalArgumentException("Unknown tracked reference: " + name);
        return entry;
    }

    private void requireObject(RemoteObject object) {
        if (object == null) throw new IllegalArgumentException("object must not be null");
        if (object.server() != jni.server()) throw new IllegalArgumentException("Object belongs to another session");
        if (object.isReleased()) throw new IllegalStateException("Object has already been released");
    }

    private static JvmReferenceStrength strength(JvmReferenceStrength value) {
        return value == null ? JvmReferenceStrength.STRONG : value;
    }

    public static String normalize(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Reference name must not be empty");
        String result = name.trim();
        if (result.charAt(0) == '$' || result.charAt(0) == '@' || result.charAt(0) == '&') {
            result = result.substring(1);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Reference name must not be empty");
        return result;
    }

    private static void close(RemoteObject object) {
        if (object == null) return;
        try { object.close(); } catch (RuntimeException ignored) { }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static final class Entry {
        private final String name;
        private final JvmReferenceKind kind;
        private final JvmReferenceStrength strength;
        private RemoteObject anchor;
        private final RemoteField field;
        private RemoteObject value;
        private final String source;
        private final boolean assignable;
        private JvmReferenceState state = JvmReferenceState.LIVE;
        private String error = "";

        private Entry(String name, JvmReferenceKind kind, JvmReferenceStrength strength,
                RemoteObject anchor, RemoteField field, RemoteObject value,
                String source, boolean assignable) {
            this.name = name;
            this.kind = kind;
            this.strength = strength;
            this.anchor = anchor;
            this.field = field;
            this.value = value;
            this.source = source;
            this.assignable = assignable || kind == JvmReferenceKind.OBJECT;
            updateState(this, value);
        }

        private JvmReferenceInfo info() {
            return new JvmReferenceInfo(name, kind, strength, state,
                    value == null || value.isReleased() ? 0L : value.remoteId(),
                    value == null ? "" : value.className(),
                    value == null ? "" : value.displayValue(), source, assignable, error);
        }

        private void close() {
            if (value != anchor) JvmReferenceManager.close(value);
            JvmReferenceManager.close(anchor);
            value = null;
            anchor = null;
            state = JvmReferenceState.RELEASED;
        }
    }
}
