package nhcm.jvmrtdp.controllerside;

import nhcm.jvmrtdp.handles.java.RemoteClass;
import nhcm.jvmrtdp.handles.java.RemoteObject;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Named handles shared by interactive commands and scripts. */
public class RemoteWorkspace implements AutoCloseable {
    private final TargetSession session;
    private final Map<String, RemoteClass> classes = new LinkedHashMap<String, RemoteClass>();
    private final Map<String, RemoteObject> objects = new LinkedHashMap<String, RemoteObject>();

    public RemoteWorkspace(TargetSession session) {
        this.session = session;
    }

    public RemoteClass defineClass(String name, String className) {
        RemoteClass remoteClass = session.findClass(className);
        classes.put(normalize(name), remoteClass);
        return remoteClass;
    }

    public RemoteObject defineObject(String name, RemoteObject object) {
        String normalized = normalize(name);
        RemoteObject previous = objects.put(normalized, object);
        if (previous != null && previous != object) previous.close();
        return object;
    }

    public RemoteClass classValue(String reference) {
        String normalized = normalize(reference);
        RemoteClass value = classes.get(normalized);
        if (value != null) return value;
        if (reference.charAt(0) == '$' || reference.charAt(0) == '@') {
            throw new IllegalArgumentException("Unknown class variable: " + reference);
        }
        return session.findClass(reference);
    }

    public RemoteObject objectValue(String reference) {
        RemoteObject value = objects.get(normalize(reference));
        if (value == null) throw new IllegalArgumentException("Unknown object variable: " + reference);
        return value;
    }

    public Map<String, RemoteClass> classes() {
        return Collections.unmodifiableMap(classes);
    }

    public Map<String, RemoteObject> objects() {
        return Collections.unmodifiableMap(objects);
    }

    public void release(String reference) {
        RemoteObject object = objects.remove(normalize(reference));
        if (object == null) throw new IllegalArgumentException("Unknown object variable: " + reference);
        object.close();
    }

    @Override
    public void close() {
        Collection<RemoteObject> values = objects.values();
        for (RemoteObject object : values) {
            try {
                object.close();
            } catch (RuntimeException ignored) {
            }
        }
        objects.clear();
        classes.clear();
    }

    public static String normalize(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("Variable name must not be empty");
        }
        String value = reference.trim();
        if (value.charAt(0) == '$' || value.charAt(0) == '@') value = value.substring(1);
        if (value.isEmpty()) throw new IllegalArgumentException("Variable name must not be empty");
        return value;
    }
}
