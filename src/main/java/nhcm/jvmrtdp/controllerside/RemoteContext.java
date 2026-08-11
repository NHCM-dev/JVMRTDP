package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Current receiver, navigation history and named bookmarks for one target session. */
public class RemoteContext implements AutoCloseable {
    private static final int MAX_STACK_DEPTH = 1_024;

    private final Deque<Value> history = new ArrayDeque<Value>();
    private final Map<String, Value> bookmarks = new LinkedHashMap<String, Value>();
    private final Set<RemoteObject> retainedObjects =
            Collections.newSetFromMap(new IdentityHashMap<RemoteObject, Boolean>());
    private Value current;
    private int temporaryScopeDepth;
    private long revision;

    public void select(RemoteClass value) {
        select(Value.of(value));
    }

    public void select(RemoteObject value) {
        select(value, null);
    }

    public void select(RemoteObject value, RemoteClass viewClass) {
        select(value, viewClass, null);
    }

    /** Selects a value while retaining the writable field/array/local that produced it. */
    public void select(RemoteObject value, RemoteClass viewClass, Assignment assignment) {
        if (value == null) throw new IllegalArgumentException("Context object must not be null");
        if (viewClass != null && viewClass.server() != value.server()) {
            throw new IllegalArgumentException("Context type belongs to another target session");
        }
        if (viewClass != null && !value.isNull() && !viewClass.isInstance(value)) {
            throw new IllegalArgumentException(value.className() + " is not assignable to " + viewClass.className());
        }
        retainedObjects.add(value);
        select(Value.of(value, viewClass, assignment));
    }

    public void viewAs(RemoteClass viewClass) {
        Assignment assignment = requireCurrent().assignment;
        RemoteObject object = remoteObject();
        select(object, viewClass, assignment);
    }

    public void runtimeView() {
        Assignment assignment = requireCurrent().assignment;
        RemoteObject object = remoteObject();
        select(object, null, assignment);
    }

    public boolean isSet() {
        return current != null;
    }

    public boolean isClass() {
        return requireCurrent().remoteClass != null;
    }

    public boolean isObject() {
        return requireCurrent().remoteObject != null;
    }

    public RemoteClass remoteClass() {
        Value value = requireCurrent();
        return value.remoteClass != null ? value.remoteClass
                : value.viewClass != null ? value.viewClass : value.remoteObject.remoteClass();
    }

    public RemoteObject remoteObject() {
        Value value = requireCurrent();
        if (value.remoteObject == null) {
            throw new IllegalStateException("Current context is a class, not an object: " + value.remoteClass.className());
        }
        return value.remoteObject;
    }

    public boolean canAssign() {
        return current != null && current.remoteObject != null && current.assignment != null;
    }

    public String assignmentDescription() {
        return canAssign() ? current.assignment.description() : "<read-only snapshot>";
    }

    /** Writes a replacement through the current field/array/local and keeps that l-value selected. */
    public void assign(RemoteObject replacement) {
        if (!canAssign()) {
            throw new IllegalStateException(
                    "Current context is not backed by a writable field, array element, or debugger local");
        }
        if (replacement == null) throw new IllegalArgumentException("Replacement value must not be null");
        Value previous = current;
        previous.assignment.write(replacement);
        retainedObjects.add(replacement);
        current = Value.of(replacement, previous.viewClass, previous.assignment);
        revision++;
    }

    public void back() {
        pop(1);
    }

    public void pop(int count) {
        if (count < 1) throw new IllegalArgumentException("Pop count must be positive");
        requireCurrent();
        if (count > history.size()) {
            throw new IllegalStateException(
                    "Cannot pop " + count + " context(s); stack contains " + depth());
        }
        for (int index = 0; index < count; index++) current = history.pop();
        revision++;
    }

    public void duplicate() {
        Value value = requireCurrent();
        pushHistory(value);
        revision++;
    }

    public void swap() {
        requireCurrent();
        if (history.isEmpty()) throw new IllegalStateException("Context stack has no second item");
        Value previous = current;
        current = history.pop();
        history.push(previous);
        revision++;
    }

    /** Copies a stack item to the top. Zero means the current context. */
    public void pick(int stackIndex) {
        Value selected = valueAt(stackIndex);
        pushHistory(requireCurrent());
        current = selected;
        revision++;
    }

    /** Moves an existing stack item to the top without duplicating it. */
    public void moveToTop(int stackIndex) {
        if (stackIndex == 0) { requireCurrent(); return; }
        Value selected = valueAt(stackIndex);
        Deque<Value> rebuilt = new ArrayDeque<Value>();
        int index = 1;
        for (Value value : history) {
            if (index++ != stackIndex) rebuilt.addLast(value);
        }
        rebuilt.addFirst(requireCurrent());
        history.clear();
        history.addAll(rebuilt);
        current = selected;
        revision++;
    }

    /** Removes one stack item. Removing the top promotes the next item. */
    public void remove(int stackIndex) {
        requireCurrent();
        if (depth() == 1) {
            if (stackIndex != 0) valueAt(stackIndex);
            current = null;
            revision++;
            return;
        }
        if (stackIndex == 0) {
            current = history.pop();
            revision++;
            return;
        }
        valueAt(stackIndex);
        Deque<Value> rebuilt = new ArrayDeque<Value>();
        int index = 1;
        for (Value value : history) {
            if (index++ != stackIndex) rebuilt.addLast(value);
        }
        history.clear();
        history.addAll(rebuilt);
        revision++;
    }

    public int depth() {
        return current == null ? 0 : history.size() + 1;
    }

    /**
     * Changes whenever the persistent context or its stack changes. Temporary command
     * pipelines restore the previous token together with their context snapshot.
     * Interaction layers use this to invalidate only their derived views instead of
     * maintaining a second, unsynchronised notion of the current context.
     */
    public long revision() {
        return revision;
    }

    public String peek(int stackIndex) {
        return valueAt(stackIndex).description();
    }

    /** Returns top-first descriptions, where index zero is the current context. */
    public List<String> stack(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Stack list limit must be positive");
        List<String> result = new ArrayList<String>();
        if (current == null) return Collections.unmodifiableList(result);
        result.add(current.description());
        for (Value value : history) {
            if (result.size() >= limit) break;
            result.add(value.description());
        }
        return Collections.unmodifiableList(result);
    }

    public void clear() {
        current = null;
        history.clear();
        bookmarks.clear();
        revision++;
        // A -> chain owns only a temporary navigation view. Handles reachable before the
        // chain must remain alive so its snapshot can be restored when the chain finishes.
        if (temporaryScopeDepth > 0) return;
        for (RemoteObject object : retainedObjects) {
            try {
                object.close();
            } catch (RuntimeException ignored) {
            }
        }
        retainedObjects.clear();
    }

    /**
     * Opens a temporary navigation scope. Closing it restores current context, stack and
     * bookmarks exactly as they were, while keeping any newly observed handles tracked for
     * release with this session.
     */
    public TemporaryScope temporaryScope() {
        temporaryScopeDepth++;
        return new TemporaryScope(current, new ArrayDeque<Value>(history),
                new LinkedHashMap<String, Value>(bookmarks), revision);
    }

    public void save(String name) {
        bookmarks.put(RemoteWorkspace.normalize(name), requireCurrent());
        revision++;
    }

    public void use(String name) {
        Value value = bookmarks.get(RemoteWorkspace.normalize(name));
        if (value == null) throw new IllegalArgumentException("Unknown context bookmark: " + name);
        select(value);
    }

    public Map<String, String> bookmarks() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Value> entry : bookmarks.entrySet()) {
            result.put(entry.getKey(), entry.getValue().description());
        }
        return Collections.unmodifiableMap(result);
    }

    public String description() {
        return isSet() ? current.description() : "<unset>";
    }

    /** Best-effort prompt refresh; a failing toString() must not break the shell. */
    public String refreshedDescription() {
        if (!isSet()) return "<unset>";
        if (current.remoteObject != null && !current.remoteObject.isReleased()) {
            try {
                current.remoteObject.refresh();
            } catch (RuntimeException ignored) {
                // Keep the last known snapshot so the user can still navigate away or detach.
            }
        }
        return current.description();
    }

    @Override
    public void close() {
        for (RemoteObject object : retainedObjects) {
            try {
                object.close();
            } catch (RuntimeException ignored) {
            }
        }
        retainedObjects.clear();
        bookmarks.clear();
        history.clear();
        current = null;
    }

    private void select(Value value) {
        if (temporaryScopeDepth > 0) {
            current = value;
            revision++;
            return;
        }
        if (current != null && current != value) pushHistory(current);
        current = value;
        revision++;
    }

    private void pushHistory(Value value) {
        if (history.size() >= MAX_STACK_DEPTH - 1) history.removeLast();
        history.push(value);
    }

    private Value valueAt(int stackIndex) {
        if (stackIndex < 0) throw new IllegalArgumentException("Stack index must not be negative");
        requireCurrent();
        if (stackIndex == 0) return current;
        int index = 1;
        for (Value value : history) {
            if (index++ == stackIndex) return value;
        }
        throw new IndexOutOfBoundsException(
                "Context stack index " + stackIndex + " is outside [0, " + depth() + ")");
    }

    private Value requireCurrent() {
        if (current == null) throw new IllegalStateException("No context selected; use 'context class <name>' first");
        return current;
    }

    public final class TemporaryScope implements AutoCloseable {
        private final Value savedCurrent;
        private final Deque<Value> savedHistory;
        private final Map<String, Value> savedBookmarks;
        private final long savedRevision;
        private boolean closed;

        private TemporaryScope(Value savedCurrent, Deque<Value> savedHistory,
                Map<String, Value> savedBookmarks, long savedRevision) {
            this.savedCurrent = savedCurrent;
            this.savedHistory = savedHistory;
            this.savedBookmarks = savedBookmarks;
            this.savedRevision = savedRevision;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            current = savedCurrent;
            history.clear();
            history.addAll(savedHistory);
            bookmarks.clear();
            bookmarks.putAll(savedBookmarks);
            revision = savedRevision;
            temporaryScopeDepth--;
        }
    }

    private static class Value {
        private final RemoteClass remoteClass;
        private final RemoteObject remoteObject;
        private final RemoteClass viewClass;
        private final Assignment assignment;

        private Value(RemoteClass remoteClass, RemoteObject remoteObject, RemoteClass viewClass,
                Assignment assignment) {
            this.remoteClass = remoteClass;
            this.remoteObject = remoteObject;
            this.viewClass = viewClass;
            this.assignment = assignment;
        }

        private static Value of(RemoteClass value) {
            return new Value(value, null, null, null);
        }

        private static Value of(RemoteObject value, RemoteClass viewClass) {
            return of(value, viewClass, null);
        }

        private static Value of(RemoteObject value, RemoteClass viewClass, Assignment assignment) {
            return new Value(null, value, viewClass, assignment);
        }

        private String description() {
            if (remoteClass != null) return "class " + remoteClass.className();
            String description = remoteObject.toString();
            if (viewClass != null && !viewClass.className().equals(remoteObject.className())) {
                description += " as " + viewClass.className();
            }
            return description;
        }
    }

    /** A writable source retained by a context value. */
    public interface Assignment {
        void write(RemoteObject value);
        String description();
    }
}
